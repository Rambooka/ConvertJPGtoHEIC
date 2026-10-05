# Convert JPG to HEIC

An Android app that bulk re-encodes a phone's media into newer, smaller formats to reclaim storage:

- **Photos:** JPG → **HEIC**
- **Videos:** H.264 → **HEVC**
- **Music:** MP3 → **Opus**

It is built around the assumption that it will be pointed at irreplaceable files: originals are only
deleted after a successful encode, a check of the result, and an explicit confirmation, and every run
reports exactly what it changed, skipped, and could not carry across.

**Samsung motion photos are kept whole.** A Samsung motion photo is a JPG with the live video
appended in a self-contained SEF trailer; the app re-encodes only the still to HEIC and re-attaches
that trailer verbatim, so the result is a smaller HEIC that Samsung Gallery still plays as a motion
photo. See [SefTrailer.kt](app/src/main/java/com/example/convertjpgtoheic/SefTrailer.kt).

## Photos

Pick a date range, and the app finds every JPG taken in it, re-encodes each one through the device's
HEVC encoder, publishes the result into the same folder, and offers to delete the originals.

| Mode | What it does |
| --- | --- |
| **Dry run** | Encodes everything in memory to measure the real saving — motion photos included, their video re-attached — so the figure is what the real run would reclaim. Nothing is added to the gallery, nothing is deleted. |
| **Convert** | Encodes, publishes to the gallery, then asks before deleting the originals. |
| **Clean up leftovers** | Finds JPGs that already have a HEIC beside them — left behind by a cancelled run or a declined delete prompt — and offers to remove them. |
| **Repair converted photos** | Fixes HEICs already in the library that show sideways in some viewers or have lost their capture date. See [Repairing converted photos](#repairing-converted-photos). |

Options, remembered between runs:

- **Only keep the HEIC if it is smaller** — discards a result that came out no smaller, rather than trading quality for nothing.
- **Skip JPGs under 1 MB** (on by default) — passes over photos too small to be worth re-encoding, before they are even decoded. The report counts them and the storage they still occupy.
- **Shrink oversized photos to fit the encoder** (on by default) — a very large panorama can exceed the GPU's maximum texture size (`HeifWriter` uploads the image as one OpenGL texture), which no amount of memory fixes. When this is on, such an image is downsampled just enough to fit and is converted rather than failed; the report flags it as saved at reduced resolution. It only ever affects images that could not be converted at all otherwise.
- **Skip photos already converted**
- **Motion photos** — a three-way choice, defaulting to *Keep the video*:
  - **Keep the video (save as a HEIC motion photo)** — re-encode the still and re-attach the original video, so nothing is lost. Only Samsung SEF motion photos can be carried across this way; a motion photo whose video cannot (a Google/Pixel one, whose video lives in an XMP container `HeifWriter` has no channel for) is skipped rather than silently flattened.
  - **Skip them** — leave motion photos untouched.
  - **Convert to a still (discard the video)** — re-encode the still and drop the video, for when you only want the picture.
- **Delete originals after converting** — also applies to video and music runs.
- **HEIC quality** — 50–100 in steps of 5, default 80.

## Videos

**Convert videos to HEVC** re-encodes the H.264 MP4s in the date range as HEVC on the phone's
hardware encoder, at 55% of the original's bitrate — usually about 40% smaller and visually
identical. The sound track is copied through untouched.

- **Kept:** the capture time (MediaMuxer stamps the time of writing, so the original's
  `mvhd`/`tkhd`/`mdhd` times are copied back afterwards), the location (`©xyz`), the rotation and the
  frame rate. Each result is read back and compared with the original before it is kept. See
  [VideoConverter.kt](app/src/main/java/com/example/convertjpgtoheic/VideoConverter.kt) and
  [Mp4Metadata.kt](app/src/main/java/com/example/convertjpgtoheic/Mp4Metadata.kt).
- **Left alone:** slow motion, hyperlapse and other Samsung camera modes (anything carrying a SEF
  trailer), videos already in HEVC, and formats not handled yet (`.mov`, `.avi`, anything above 4K).
- **Naming:** while both exist the copy is called `name_HEVC.mp4`; once you confirm deleting the
  original, the copy takes the original's name in the original's folder.
- Originals are offered for deletion every 25 videos. Videos take far longer than photos and warm
  the phone up, so a long run is best done while charging.

## Music

**Convert music to Opus** re-encodes every MP3 in the music library (the whole library — songs have
no capture date, so the date range does not apply) as Opus, and saves it beside the MP3 as
`name.opus`.

- **Kept only if smaller**, whatever the photo option says: the point is the space.
- **Tags and cover art are copied across** — title, artist, album, album artist, track and disc
  number, date, genre, composer, comments, lyrics and every embedded picture. Android's Ogg writer
  leaves the tag block empty, so the app reads the MP3's ID3 tags itself and writes them into the
  file as Vorbis comments.
- **Checked before it is kept:** each file is read back by Android's own parsers and must be Opus,
  run as long as the MP3, and show the same title, artist, album and cover.
- **Bitrate** is 75% of the MP3's, between 64 and 160 kbps (a 128 kbps MP3 becomes 96 kbps; a
  320 kbps one 160 kbps). Re-encoding an already-compressed file loses a little quality; at these
  rates it is rarely audible, but the MP3s cannot be got back once deleted.
