# ConvertJPGtoHEIC — working notes

Android app that bulk-converts a phone's JPG library to HEIC via `androidx.heifwriter`,
publishes through MediaStore, and offers to delete the originals.

## Build

```bash
JAVA_HOME="C:/Program Files/Android/Android Studio/jbr" ./gradlew :app:assembleDebug
JAVA_HOME="C:/Program Files/Android/Android Studio/jbr" ./gradlew :app:testDebugUnitTest
```

APK lands at `app/build/outputs/apk/debug/app-debug.apk`.

---

## PICK UP HERE (2026-09-26)

### 1. Uncommitted fixes in the working tree

Three fixes from the 2026-09-26 session are **built, installed and verified on device, but
NOT committed**. Commit them before starting anything new.

```
M app/src/main/java/com/example/convertjpgtoheic/ConversionEngine.kt
M app/src/main/java/com/example/convertjpgtoheic/HeicEncoder.kt
```

- **HeifWriter thread leak** — `HeicEncoder` now passes its own `Handler` via
  `Builder.setHandler()`, plus `release()` from `ConversionEngine` at end of run.
- **`awaitClose()`** — `HeifWriter.close()` only *posts* the muxer stop, so the output file was
  being measured before it had flushed.
- **Bitmap budget + pixel ceiling** — see "Two ways a run used to wedge" below.

### 2. Open problems, roughly in priority order

**a. Capture dates missing on 16% of converted files.** 4150 of ~25,750 HEICs have
`datetaken = NULL` in MediaStore, so they sort wrong in the gallery. Established:

- The date is **not lost** — `DateTimeOriginal` is intact in every affected file's EXIF.
- `DATE_TAKEN` **cannot be written after publish**. Verified directly: `content update` on a
  published row returns no error and changes nothing, and the app's own
  `applyCaptureDate` (updating a row it owns) fails the same way. MediaProvider treats it as
  derived from the file. This is why `applyCaptureDate` logs
  "Could not restamp the capture date" — the retry can never work.
- A forced `scan_file` does **not** recover it.
- The deciding factor is MediaProvider's EXIF extractor. Copying a good file and a bad file to
  fresh paths and letting MediaProvider index them cleanly reproduces it exactly: good → correct
  date, bad → NULL. So it is something *in the file*.
- But **not** the HEIF container — a good and a bad file were byte-identical in box structure,
  same `meta` size (2529), both with a valid `DateTimeOriginal`. The difference is inside the
  EXIF block the app copies verbatim from the source JPEG. Bad file had no GPS IFD, no thumbnail
  IFD1, no SubSecTime tags, 18 ExifIFD entries, and `ImageWidth`/`ImageLength` in IFD0; good file
  had GPS + IFD1 + SubSecTime and 30 entries.
- **UNRESOLVED:** which of those differences actually trips the extractor. Do not guess — bisect
  it by mutating a copy of a failing file's EXIF and re-scanning.
- Likely fix direction: synthesise a normalised EXIF block (extend `MinimalExif`) rather than
  copying the source's verbatim, so extraction is predictable. Recovering the existing 4150 needs
  a separate pass, and direct column writes won't do it.

**b. `addBitmap` has no timeout.** Any encoder stall wedges the whole run with no way out —
`writer.stop(STOP_TIMEOUT_MS)` is never reached because the block happens earlier. The 64 MP
ceiling avoids the one known trigger but is not a general guard. A real watchdog is awkward: the
call blocks on the calling thread inside EGL, so timing it out means abandoning a thread
mid-render.

**c. Old panoramas "don't convert" — this was the original reported bug, and it is NOT a bug.**
They encode fine, then get discarded by "Only keep if smaller". 2014–2016 panoramas are already
compressed to ~0.29 bytes/px; HeifWriter's bitrate formula
(`w*h*1.5*8*0.25*quality/100`) caps around 0.3 bytes/px at quality 80, so the HEIC comes out
marginally larger. 2020/21 panoramas convert because they start at ~0.8 bytes/px.
Dropping quality to ~70, or turning off "Only keep if smaller", would convert them — for little
or no space saving. **Decide with the user whether this is worth doing at all.**

**d. Two non-zero orphaned pending files** left by the wedged runs. Left in place because they
hold real encoded data and a partial HEIC is harder to judge safe than an empty one. Once a run
completes cleanly, anything still there is provably stale.

```
/storage/emulated/0/DCIM/Camera/.pending-1790830549-20220129_131506.heic   412318
/storage/emulated/0/DCIM/Camera/.pending-1790465830-20190526_124424.heic   415896
```

### 3. State left on the device

A conversion run was **still going** when the session ended (foreground service, survives
unplugging). Range 2021-09-16 → now, ~11,006 photos. HEIC count was 25,851 and climbing.
Whatever it reports on completion is the first clean full-run result we've ever had.

