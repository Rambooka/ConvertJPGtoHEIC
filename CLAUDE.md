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

## PICK UP HERE (2026-09-26, evening)

Everything below is on branch `fix/encoder-thread-leak-and-pixel-ceiling` (pushed, **not merged
to `main`**). Installed on the phone and verified there.

### 1. Done and proven on the device

**Wedging runs** (thread leak, bitmap budget, 64 MP ceiling) — see "Two ways a run used to wedge".
Held over a ~4 h unattended run: 50 threads flat, 0 failures.

**Converter output is now correct for new conversions** (`ConversionEngine.anchorCaptureTime`):
- EXIF Orientation is **kept** matching the container `irot` (how Samsung's camera writes HEIC).
  It used to be zeroed, which left MediaStore's `orientation` column — read from EXIF alone — at 0.
- `OffsetTimeOriginal` is added, plus `DateTimeOriginal` when a file has only `DateTime`
  (Google PhotoScan). See "Why converted photos lost their dates" below.

**"Repair converted photos" button** (`HeicRepair`, `HeifExif`, `ExifEditor`) fixed the existing
library in place — EXIF only, the image never re-encoded. Result of the full run:
**4,084 dates restored, 15,351 rotations fixed**, confirmed from MediaStore (undated HEICs
4,168 → 84; orientation ≠ 0: 20 → 15,371). Pre-flighted on 20 real files first: all verified, all
decode identically in Windows' HEIF codec.

**Estimated completion time** on every repair step (`Eta`); the refresh step shows "X of Y", and
waits while the scanner is still making progress instead of on a fixed clock.

**"Convert videos to HEVC" button** (`VideoConverter`, `Mp4Metadata`) — first milestone done:
- 720p30 test (6 clips, 140 MB): **83 MB, 40% saved**, ~5× real time.
- 1080p60 test (28 s, 78 MB): **44 MB, 43% saved**, encoded in **3.9 s** (~7× real time).
- Every copy verified: `hvc1`, identical `mvhd` capture time, `©xyz` location, rotation matrix,
  frame rate; AAC audio passed through. The user compared them on the phone and in VLC:
  identical, and the correct way up.

### 2. Next steps

1. **Video: exercise the delete-then-rename path.** Only tested with "Delete originals" off. With
   it on, originals go in batches of 25 and each `<name>_HEVC.mp4` is renamed to the original's
   name afterwards (`convertVideos.flushDeletions`). Untested on the device.
2. **Video: a long run**, to see thermal throttling. 540 videos / 18.4 GB; at the measured pace
   roughly 20–40 minutes of encoding.
3. **Video bitrate** is 55% of the source's (`HEVC_BITRATE_SHARE`), deliberately conservative: the
   result was visually identical. Lower is probably fine; ffmpeg SSIM would settle it (not
   installed — ask before `winget install Gyan.FFmpeg`).
4. **Video: not handled yet** — `.mov` (15), `.avi` (2), anything above 4K. Deliberately skipped:
   slow motion, hyperlapse and any other Samsung special mode (they carry a SEF trailer:
   `SlowMotion_Data`, `HyperLapse_Data_Speed`; normal videos carry none).
5. **Photos: 84 HEICs remain undated** — expected to be images with no capture time anywhere
   (downloads, templates). Check the repair report's "No date anywhere" count against that.

### 3. Open problems

**a. `addBitmap` has no timeout.** Any encoder stall wedges the whole run with no way out —
`writer.stop(STOP_TIMEOUT_MS)` is never reached because the block happens earlier. The 64 MP
ceiling avoids the one known trigger but is not a general guard. A real watchdog is awkward: the
call blocks on the calling thread inside EGL, so timing it out means abandoning a thread
mid-render.

**b. Old panoramas "don't convert" — the original reported bug, and NOT a bug.** They encode
fine, then get discarded by "Only keep if smaller". 2014–2016 panoramas are already compressed to
~0.29 bytes/px; HeifWriter's bitrate formula (`w*h*1.5*8*0.25*quality/100`) caps around
0.3 bytes/px at quality 80, so the HEIC comes out marginally larger. **The user's call** whether
to lower quality for them.

**c. Two non-zero orphaned pending files** from the wedged runs, left because they hold real
data: `DCIM/Camera/.pending-1790830549-20220129_131506.heic` (412318) and
`.pending-1790465830-20190526_124424.heic` (415896). Provably stale now — safe to remove, with
the user's OK.

**d. A run cannot pass a consent prompt unattended.** Photo runs suspend on the delete prompt
every 500, the repair on its "allow changes" prompt, videos every 25. If the screen locks while
MediaProvider's dialog is up, the dialog is dismissed and **does not re-raise** (`dumpsys` showed
`PermissionActivity ... isExiting`). Cancel and start again recovers, but a "resume pending
prompt" affordance would be better.

