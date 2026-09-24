# Convert JPG to HEIC

An Android app that bulk re-encodes JPGs in your photo library to HEIC, over a date range you
pick, to reclaim storage. It is built around the assumption that it will be pointed at irreplaceable
photos: originals are only deleted after a successful encode and an explicit confirmation, and every
run reports exactly what it changed, skipped, and could not carry across.

**Samsung motion photos are kept whole.** A Samsung motion photo is a JPG with the live video
appended in a self-contained SEF trailer; the app re-encodes only the still to HEIC and re-attaches
that trailer verbatim, so the result is a smaller HEIC that Samsung Gallery still plays as a motion
photo. See [SefTrailer.kt](app/src/main/java/com/example/convertjpgtoheic/SefTrailer.kt).

## What it does

Pick a date range, and the app finds every JPG taken in it, re-encodes each one through the device's
HEVC encoder, publishes the result into the same folder, and offers to delete the originals.

Three run modes:

| Mode | What it does |
| --- | --- |
| **Dry run** | Encodes everything in memory to measure the real saving — motion photos included, their video re-attached — so the figure is what the real run would reclaim. Nothing is added to the gallery, nothing is deleted. |
| **Convert** | Encodes, publishes to the gallery, then asks before deleting the originals. |
| **Clean up leftovers** | Finds JPGs that already have a HEIC beside them — left behind by a cancelled run or a declined delete prompt — and offers to remove them. |

Options, remembered between runs:

- **Only keep the HEIC if it is smaller** — discards a result that came out no smaller, rather than trading quality for nothing.
- **Skip photos already converted**
- **Motion photos** — a three-way choice, defaulting to *Keep the video*:
  - **Keep the video (save as a HEIC motion photo)** — re-encode the still and re-attach the original video, so nothing is lost. Only Samsung SEF motion photos can be carried across this way; a motion photo whose video cannot (a Google/Pixel one, whose video lives in an XMP container `HeifWriter` has no channel for) is skipped rather than silently flattened.
  - **Skip them** — leave motion photos untouched.
  - **Convert to a still (discard the video)** — re-encode the still and drop the video, for when you only want the picture.
- **Delete originals after converting**
- **HEIC quality** — 50–100 in steps of 5, default 80.

## What is preserved, and what is not

This is the part that matters most, because originals get deleted. Each run reports counts for all
of it (see `RunReport` in [ConversionEngine.kt](app/src/main/java/com/example/convertjpgtoheic/ConversionEngine.kt)).

**Preserved:**

