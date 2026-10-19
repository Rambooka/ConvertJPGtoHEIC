# ConvertJPGtoHEIC — working notes

Android app that shrinks a phone's media library in place and offers to delete the originals:
JPG → HEIC (`androidx.heifwriter`), H.264 → HEVC video, and MP3 / M4A (AAC) / FLAC → Opus music.
Everything is published through MediaStore. Also repairs earlier output (photo dates and rotation,
music years) and cleans up leftovers.

## Build

```bash
JAVA_HOME="C:/Program Files/Android/Android Studio/jbr" ./gradlew :app:assembleDebug
JAVA_HOME="C:/Program Files/Android/Android Studio/jbr" ./gradlew :app:testDebugUnitTest
```

APK lands at `app/build/outputs/apk/debug/app-debug.apk`.

Real-file tests are skipped unless a folder of samples is named: `REPAIR_SAMPLES_DIR` (.heic),
`M4A_SAMPLES_DIR`, `FLAC_SAMPLES_DIR`, `OPUS_SAMPLES_DIR`. Pull samples from the phone into the
scratchpad, never into the repo.

---

## PICK UP HERE (2026-10-19, evening)

Branch **`fix/music-oversized-cover-art`**: 12 commits, pushed, **not merged to `main`** (the user
has not chosen PR or direct merge). The earlier photo/video branch is merged. The current build is
installed on the phone.

### Running when the user left

1. **FLAC → Opus** (12 Katy Perry "Smile" FLACs, 263 MB). Re-run with the new `FlacFrames` reader
   after the first copies were found truncated (see "Samsung's FLAC extractor"). The first two
   came out within 19–23 ms of the FLAC; the user was told to accept the delete prompt. **Check**:
   every `.opus` in `Music/Katy Perry/…FLAC [Hunter]/` against the FLAC's duration, or the log's
   per-song line (`… samples out … = X ms · file says Y ms`).
2. **Photos 2020 → 2026** next ("Convert pictures to HEIC"): 6,030 camera JPGs from 2023–24, 16 GB,
   never converted only because the range had been 1 Sep–26 Oct 2026. None sampled were motion
   photos. Afterwards "Clean up leftovers" for the 423 JPGs (1.4 GB) that already have a HEIC.

### Library state (survey of 19 Oct; 225 GB phone, 184 GB used)

| What | Size | Status |
|---|---|---|
| HEIC | 28,468 · 59.7 GB | done |
| JPG ≥ 1 MB | 6,355 · 16.9 GB | being converted (above) |
| JPG < 1 MB | 4,047 · 1.0 GB | skipped by `skipSmall`; little to gain |
| Video already HEVC | 355 · 11.6 GB | done |
| **Video H.264** | **154 · 3.25 GB** | **not yet run** — ~1.3 GB at the ~40% measured |
| Opus music | 1,333 · 5.8 GB | done; 1,221 given a YEAR by the repair, 108 have no date at all |
| `.m4p` (FairPlay) | 2 · 7 MB | cannot be decoded; "Delete copy-protected songs" offers them |
| GIF / PNG | 103 · 0.94 GB / 88 · 0.92 GB | not handled; would mean a format change |

Old video notes still apply: SEF-trailer videos (slow-mo, hyperlapse, highlight reels…) are
skipped by design; `.mov` (15) and `.avi` (2) are not handled. MediaMuxer writes `moov` at the
**start**, so any edit that grows it must shift every `stco`/`co64` offset.

### Open problems

a. **`addBitmap` has no timeout.** Any HEIC encoder stall wedges the run; the 64 MP ceiling avoids
   the one known trigger but is not a general guard (the call blocks inside EGL).

b. **Old panoramas "don't convert" — not a bug.** They encode, then lose to "Only keep if
   smaller": 2014–16 panoramas are ~0.29 bytes/px, and HeifWriter's bitrate caps near 0.3 at
   quality 80. Lowering quality for them is **the user's call**.

c. **Consent prompts cannot be passed unattended.** Photo runs stop every 500, videos 25, music
   100, repairs once. If the screen locks while MediaProvider's dialog is up it is dismissed and
   does not re-raise; Cancel and start again recovers. A "resume pending prompt" would be better.

d. **MediaProvider rarely rescans a same-length rewrite** — why both repairs end with an explicit
   rescan step. A rewrite that grows the file is mostly picked up on close.