---

## Two ways a run used to wedge

Both presented identically — progress frozen, app alive, **zero CPU**. Worth knowing because the
symptom is indistinguishable from "slow" until you measure.

**1. Thread leak in `androidx.heifwriter:1.1.0` (library bug).** It starts a `HeifEncoderThread`
in *both* `WriterBase` (line ~146) and `EncoderBase` (line ~277) — two per encode — and `quit()`
appears **nowhere in the library**. `close()` only stops the muxer and codec. Observed **4226
live threads** in one process. Fixed by supplying our own `Handler`: both constructors take the
given looper and skip creating a thread.

**2. Our own `largeMemoryClass` floor.** `bitmapBudgetBytes()` used `largeMemoryClass` (512 MB
here, `dalvik.vm.heapsize=512m`) as a *lower bound* on the budget. A 12000x9000 (108 MP) shot
needs 432 MB, slipped under that floor, and was decoded on a phone with 387 MB free. The decode
survived; the **tile grid** killed it — HeifWriter cuts into 512x512 cells, so 108 MP = 432 tiles
and the encoder stalled inside `addBitmap`. Fixed by lowering the floor to 128 MB and adding
`MAX_ENCODE_PIXELS = 64_000_000`.

**Why a per-side limit is not enough:** `GL_MAX_TEXTURE_SIZE` bounds each side, but *area* sets
tile count. 12656x3744 (47 MP, 200 tiles) encodes fine; 12000x9000 (108 MP, 432 tiles) wedges —
and both sides are under the 16384 texture limit. The 64 MP figure is bracketed by those two data
points, **not** a measured threshold; bisect it if it ever matters.

---

## Device / tooling gotchas

Test device: **SM-G998B** (Galaxy S21 Ultra, Exynos), Android 15, **Mali-G78**.
`GL_MAX_TEXTURE_SIZE` is >= 12000 (so 16384, not the 8192 Mali is often assumed to report).
`dalvik.vm.heapsize=512m`, `heapgrowthlimit=256m`. Library is large (~25k HEIC, ~18k JPG) and the
phone runs close to full memory — treat low-memory behaviour as the normal case, not the edge.

**adb** is not on PATH: `C:\Users\RayAb\AppData\Local\Android\Sdk\platform-tools\adb.exe`

**Use PowerShell for adb, not the Bash tool.** Git Bash rewrites POSIX paths — an
`adb push ... /data/local/tmp/x.apk` silently became `C:/Program Files/Git/data/local/tmp/x.apk`.
Cost real time before it was spotted.

**The USB cable is faulty in the write direction.** Reads were fine all day (38 MB pulls at
34 MB/s); every ~15 MB *write* died mid-transfer with `failed to read copy response: EOF`, often
leaving a 0-byte file, and knocked the device to `offline`. This masqueraded convincingly as
device memory pressure. **Wi-Fi ADB installed the same APK first try** — use that until the cable
is replaced:

```powershell
adb tcpip 5555; adb connect <phone-ip>:5555; adb -s <ip>:5555 install -r <apk>
adb usb        # RESTORE THIS AFTERWARDS - otherwise adbd keeps listening on the network
```

When the device goes `offline`, `adb kill-server; adb start-server` usually recovers it; it
sometimes takes several cycles.

## Diagnosing a frozen run

`jdb` does not work on Android 15 (classic JDWP is gone), and `debuggerd -j` needs root, so Java
stack dumps are unavailable on a user build. What does work:

```bash
# blocked vs merely slow - the single most useful check
adb shell "cut -d' ' -f14 /proc/<pid>/stat; sleep 4; cut -d' ' -f14 /proc/<pid>/stat"

# thread census - catches leaks instantly
adb shell "cat /proc/<pid>/task/*/comm" | sort | uniq -c | sort -rn
```

Healthy during a run: ~45-55 threads, exactly one `heic-encode`, zero `HeifEncoderThread`.

Library sources for reading `HeifWriter` internals (no network needed):

```
~/.gradle/caches/modules-2/files-2.1/androidx.heifwriter/heifwriter/1.1.0/*/heifwriter-1.1.0-sources.jar
```

## Reading the progress counters

The tallies account for every photo, which makes them a reliable deduction tool:
`converted + under-1MB + already-done + not-smaller + name-clash + failed + 1 in progress`
should equal the current index. Use that to work out which bucket a given photo fell into when
there is no per-file log line.

Photos are processed **oldest first** (`DATE_TAKEN ASC, DATE_MODIFIED ASC`), and rows with a NULL
`DATE_TAKEN` sort first. Early in a run `converted` stays at 0 for a long time — that is expected,
not a fault: old photos are small and already compressed. Savings start at the newer end.