- **EXIF**, copied across wholesale — including GPS. The app requests `ACCESS_MEDIA_LOCATION` so MediaStore hands over the *unredacted* original.
- **Capture date.** A JPG with no EXIF of its own (a screenshot, a download, anything re-saved by an editor) gets a synthesised 128-byte EXIF block carrying just `DateTimeOriginal`, so a later media rescan re-derives the original date rather than the moment the file was written. See [MinimalExif.kt](app/src/main/java/com/example/convertjpgtoheic/MinimalExif.kt).
- **Orientation**, moved from the EXIF tag into the HEIF container rotation, and zeroed in the EXIF on the way out so a viewer that reads both does not rotate twice. See [ExifOrientation.kt](app/src/main/java/com/example/convertjpgtoheic/ExifOrientation.kt).
- **Location on disk** — the HEIC is written to the source photo's folder, on the same storage volume.
- **The video of a Samsung motion photo** — its SEF trailer (the video plus Samsung's own metadata) is copied onto the finished HEIC byte-for-byte. The trailer's offsets are all relative to its own directory, so a HEIC of a different size than the source JPG leaves them valid. The result plays as a motion photo in Samsung Gallery.

**Lost, and counted in the report:**

- **XMP** — `HeifWriter` has no channel for it.
- **ICC profiles** — wide-gamut colour is flattened to sRGB.
- **The video of a *non-Samsung* motion photo** (a Google/Pixel one, whose video lives in an XMP container rather than a SEF trailer) — such a photo is skipped under *Keep the video* rather than flattened. The video of *any* motion photo is discarded only if you choose *Convert to a still*.
- **Mirrored orientations** (EXIF values 2, 4, 5, 7) cannot be expressed as a container rotation, so the EXIF tag is left in place and may not be applied by every viewer.

## Requirements

- **Android 11 (API 30)** or newer; targets API 36.
- A device with a working **HEIC/HEVC encoder**. The app probes for one at startup and says so plainly rather than failing on all 300 photos ([EncoderSupport.kt](app/src/main/java/com/example/convertjpgtoheic/EncoderSupport.kt)).
- **Full photo library access.** "Selected Photos" is not enough — a date range can only be scanned across the whole library — and the UI tells you when you have granted partial access.

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
| [MainActivity.kt](app/src/main/java/com/example/convertjpgtoheic/MainActivity.kt) | The whole UI: date picker, options, progress, and the system delete-confirmation dialogs. |
| [ConversionEngine.kt](app/src/main/java/com/example/convertjpgtoheic/ConversionEngine.kt) | Owns the run. Process-scoped, not tied to a ViewModel, so the work survives the Activity going away. Exposes `UiState` as a `StateFlow`. |
| [ConversionService.kt](app/src/main/java/com/example/convertjpgtoheic/ConversionService.kt) | Foreground service. Does no work itself — it pins the process for the minutes a bulk run takes and mirrors engine state into a notification. |
| [PhotoRepository.kt](app/src/main/java/com/example/convertjpgtoheic/PhotoRepository.kt) | MediaStore queries, inserts, and output naming/placement. |
| [HeicEncoder.kt](app/src/main/java/com/example/convertjpgtoheic/HeicEncoder.kt) | Wraps `androidx.heifwriter`. Writes straight into the MediaStore descriptor where the device allows it, and falls back to staging-and-copy if not. |
| [JpegSegments.kt](app/src/main/java/com/example/convertjpgtoheic/JpegSegments.kt) | Walks the JPEG marker segments to pull out EXIF and detect XMP, ICC, and Google-style (XMP) motion photos. Stops at the first scan, so it costs a few KB per photo. |
| [SefTrailer.kt](app/src/main/java/com/example/convertjpgtoheic/SefTrailer.kt) | Parses the Samsung SEF trailer from a file's tail: whether it is a motion photo, and the byte offset where the video trailer begins so it can be re-attached to the HEIC. Pure Kotlin, unit-tested. |
| [DateRange.kt](app/src/main/java/com/example/convertjpgtoheic/DateRange.kt) | Converts the picker's UTC day picks into local-time bounds. Deliberately free of Android types so it can be unit-tested directly. |

A few design decisions worth knowing about:

- **Deletion is never automatic.** The engine parks converted originals and pauses, because deleting needs an Activity to show the system's confirmation dialog. Batches are confirmed as the run goes, so a long run on a nearly-full phone frees space as it proceeds instead of only at the end.
- **Cancellation is a flag checked between photos**, not `Job.cancel()` — an in-flight `HeifWriter` has to close or it leaks its encoder thread, and a cancelled coroutine could not report the partial tally.
- **Writes stay on the source volume.** Inserting into the synthetic `external` collection always lands on internal storage, which would quietly move an SD-card photo across volumes and then delete the original.
- **Photos outside `DCIM/` and `Pictures/`** (`Download/`, `Documents/`) are nested under `Pictures/` rather than refused, since the images collection rejects any other top-level folder and letting that throw would abort the run.

## Tests

Unit tests cover the logic where a bug would destroy photos or silently corrupt metadata — date
range boundaries across time zones, JPEG segment parsing against malformed input, SEF trailer
parsing (finding the video and the trailer's start from a file's tail), EXIF orientation rewriting,
the synthesised EXIF block's byte layout, output naming edge cases, and report accounting.

```bash
./gradlew test
```