e. **Music is slow, and much slower with the screen off.** `DefaultDispatch` (our Kotlin
   `Resampler` and PCM copying) is the bottleneck: ~3.5× real time on the big cores. Screen off,
   Samsung moves the app to cgroup `cpu:/background`, cpuset `/moderate` (cores 0–2): 7–17 min a
   song. Speeding up `Resampler` (both channels per tap pass, fewer allocations) is the lever.

f. **"Not smaller" songs are re-encoded on every music run** — nothing remembers them.

g. **Music converted before `f943f48` was verified only against what was decoded**, not the
   source's length. Truncation was only ever seen from Samsung's FLAC extractor; MP3/M4A spot
   checks while both existed were 34–83 ms short (codec padding). Their originals are deleted, so
   the rest cannot be re-checked.

### App settings as left

`deleteOriginals=true`, `shrinkOversized=true`, `motionPolicy=DROP_VIDEO`; the range was set by the
user to cover 2020–2026 for the photo run. Back up `conversion.xml` before touching it.

---

## Music conversion: what Android gets wrong

Each of these silently broke converted songs before it was found; each is now handled, with the
AOSP source location quoted in the code.

- **Ogg packets over 16 MB are rejected** (`OggExtractor`, "arbitrary limit", b/36592202), and the
  file then reports **0 tracks**. Cover art rides base64 (+33%) in the OpusTags packet, so a
  24 MB PNG cover (Aqua "Megalomania") broke every song. Pictures over 2 MB are now re-encoded as
  JPEG ≤ 2000 px; tag size is checked before encoding.
- **Ogg cover art must be picture type 3** (`MetaDataUtils::extractAlbumArt` skips anything else),
  while the MP3 reader shows any APIC. Taggers write 0, some 255. The first picture is made the
  front cover; **only that one is kept** — booklet scans (11 per track on Delta Goodrem "Delta")
  made copies no smaller than the MP3.
- **`YEAR`, not `DATE`, is the year** (`parseVorbisComment`: DATE → a date MediaStore never shows).
  Copies get both. "Repair converted music files" adds YEAR to old copies: retag into the cache,
  require identical audio page for page (`OggOpus.sameAudio`), overwrite in place (keeps the
  MediaStore row and playlists), compare byte for byte.
- **MediaStore mislabels `.m4a` as `audio/mpeg`** (a whole Little Mix album), so the query also
  matches by name, and the real codec is checked at decode. `.m4p` is excluded: FairPlay, the
  audio is encrypted (`audio/unknown`, duration 0).
- **Samsung's FLAC extractor (`SFLACExtractor`) ends streams early** — 0.4–47 s, varying with
  timing — and stamps every frame at time 0. Android picks it over the AOSP one. `FlacFrames`
  now splits frames itself (header CRC-8 + frame number + frame CRC-16, so in-audio sync patterns
  never split a frame) and feeds `c2.android.flac.decoder` with `fLaC`+STREAMINFO as csd-0.
  A FLAC must decode to exactly STREAMINFO's sample count; every format must decode to within
  1 s / 2% of its container's length.

MediaStore lists Opus as `audio/ogg`. `.opus` rather than `.ogg` because jaudiotagger-based
editors take `.ogg` to be Vorbis.

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
`dalvik.vm.heapsize=512m`, `heapgrowthlimit=256m`. Library is large and the phone runs close to
full memory — treat low-memory behaviour as the normal case, not the edge.

**adb** is not on PATH. Two development PCs:
- `C:\Users\rabram\AppData\Local\Android\Sdk\platform-tools\adb.exe` (ICT laptop). USB installs
  worked all day on 19 Oct. A new PC shows `unauthorized` until the phone's prompt is accepted.
- `C:\Users\RayAb\AppData\Local\Android\Sdk\platform-tools\adb.exe` (home). **Its USB cable was
  faulty in the write direction**: reads fine, every ~15 MB write died with `failed to read copy
  response: EOF` and knocked the device `offline` — it looked like memory pressure. Use Wi-Fi ADB
  there:

```powershell
adb tcpip 5555; adb connect <phone-ip>:5555; adb -s <ip>:5555 install -r <apk>
adb usb        # RESTORE THIS AFTERWARDS - otherwise adbd keeps listening on the network
```