- MP3s are offered for deletion every 100 songs. Playlists that list the MP3s lose those songs.

Why **Opus, named `.opus`**: Android has an Opus encoder but no Vorbis one, and Opus needs fewer bits
for the same sound. The file is an ordinary Ogg container, but it is named `.opus` because tag
editors built on jaudiotagger (Pulsar+, for one) take any `.ogg` to be Vorbis and refuse to open an
Opus one ("Invalid Identification header for this Ogg File"), while they read and write `.opus`
correctly. Copies made by early builds as `.ogg` are renamed at the start of the next music run.

Most MP3s are 44.1 kHz and the Opus encoder only takes 48 kHz, so the audio is resampled with a
windowed-sinc filter, flat to about 20 kHz and better than −80 dB outside it, rather than the linear
interpolation that would fold audible images back into the music. See
[Resampler.kt](app/src/main/java/com/example/convertjpgtoheic/Resampler.kt).

## Repairing converted photos

**Repair converted photos** checks every HEIC on the phone (not just the range) and fixes two things,
by rewriting the photo's EXIF in place — the picture itself is never re-encoded:

- **Rotation.** Android and Windows turn a HEIC by its container rotation, but MediaStore's
  `orientation` column — which Phone Link's full-size view, among others, goes by — is read from
  EXIF alone. The repair sets the EXIF orientation to match the container, as Samsung's camera does.