**e. MediaProvider rarely rescans a same-length rewrite.** A rotation-only repair is a 2-byte
in-place patch; most were not picked up until rescanned explicitly (~4 files/s). That is why the
repair has a slow "Refreshing the gallery" step. Date fixes grow the file and were mostly picked
up on close.

### 4. State left on the device

App settings are **still set for the video test**: range 22 Sep 2021 only, "Delete originals" off.
The user's own settings (backed up in the session scratchpad as `prefs-before-video-test.xml`):
`motionPolicy=DROP_VIDEO`, `shrinkOversized=true`, range 2021-09-16 → 2026-09-26, other keys
default. **Restore before a real run.**

Test output on the phone, beside the originals: 7 `*_HEVC.mp4` files in `DCIM/Camera` from
4–10 Oct 2021 and 22 Sep 2021 (~127 MB). Keep or delete as the user prefers.

---

## Why converted photos lost their dates (fixed)

`DateTimeOriginal` carries no timezone, so MediaProvider's scanner only records a `DATE_TAKEN`
when it can infer one, in this order:

1. `OffsetTimeOriginal` (0x9011) in the EXIF;
2. else a GPS timestamp (`GPSDateStamp` 0x1D + `GPSTimeStamp` 0x07) within 24 h of it;
3. else the **file's mtime**, if within 24 h;
4. else it gives up and writes NULL.

A freshly converted file's mtime is *now*, so step 3 fails for any old photo, and one with neither
a timezone nor a GPS timestamp comes out undated. The originals never hit this because their mtime
*was* the capture time. Publishing (IS_PENDING=0) triggers that scan, which also overwrites
whatever `DATE_TAKEN` the app set at insert — and **`DATE_TAKEN` cannot be written directly**
afterwards (silently ignored). Proven by setting an unmodified failing copy's mtime to its capture
instant: NULL became the exact right date.

The fix writes `OffsetTimeOriginal` into the EXIF. Where the true instant is known — the original
JPG's MediaStore date, or a PhotoScan filename, which is epoch milliseconds — the offset is exact;
otherwise it is **Pacific/Auckland for that date** (the user's instruction).

Ruled out along the way, so nobody re-investigates: the HEIF `iloc` extent (an early check found
EXIF by raw byte-search, which bypasses `iloc`), IFD sort order, `ImageWidth` in IFD0, GPS
presence, EXIF size, SubSecTime and thumbnail IFD1. Each was only *correlated* with older camera
firmware, which wrote none of the timezone tags.

**The same trap applies to video:** MediaMuxer writes the time of writing into `mvhd`, so
`VideoConverter` copies the original's `mvhd`/`tkhd`/`mdhd` times across afterwards.

## Why converted photos showed sideways in Phone Link (fixed)

Android's decoder and Windows' HEIF codec apply the container rotation (`irot`) and ignore EXIF,
so thumbnails were right. But MediaStore's `orientation` column comes from **EXIF alone**, and
anything going by it — Phone Link's full-size view, for one — showed the photo unrotated. Tested
with copies of one photo: `irot` + EXIF 1 (old output) rotated in Phone Link; `irot` + matching
EXIF (camera style) correct everywhere; EXIF only with no `irot`, wrong in Windows.

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
sometimes takes several cycles. On a Wi-Fi transport, `adb disconnect <ip>:5555; adb connect
<ip>:5555` is quicker and does not disturb a USB transport in the same session.

`adb tcpip 5555` needs an existing connection, so re-enabling Wi-Fi ADB after `adb usb` requires
the cable once (a tiny control command — the faulty cable copes fine), or pairing via
Developer options → Wireless debugging.

**The phone must be unlocked to drive the app UI.** `am start` will happily launch MainActivity
*behind* the lock screen, and the screenshot then shows the lock screen while `dumpsys` reports
the activity focused. Wake with `input keyevent KEYCODE_WAKEUP`, but unlocking needs the user.

### Setting app options without the UI

The app is debuggable, so its prefs can be rewritten directly. base64 avoids all shell-quoting
pain. Force-stop first, or the running app overwrites the file on exit:

```powershell
adb shell "am force-stop com.example.convertjpgtoheic"
adb shell "run-as com.example.convertjpgtoheic sh -c 'echo <BASE64> | base64 -d > /data/data/com.example.convertjpgtoheic/shared_prefs/conversion.xml'"
```

Keys: `quality`, `deleteOriginals`, `onlyIfSmaller`, `skipSmall`, `shrinkOversized`,
`skipAlreadyConverted`, `motionPolicy`, `rangeStart`, `rangeEnd` (both epoch ms).
**Back the file up first and restore it afterwards** — these are the user's real settings.
`ConversionService` is not exported, so a run still has to be started by tapping Convert.

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