When the device goes `offline`, `adb kill-server; adb start-server` usually recovers it. On a
Wi-Fi transport, `adb disconnect <ip>:5555; adb connect <ip>:5555` is quicker.

**Never install while a run is going** — `install -r` kills it, and a music/photo run loses its
pending delete batch (those originals then count as "already converted" and are never offered
again; "Clean up leftovers" is the way back). Check first:

```powershell
adb shell 'dumpsys activity services com.example.convertjpgtoheic | grep -c "ServiceRecord.*ConversionService"'   # 0 = idle
```

**Shell traps that cost time:**
- **Use PowerShell for adb, not Git Bash** — Bash rewrites POSIX paths (`/data/local/tmp/x` became
  `C:/Program Files/Git/data/local/tmp/x`).
- `content query --where` with SQL quotes: wrap the whole `adb shell` argument in single quotes and
  double the inner ones — `'content query … --where "relative_path=''Music/X/''"'`.
- A nested `-match` inside a PowerShell loop **overwrites `$matches`** — clone it first.
- `git commit -m "…\"…\"…"` breaks in PowerShell; commit from Bash with a heredoc.
- On the phone, `sh` arithmetic is 32-bit (sizes overflow — sum in `awk`), and `grep -a` on large
  binary data is unreliable — split it with `tr -c 'a-z0-9' '\n'` first.
- `python -I` ignores `PYTHONIOENCODING`; call `sys.stdout.reconfigure(encoding='utf-8')`.

**logcat's main buffer is 5 MB** and holds about an hour of a run. `MusicConverter` logs every
failure ("Not converting X: reason") and a per-song length line; read them soon after a run.

**The phone must be unlocked to drive the app UI.** `am start` launches MainActivity *behind* the
lock screen. Wake with `input keyevent KEYCODE_WAKEUP`, but unlocking needs the user. With the
screen on, `uiautomator dump` + pulling the XML reads the report text without a screenshot.

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
`ConversionService` is not exported, so a run still has to be started by tapping a button.

## Diagnosing a frozen run

`jdb` does not work on Android 15 (classic JDWP is gone), and `debuggerd -j` needs root, so Java
stack dumps are unavailable on a user build. What does work:

```bash
# blocked vs merely slow - the single most useful check
adb shell "cut -d' ' -f14 /proc/<pid>/stat; sleep 4; cut -d' ' -f14 /proc/<pid>/stat"

# thread census - catches leaks instantly
adb shell "cat /proc/<pid>/task/*/comm" | sort | uniq -c | sort -rn

# which thread is busy, and which cores the app may use
adb shell 'for t in /proc/<pid>/task/*; do echo "$(cut -d" " -f14,15 $t/stat) $(cat $t/comm)"; done'
adb shell 'cat /proc/<pid>/cpuset; grep cpu /proc/<pid>/cgroup'
```

Healthy photo run: ~45-55 threads, exactly one `heic-encode`, zero `HeifEncoderThread`.
Music runs: the Codec2 software codecs run in-process (`MediaCodec_loop` threads), not in
`media.swcodec`.

Library sources for reading `HeifWriter` internals (no network needed):

```
~/.gradle/caches/modules-2/files-2.1/androidx.heifwriter/heifwriter/1.1.0/*/heifwriter-1.1.0-sources.jar
```

AOSP sources come base64-encoded from
`https://android.googlesource.com/platform/frameworks/av/+/refs/heads/main/<path>?format=TEXT`
(e.g. `media/module/extractors/ogg/OggExtractor.cpp`, `media/module/metadatautils/MetaDataUtils.cpp`).

## Reading the progress counters

The tallies account for every photo, which makes them a reliable deduction tool:
`converted + under-1MB + already-done + not-smaller + name-clash + failed + 1 in progress`
should equal the current index. Use that to work out which bucket a given photo fell into when
there is no per-file log line.

Photos are processed **oldest first** (`DATE_TAKEN ASC, DATE_MODIFIED ASC`), and rows with a NULL
`DATE_TAKEN` sort first. Early in a run `converted` stays at 0 for a long time — that is expected,
not a fault: old photos are small and already compressed. Savings start at the newer end.

Music is processed **folder by folder, alphabetically**; songs with an `.opus` beside them are
skipped instantly, so the counter can jump straight to song 91.