- **Capture date.** Photos that lost their date sit at the top of the gallery on the day they were
  converted. The repair recovers a date from the EXIF, the filename (`20191221_081314`,
  `IMG-20150606-WA0009`, PhotoScan's epoch milliseconds and others), another copy of the same photo,
  or the original JPG, and adds the timezone MediaStore needs to use it (see below). Photos with no
  date anywhere are listed at the end of the report with a thumbnail and a **Set date** button.

Android asks once per 1,000 photos to allow changes to files the app no longer owns. The run ends by
waiting for Android to re-read each repaired photo, so the gallery shows the fix straight away.

## What is preserved, and what is not

This is the part that matters most, because originals get deleted. Each run reports counts for all
of it (see `RunReport` in [ConversionEngine.kt](app/src/main/java/com/example/convertjpgtoheic/ConversionEngine.kt)).

**Preserved:**

- **EXIF**, copied across wholesale — including GPS. The app requests `ACCESS_MEDIA_LOCATION` so MediaStore hands over the *unredacted* original.
- **Capture date.** `DateTimeOriginal` carries no timezone, and MediaStore will only date a photo when it can work one out — which for a freshly written file it often cannot, leaving the photo undated. So the app also writes `OffsetTimeOriginal`: exact where the original's instant is known, otherwise the Pacific/Auckland offset for that date. A JPG with no EXIF of its own (a screenshot, a download, anything re-saved by an editor) gets a synthesised EXIF block carrying the date. See [MinimalExif.kt](app/src/main/java/com/example/convertjpgtoheic/MinimalExif.kt).
- **Orientation**, written as the HEIF container rotation *and* kept in the EXIF matching it, so viewers that go by either agree. See [ExifOrientation.kt](app/src/main/java/com/example/convertjpgtoheic/ExifOrientation.kt).
- **Location on disk** — the HEIC is written to the source photo's folder, on the same storage volume.
- **The video of a Samsung motion photo** — its SEF trailer (the video plus Samsung's own metadata) is copied onto the finished HEIC byte-for-byte. The trailer's offsets are all relative to its own directory, so a HEIC of a different size than the source JPG leaves them valid. The result plays as a motion photo in Samsung Gallery.

**Lost, and counted in the report:**

- **XMP** — `HeifWriter` has no channel for it.
- **ICC profiles** — wide-gamut colour is flattened to sRGB.
- **The video of a *non-Samsung* motion photo** (a Google/Pixel one, whose video lives in an XMP container rather than a SEF trailer) — such a photo is skipped under *Keep the video* rather than flattened. The video of *any* motion photo is discarded only if you choose *Convert to a still*.
- **Mirrored orientations** (EXIF values 2, 4, 5, 7) cannot be expressed as a container rotation, so the EXIF tag is left in place and may not be applied by every viewer.

## The report

Both the live progress and the summary at the end break the saving down, rather than giving a single
number:

- **Stills and motion photos are counted and measured separately**, so you can see how much came from
  each. The figures update as the run goes, not just at the end.
- **Skipped files are broken out by reason** — already converted, not smaller, under 1 MB (with the
  bytes left untouched), motion photos passed over, special video modes left alone.
- **Failures are listed per file** — name, size, and why — each with a **View** button to check the
  original and a **Delete** button to remove it (through the system's own confirmation, since a failed
  original is the only copy). A whole-run failure with no single file behind it (no encoder, storage
  full) is stated once, without buttons.
- **Long runs show an estimated time left** and when they should finish.

When a run pauses to have a batch of originals deleted, the phone sounds **five short beeps**, so a
long run left in a pocket does not sit unnoticed waiting on the confirmation. If the screen locks
while Android's confirmation dialog is up, the dialog is dismissed; cancel and start the run again.

## Requirements

- **Android 11 (API 30)** or newer; targets API 36.
- A device with a working **HEIC/HEVC encoder** for photos and videos, and an **Opus encoder** for music (every device since Android 10). The app probes for them at startup and says so plainly rather than failing on every file ([EncoderSupport.kt](app/src/main/java/com/example/convertjpgtoheic/EncoderSupport.kt)).
- **Full photo library access.** "Selected Photos" is not enough — a date range can only be scanned across the whole library — and the UI tells you when you have granted partial access.
- Access to **videos** and to **music and audio** is asked for only when you first tap those buttons.

## Building

```bash
./gradlew assembleDebug        # build the APK
./gradlew test                 # unit tests (JVM, no device)
./gradlew connectedAndroidTest # instrumented tests (needs a device/emulator)
./gradlew installDebug         # build and install
```

Gradle 9.1, AGP 9.0.1, Java 11 source/target. `local.properties` is git-ignored — Android Studio
writes your own SDK path there on first open.

## How it is put together

Plain Views with view binding, coroutines, no DI framework, no Compose.

| File | Role |
| --- | --- |
| [MainActivity.kt](app/src/main/java/com/example/convertjpgtoheic/MainActivity.kt) | The whole UI: date picker, options, progress, the summary report with its per-failure view/delete list, and the system delete-confirmation dialogs. |
| [ConversionEngine.kt](app/src/main/java/com/example/convertjpgtoheic/ConversionEngine.kt) | Owns every run — photos, videos, music, clean-up and repair. Process-scoped, not tied to a ViewModel, so the work survives the Activity going away. Exposes `UiState` as a `StateFlow`. |
| [ConversionService.kt](app/src/main/java/com/example/convertjpgtoheic/ConversionService.kt) | Foreground service. Does no work itself — it pins the process for the minutes a bulk run takes and mirrors engine state into a notification. |
| [PhotoRepository.kt](app/src/main/java/com/example/convertjpgtoheic/PhotoRepository.kt) | MediaStore queries, inserts, and output naming/placement for photos, videos and music. |
| [HeicEncoder.kt](app/src/main/java/com/example/convertjpgtoheic/HeicEncoder.kt) | Wraps `androidx.heifwriter`. Writes straight into the MediaStore descriptor where the device allows it, and falls back to staging-and-copy if not. |
| [JpegSegments.kt](app/src/main/java/com/example/convertjpgtoheic/JpegSegments.kt) | Walks the JPEG marker segments to pull out EXIF and detect XMP, ICC, and Google-style (XMP) motion photos. Stops at the first scan, so it costs a few KB per photo. |
| [SefTrailer.kt](app/src/main/java/com/example/convertjpgtoheic/SefTrailer.kt) | Parses the Samsung SEF trailer from a file's tail: whether it is a motion photo, and the byte offset where the video trailer begins so it can be re-attached to the HEIC. Pure Kotlin, unit-tested. |
| [HeicRepair.kt](app/src/main/java/com/example/convertjpgtoheic/HeicRepair.kt), [HeifExif.kt](app/src/main/java/com/example/convertjpgtoheic/HeifExif.kt), [ExifEditor.kt](app/src/main/java/com/example/convertjpgtoheic/ExifEditor.kt) | The repair: find a HEIC's EXIF through its `iloc`, edit orientation and dates in place, and add an EXIF item to a HEIC that has none. |
| [VideoConverter.kt](app/src/main/java/com/example/convertjpgtoheic/VideoConverter.kt), [Mp4Metadata.kt](app/src/main/java/com/example/convertjpgtoheic/Mp4Metadata.kt) | HEVC re-encoding through Media3 Transformer, and the MP4 box reading and patching that carries the capture time across and checks the result. |
| [MusicConverter.kt](app/src/main/java/com/example/convertjpgtoheic/MusicConverter.kt) | MP3 → Opus: MediaExtractor, the MP3 decoder, the resampler, the Opus encoder and MediaMuxer's Ogg writer, then the tags and the read-back check. |
| [Id3Tags.kt](app/src/main/java/com/example/convertjpgtoheic/Id3Tags.kt), [OggOpus.kt](app/src/main/java/com/example/convertjpgtoheic/OggOpus.kt), [Resampler.kt](app/src/main/java/com/example/convertjpgtoheic/Resampler.kt) | ID3v1/v2.2/2.3/2.4 tags to Vorbis comments; rewriting an Ogg Opus file's tag packet (re-paging, renumbering and re-checksumming the audio pages untouched); 44.1 → 48 kHz resampling. Pure Kotlin, unit-tested. |
| [DeletionAlert.kt](app/src/main/java/com/example/convertjpgtoheic/DeletionAlert.kt) | Sounds the five beeps when a run pauses for deletion confirmation. Fire-and-forget on its own thread, and silent on a device that cannot open a tone generator. |
| [DateRange.kt](app/src/main/java/com/example/convertjpgtoheic/DateRange.kt), [Eta.kt](app/src/main/java/com/example/convertjpgtoheic/Eta.kt) | The picker's UTC day picks as local-time bounds, and the time-left estimate. Free of Android types so they can be unit-tested directly. |

A few design decisions worth knowing about:

- **Deletion is never automatic.** The engine parks converted originals and pauses, because deleting needs an Activity to show the system's confirmation dialog. Batches are confirmed as the run goes, so a long run on a nearly-full phone frees space as it proceeds instead of only at the end.
- **Cancellation is a flag checked between files**, not `Job.cancel()` — an in-flight `HeifWriter` has to close or it leaks its encoder thread, and a cancelled coroutine could not report the partial tally.
- **Writes stay on the source volume.** Inserting into the synthetic `external` collection always lands on internal storage, which would quietly move an SD-card photo across volumes and then delete the original.
- **Files outside the folders their collection accepts** are nested rather than refused: photos outside `DCIM/` and `Pictures/` go under `Pictures/`, videos under `Movies/`, songs under `Music/`. MediaStore rejects any other top-level folder, and letting that throw would abort the run.

## Tests

Unit tests cover the logic where a bug would destroy files or silently corrupt metadata — date
range boundaries across time zones, JPEG segment parsing against malformed input, SEF trailer
parsing, EXIF orientation and date editing, HEIF EXIF location and repair, MP4 box reading and
patching, ID3 tag parsing (every version, unsynchronisation, iTunes' malformed sizes, corrupt
input), Ogg page rewriting and checksums, resampler accuracy and image rejection, output naming edge
cases, and report accounting.

```bash
./gradlew test
```
