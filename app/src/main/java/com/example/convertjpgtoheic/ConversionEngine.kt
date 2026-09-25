package com.example.convertjpgtoheic

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.MediaStore
import android.util.Log
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.File

enum class RunMode {
    /** Encode everything and measure it, then throw the results away. Touches nothing. */
    DRY_RUN,

    /** Encode, publish to the gallery, then offer to delete the originals. */
    CONVERT,

    /** Delete JPGs that already have a HEIC beside them, left over from an interrupted run. */
    CLEAN_UP,

    /**
     * Fix HEICs that show sideways in viewers going by MediaStore's orientation, or that lost their
     * capture date. Edits EXIF in place; the image is never re-encoded. See [HeicRepair].
     */
    REPAIR,

    /** Re-encode H.264 videos as HEVC, then offer to delete the originals. See [VideoConverter]. */
    VIDEO,
}

/** What to do when a photo turns out to be a motion photo. */
enum class MotionPhotoPolicy {
    /** Leave it untouched. Nothing is re-encoded and the original is not deleted. */
    SKIP,

    /**
     * Re-encode the still to HEIC and re-attach the original video, so the result stays a playable
     * motion photo. Only Samsung SEF motion photos can be carried across this way; a motion photo
     * whose video cannot be re-attached is skipped rather than silently flattened to a still.
     */
    KEEP_VIDEO,

    /** Re-encode the still to HEIC and discard the video — a plain still, smaller than either. */
    DROP_VIDEO,
}

/** How a single photo's motion video was handled, so the report can count it honestly. */
enum class MotionOutcome { NONE, PRESERVED, DROPPED }

data class RunOptions(
    val startMs: Long,
    val endMs: Long,
    val quality: Int,
    val deleteOriginals: Boolean,
    /** Discard a HEIC that came out no smaller than its JPG, rather than trade quality for nothing. */
    val onlyIfSmaller: Boolean,
    /** Pass over JPGs under [MIN_CONVERT_SIZE_BYTES] — too little to gain to be worth re-encoding. */
    val skipSmall: Boolean,
    /** Shrink an image too large for the encoder (a huge panorama) to fit, rather than failing it. */
    val shrinkOversized: Boolean,
    val skipAlreadyConverted: Boolean,
    /** What to do with motion photos — skip them, keep the video, or drop it. */
    val motionPolicy: MotionPhotoPolicy,
)

data class RunProgress(
    val done: Int,
    val total: Int,
    val currentName: String,
    /** Converted originals waiting to be deleted. */
    val pendingDeletions: Int = 0,
    /** How many they are waiting for, so the UI need not know the engine's batch size. */
    val promptAt: Int = 0,
    /** Running tallies, so a long run shows what it is doing and quietly passing over. */
    val converted: Int = 0,
    /** Bytes saved so far — original size minus HEIC output over everything converted. */
    val savedBytes: Long = 0,
    /** Of [converted], how many were motion photos, and the bytes those saved — so the live
     *  status can break the saving down the same way the final report does. */
    val convertedMotion: Int = 0,
    val savedMotionBytes: Long = 0,
    val skippedMotion: Int = 0,
    /** JPGs passed over for being under the size floor, and the bytes they still occupy. */
    val skippedTooSmall: Int = 0,
    val skippedTooSmallBytes: Long = 0,
    /** The three "other" skips, broken out so the status says *why* something was passed over. */
    val skippedExisting: Int = 0,
    val skippedNotSmaller: Int = 0,
    val skippedNameClash: Int = 0,
    val failures: Int = 0,
    /** Repair runs only: photos fixed so far, by what was wrong with them. */
    val repairedDates: Int = 0,
    val repairedRotations: Int = 0,
    /** Repair runs only: which step is running, since each counts different things. */
    val repairPhase: RepairPhase? = null,
    /** Repair runs only: found to need a fix but waiting for the user to allow it. */
    val awaitingPermission: Int = 0,
    /** Estimated time left in the current step, or null while it is too early to say. */
    val remainingMs: Long? = null,
    /** Video runs only: how far through the current video the encoder is, 0–1. */
    val itemFraction: Float = 0f,
)

/** The steps of a repair, in order. */
enum class RepairPhase {
    /** Reading every HEIC; fixing the ones the app still owns straight away. */
    CHECKING,

    /** Fixing the photos the user has just allowed. */
    WRITING,

    /** Waiting for Android to re-read the fixed files, so the gallery shows the change. */
    REFRESHING,
}

/**
 * A photo that could not be converted, carrying what the report needs to show it and act on it.
 *
 * [uri] is [Uri.EMPTY] for a whole-run failure that has no single file behind it (no encoder,
 * storage full), in which case only [reason] is meaningful.
 */
data class FailedPhoto(
    val uri: Uri,
    val name: String,
    val sizeBytes: Long,
    val reason: String,
) {
    /** A real file stands behind this failure, so it can be viewed and deleted. */
    val hasFile: Boolean get() = uri != Uri.EMPTY
}

data class RunReport(
    val mode: RunMode,
    val cancelled: Boolean = false,
    val converted: Int = 0,
    val skippedExisting: Int = 0,
    val skippedNotSmaller: Int = 0,
    val skippedMotionPhoto: Int = 0,
    val exifPreserved: Int = 0,
    /** Had no EXIF of its own, so a capture-date-only block was written to anchor the date. */
    val exifSynthesised: Int = 0,
    /** Converted, but XMP was dropped — HeifWriter has no way to carry it. */
    val droppedXmp: Int = 0,
    /** Converted, but an ICC profile was dropped, so wide-gamut colour is flattened to sRGB. */
    val flattenedColour: Int = 0,
    /** Orientation involves mirroring, which a container rotation cannot express, so the EXIF
     *  tag was left in place and may not be applied by every viewer. */
    val mirroredOrientation: Int = 0,
    /** Converted despite being a motion photo, so the embedded video was discarded. */
    val convertedMotionPhotos: Int = 0,
    /** Converted as a HEIC motion photo, with the original video re-attached and nothing lost. */
    val motionPhotosPreserved: Int = 0,
    /** Written to a different folder, because the source folder is not one the images
     *  collection accepts. */
    val relocated: Int = 0,
    /** Published, but MediaStore would not accept the original capture date on the row. */
    val dateWriteFailed: Int = 0,
    /** Abandoned because a file of that name was already there — nothing was changed, e.g. `IMG_1234 (1).heic`. */
    val renamedOutput: Int = 0,
    val originalBytes: Long = 0,
    val heicBytes: Long = 0,
    /**
     * The motion-photo subset of [originalBytes]/[heicBytes]. Stills are derived as the remainder,
     * so the two always add back up to the totals with no chance of the buckets drifting apart.
     */
    val motionOriginalBytes: Long = 0,
    val motionHeicBytes: Long = 0,
    /** JPGs skipped for being under the size floor, and the bytes they still take up on disk. */
    val skippedTooSmall: Int = 0,
    val skippedTooSmallBytes: Long = 0,
    /** Converted, but shrunk to fit the encoder — so the HEIC is lower resolution than the source. */
    val downscaledToFit: Int = 0,
    /** Sample of failure messages, capped — see [failureCount] for the real total. */
    val failures: List<FailedPhoto> = emptyList(),
    val failureCount: Int = 0,
    val deletedOriginals: Int = 0,
    val deletionOutcome: DeletionOutcome = DeletionOutcome.NOT_REQUESTED,
    /** Repair runs only: HEICs examined. */
    val repairChecked: Int = 0,
    /** Given back a capture date the gallery now reads. */
    val repairedDates: Int = 0,
    /** Given an EXIF orientation agreeing with the container, so every viewer turns them alike. */
    val repairedRotations: Int = 0,
    /** Needed a fix but the user did not allow the app to modify them. */
    val repairDeclined: Int = 0,
    /** Undated, with no capture time anywhere to recover — not in EXIF, not in the name. */
    val repairNoDateSource: Int = 0,
    /** Rewritten, but MediaStore still reports the old values after a rescan. */
    val repairUnverified: Int = 0,
    /** Video runs only: left alone because re-encoding would lose a camera mode (slow motion...). */
    val videoSkippedSpecial: Int = 0,
    /** Video runs only: already HEVC, so there is nothing to gain. */
    val videoSkippedCodec: Int = 0,
    /** Video runs only: a container or size this does not handle yet (.mov, 8K...). */
    val videoSkippedFormat: Int = 0,
) {
    /**
     * Records a whole-run failure with no single file behind it — no encoder, storage full.
     *
     * Kept in the same list as the per-photo ones (with an empty [FailedPhoto.uri]) so the report
     * has one place to look; the UI shows these as a plain reason, with no view/delete buttons.
     */
    fun withFailure(reason: String): RunReport = copy(
        failures = if (failures.size < MAX_RETAINED_FAILURES) {
            failures + FailedPhoto(Uri.EMPTY, "", 0, reason)
        } else {
            failures
        },
        failureCount = failureCount + 1,
    )

    /**
     * Records a single photo that could not be converted, keeping the handle, name and size the
     * report needs to show it and act on it.
     *
     * Appending to an immutable list once per photo is quadratic, and a run where everything fails
     * would build thousands of entries the UI never shows. Only a sample is kept; [failureCount]
     * stays exact.
     */
    fun withFailure(photo: SourcePhoto, reason: String): RunReport = copy(
        failures = if (failures.size < MAX_RETAINED_FAILURES) {
            failures + FailedPhoto(photo.uri, photo.displayName, photo.sizeBytes, reason)
        } else {
            failures
        },
        failureCount = failureCount + 1,
    )

    /** As [withFailure] for a photo, for files that are not conversion sources (a repair). */
    fun withFailure(uri: Uri, name: String, sizeBytes: Long, reason: String): RunReport = copy(
        failures = if (failures.size < MAX_RETAINED_FAILURES) {
            failures + FailedPhoto(uri, name, sizeBytes, reason)
        } else {
            failures
        },
        failureCount = failureCount + 1,
    )

    val savedBytes: Long get() = originalBytes - heicBytes
    val savedPercent: Int
        get() = if (originalBytes <= 0) 0 else ((savedBytes * 100) / originalBytes).toInt()

    /** How many of the converted photos were motion photos (kept whole or flattened). */
    val convertedMotion: Int get() = convertedMotionPhotos + motionPhotosPreserved

    /** The still-photo half of the breakdown — everything that was not a motion photo. */
    val convertedStills: Int get() = converted - convertedMotion
    val stillOriginalBytes: Long get() = originalBytes - motionOriginalBytes
    val stillHeicBytes: Long get() = heicBytes - motionHeicBytes
    val stillSavedBytes: Long get() = stillOriginalBytes - stillHeicBytes
    val motionSavedBytes: Long get() = motionOriginalBytes - motionHeicBytes
}

private const val MAX_RETAINED_FAILURES = 50

enum class DeletionOutcome { NOT_REQUESTED, PENDING, COMPLETED, DECLINED }

sealed interface UiState {
    data object Idle : UiState
    data object Scanning : UiState
    data class Ready(val photos: List<SourcePhoto>, val totalBytes: Long) : UiState
    data class Working(val mode: RunMode, val progress: RunProgress) : UiState

    /**
     * Paused part-way, waiting for the user to confirm deleting the originals converted so far.
     * A distinct state because the service must stay in the foreground through it — the run is
     * not finished, it is blocked on a dialog.
     */
    data class AwaitingDeletion(
        val mode: RunMode,
        val done: Int,
        val total: Int,
        val pending: Int,
        /** Triggered by storage running low rather than by the usual batch size. */
        val lowOnSpace: Boolean = false,
    ) : UiState

    data class Finished(val report: RunReport) : UiState
}

/** What the system dialog is asking the user to allow. */
enum class ConsentKind {
    /** Delete converted originals — the usual case. */
    DELETE,

    /** Let the app rewrite files it no longer owns (a repair of HEICs made before a reinstall). */
    MODIFY,
}

/**
 * A batch waiting on a system confirmation dialog. Named for its first use; a repair uses the same
 * batching, re-raise-on-return and cancel handling to ask for write access instead.
 */
data class DeletionRequest(val uris: List<Uri>, val kind: ConsentKind = ConsentKind.DELETE)

/**
 * Owns the conversion run.
 *
 * Process-scoped rather than tied to a ViewModel: a few hundred photos takes minutes, and the work
 * has to keep going while the Activity is gone. [ConversionService] keeps the process alive and
 * mirrors [state] into a notification; the Activity observes the same instance and can come and go.
 *
 * Deletion is deliberately *not* done here — it needs an Activity to show the system's confirmation
 * dialog — so a finished run parks the originals in [deletionRequest] until someone handles them.
 */
class ConversionEngine private constructor(context: Context) {

    private val app = context.applicationContext
    private val repository = PhotoRepository(app)
    private val encoder = HeicEncoder(app, repository)
    private val repairer = HeicRepair(app.contentResolver)
    private val videoConverter = VideoConverter(app)
    private val videoStaging = File(app.cacheDir, "video-staging")
    /**
     * The handler is not optional. A revoked photo permission makes `ContentResolver.query` throw
     * SecurityException, and an uncaught throw from `launch` reaches the thread's default handler
     * and takes the process down. Since a saved range is re-scanned on startup, that crash is
     * reachable simply by revoking access and reopening the app.
     */
    private val errors = CoroutineExceptionHandler { _, error -> reportFailure(error) }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO + errors)

    private val _state = MutableStateFlow<UiState>(UiState.Idle)
    val state: StateFlow<UiState> = _state.asStateFlow()

    private val _deletionRequest = MutableStateFlow<DeletionRequest?>(null)
    val deletionRequest: StateFlow<DeletionRequest?> = _deletionRequest.asStateFlow()

    /**
     * Cancellation is a flag rather than [Job.cancel] so the loop stops between photos: an
     * in-flight HeifWriter has to finish and close or it leaks its encoder thread, and a cancelled
     * coroutine could not report the partial tally afterwards.
     */
    @Volatile
    private var cancelRequested = false

    /**
     * What the current run is. Needed because a clean-up never reaches [UiState.Working], so a
     * failure part-way through it could not otherwise tell which kind of run had failed.
     */
    @Volatile
    private var currentMode = RunMode.CONVERT

    /**
     * Whether encoding straight into the MediaStore file works on this device.
     *
     * Writing in place halves the bytes that hit the flash, but it leans on MediaMuxer accepting
     * a content-provider descriptor. If that turns out not to work here, the first failure drops
     * the whole run back to the staging-and-copy path rather than failing every photo. Once a
     * direct write has succeeded, later failures are treated as genuine problems with that photo.
     */
    @Volatile
    private var directWriteWorks = true

    @Volatile
    private var directWriteProven = false

    /**
     * Deletion bookkeeping is touched from two threads — the run finishes on an IO thread, the
     * confirmation result arrives on the main thread — so every read and write of these goes
     * through [deletionLock].
     */
    private val deletionLock = Any()
    private var pendingDeletion: PendingDeletion? = null

    /**
     * One confirmation sequence, awaited by the run that raised it.
     *
     * The run suspends on [outcome] until every batch has been answered, which is what lets a
     * conversion stop half way, have its originals removed, and carry on.
     */
    private class PendingDeletion(val batches: List<List<Uri>>, val kind: ConsentKind) {
        var index = 0
        var deleted = 0

        /** Every URI the user has allowed so far — what a repair may now go on to modify. */
        val accepted = ArrayList<Uri>()

        /** A dialog is on screen; do not raise a second one for the same batch. */
        var inFlight = false
        val outcome = CompletableDeferred<Boolean>()
    }

    private data class DeletionAnswer(val granted: Boolean, val deleted: Int, val accepted: List<Uri> = emptyList())

    /**
     * Whether MediaStore will hand us unredacted GPS EXIF.
     *
     * Read from the system on demand rather than pushed in by the Activity: the run outlives the
     * Activity, and a stale `false` here would silently strip location from every photo.
     */
    private val hasMediaLocationPermission: Boolean
        get() = ContextCompat.checkSelfPermission(app, Manifest.permission.ACCESS_MEDIA_LOCATION) ==
            PackageManager.PERMISSION_GRANTED

    val isBusy: Boolean
        get() = _state.value.let {
            it is UiState.Scanning || it is UiState.Working || it is UiState.AwaitingDeletion
        }

    /** Originals are converted but not yet confirmed for deletion. Starting another run here
     *  would overwrite the batch list and strand them. */
    val hasPendingDeletion: Boolean
        get() = synchronized(deletionLock) { pendingDeletion != null }

    fun scan(range: DateRange) {
        if (isBusy) return
        cancelRequested = false
        // Set synchronously, before the coroutine is dispatched: ConversionService attaches its
        // observer straight after calling this, and a StateFlow replays whatever is current. Left
        // until inside the launch, that replay would be the *previous* run's Finished.
        _state.value = UiState.Scanning
        scope.launch {
            val photos = repository.queryJpgs(range.startMs, range.endMs)
            _state.value = UiState.Ready(photos, photos.sumOf { it.sizeBytes })
        }
    }

    /** @return false if nothing was started, so the caller can stand its service back down. */
    fun start(mode: RunMode, options: RunOptions): Boolean {
        if (isBusy || hasPendingDeletion) return false
        cancelRequested = false
        currentMode = mode
        directWriteWorks = true
        directWriteProven = false
        // See the note in scan(): the observer must never replay a stale Finished.
        _state.value = UiState.Scanning
        scope.launch {
            when (mode) {
                RunMode.CLEAN_UP -> cleanUp(options)
                RunMode.REPAIR -> repair()
                RunMode.VIDEO -> convertVideos(options)
                else -> convert(mode, options)
            }
        }
        return true
    }

    fun cancel() {
        cancelRequested = true
        // A run suspended on a delete prompt would never see the flag otherwise.
        synchronized(deletionLock) { pendingDeletion?.outcome?.complete(false) }
        _deletionRequest.value = null
    }

    /** Free bytes on the volume conversions write to, or -1 when it cannot be read. */
    fun freeStorageBytes(): Long = encoder.freeStagingBytes()

    /** Surfaces a crash as a finished-with-failure state, so the UI says something and the
     *  service has a terminal state to stop on. */
    private fun reportFailure(error: Throwable) {
        Log.e(TAG, "Run failed", error)
        val mode = currentMode
        val failed = RunReport(mode).withFailure(
            when (error) {
                is SecurityException -> "Access to your photos was denied or revoked."
                else -> "Unexpected error: ${error.javaClass.simpleName}"
            }
        )
        synchronized(deletionLock) { pendingDeletion?.outcome?.complete(false) }
        _deletionRequest.value = null
        _state.value = UiState.Finished(failed)
    }

    // region conversion

    private suspend fun convert(mode: RunMode, options: RunOptions) {
        if (!EncoderSupport.hasHevcEncoder) {
            _state.value = UiState.Finished(RunReport(mode).withFailure(NO_ENCODER))
            return
        }

        // Originals are only deleted once the run finishes, so both copies are on disk
        // throughout. Starting with the volume nearly full means every photo fails; say so once
        // instead of producing thousands of identical errors.
        val free = encoder.freeStagingBytes()
        if (free in 0 until MIN_FREE_SPACE_BYTES) {
            _state.value = UiState.Finished(
                RunReport(mode).withFailure(
                    "Only ${free / (1024 * 1024)} MB of storage free. Free up space, or use " +
                        "\"Clean up leftovers\" to remove JPGs that already have a HEIC."
                )
            )
            return
        }

        val photos = repository.queryJpgs(options.startMs, options.endMs)
        if (photos.isEmpty()) {
            _state.value = UiState.Finished(RunReport(mode))
            return
        }

        var tally = RunReport(mode)
        val toDelete = mutableListOf<Uri>()

        // Deleting as we go, rather than hoarding every original until the end, is what keeps a
        // large run inside a small amount of free space: each prompt hands the space back before
        // the next batch is written.
        val deleteAsWeGo = mode == RunMode.CONVERT && options.deleteOriginals
        var deletedTotal = 0
        var declined = false
        var endedEarly = false

        encoder.clearStaging()

        try {
            for ((index, photo) in photos.withIndex()) {
                if (cancelRequested) break
                _state.value = UiState.Working(
                    mode,
                    RunProgress(
                        done = index,
                        total = photos.size,
                        currentName = photo.displayName,
                        pendingDeletions = toDelete.size,
                        promptAt = if (deleteAsWeGo) DELETE_PROMPT_EVERY else 0,
                        converted = tally.converted,
                        savedBytes = tally.savedBytes,
                        convertedMotion = tally.convertedMotion,
                        savedMotionBytes = tally.motionSavedBytes,
                        skippedMotion = tally.skippedMotionPhoto,
                        skippedTooSmall = tally.skippedTooSmall,
                        skippedTooSmallBytes = tally.skippedTooSmallBytes,
                        skippedExisting = tally.skippedExisting,
                        skippedNotSmaller = tally.skippedNotSmaller,
                        skippedNameClash = tally.renamedOutput,
                        failures = tally.failureCount,
                    ),
                )

                // Needed twice over: to skip, and — when not skipping — to predict that the real
                // run would hit a name clash, so a dry run does not promise a saving it will not
                // deliver.
                val counterpartExists = repository.heicAlreadyExists(photo)
                if (options.skipAlreadyConverted && counterpartExists) {
                    tally = tally.copy(skippedExisting = tally.skippedExisting + 1)
                    continue
                }

                // A tiny JPG barely gains from re-encoding and still costs a full read/encode/write
                // cycle, so pass it over. A zero SIZE means MediaStore does not know how big it is,
                // and guessing "small" there would wrongly skip it — so only a known, small size
                // counts. The bytes are tallied so the report can say how much was left untouched.
                if (options.skipSmall && photo.sizeBytes in 1 until MIN_CONVERT_SIZE_BYTES) {
                    tally = tally.copy(
                        skippedTooSmall = tally.skippedTooSmall + 1,
                        skippedTooSmallBytes = tally.skippedTooSmallBytes + photo.sizeBytes,
                    )
                    continue
                }

                val metadata = inspect(photo)
                // Decide how this photo's motion video is handled before touching it, so both the
                // encode (which may need to re-attach the video) and the report agree.
                val motion = motionInfo(photo, metadata)
                val skipMotion = motion.isMotionPhoto && (
                    options.motionPolicy == MotionPhotoPolicy.SKIP ||
                        // "Keep the video" but there is no video we can carry across (e.g. a
                        // non-Samsung motion photo): skip rather than silently flatten it.
                        (options.motionPolicy == MotionPhotoPolicy.KEEP_VIDEO &&
                            motion.videoTrailerStart == null)
                    )
                if (skipMotion) {
                    tally = tally.copy(skippedMotionPhoto = tally.skippedMotionPhoto + 1)
                    continue
                }

                // Non-null when the run will re-attach the original video onto the new HEIC.
                val trailerStart = motion.videoTrailerStart
                    ?.takeIf { options.motionPolicy == MotionPhotoPolicy.KEEP_VIDEO }
                val motionOutcome = when {
                    !motion.isMotionPhoto -> MotionOutcome.NONE
                    trailerStart != null -> MotionOutcome.PRESERVED
                    else -> MotionOutcome.DROPPED
                }

                // A JPG with no EXIF of its own still needs a capture date in the file, or a later
                // media rescan would re-derive the HEIC's date as "whenever this ran".
                val ownExif = metadata.exif
                val sourceExif = ownExif ?: MinimalExif.forDate(photo.dateTakenMs)

                // The rotation goes into the HEIF container, and the EXIF Orientation is kept so
                // that it agrees, which is how Samsung's own camera writes HEIC. Zeroing the EXIF
                // tag (as earlier builds did) left MediaStore's orientation column, read from EXIF
                // alone, at 0: anything going by it, Phone Link for one, showed portraits sideways.
                // Android's decoder and Windows both apply the container rotation and ignore the
                // EXIF value, so nothing turns the picture twice. Mirrored orientations cannot be
                // expressed as a rotation and are left exactly as they were.
                val orientation = ExifOrientation.read(sourceExif)
                val rotation = ExifOrientation.rotationDegrees(orientation)
                val outputExif = anchorCaptureTime(sourceExif, photo)

                val forEncoding = JpegMetadata(
                    exif = outputExif,
                    hasXmp = metadata.hasXmp,
                    hasIccProfile = metadata.hasIccProfile,
                    motionPhotoInHeader = metadata.motionPhotoInHeader,
                )

                tally = if (mode == RunMode.DRY_RUN) {
                    measure(options, photo, metadata, motionOutcome, trailerStart, rotation == null,
                        counterpartExists, forEncoding, rotation ?: 0, tally)
                } else {
                    convertOne(options, photo, metadata, motionOutcome, trailerStart, rotation == null,
                        forEncoding, rotation ?: 0, tally, toDelete)
                }

                if (deleteAsWeGo && shouldFlushDeletions(toDelete.size, index)) {
                    _state.value = UiState.AwaitingDeletion(
                        mode,
                        index + 1,
                        photos.size,
                        toDelete.size,
                        lowOnSpace = toDelete.size < DELETE_PROMPT_EVERY,
                    )
                    val answer = confirmDeletion(toDelete.toList())
                    deletedTotal += answer.deleted
                    toDelete.clear()
                    if (!answer.granted) {
                        // Declining means the space is not coming back, so carrying on would only
                        // fill the device. Stop, and say the run ended early rather than
                        // reporting it as a clean finish.
                        declined = true
                        endedEarly = true
                        break
                    }
                }
            }
        } finally {
            encoder.clearStaging()
            // Hands back the encoding thread; the next run starts one on demand.
            encoder.release()
        }

        // Whatever is left over from the last partial chunk.
        if (deleteAsWeGo && !declined && toDelete.isNotEmpty()) {
            _state.value = UiState.AwaitingDeletion(
                mode, photos.size, photos.size, toDelete.size,
            )
            val answer = confirmDeletion(toDelete.toList())
            deletedTotal += answer.deleted
            if (!answer.granted) declined = true
        }

        _state.value = UiState.Finished(
            tally.copy(
                cancelled = cancelRequested || endedEarly,
                deletedOriginals = deletedTotal,
                deletionOutcome = when {
                    !deleteAsWeGo -> DeletionOutcome.NOT_REQUESTED
                    declined -> DeletionOutcome.DECLINED
                    deletedTotal > 0 -> DeletionOutcome.COMPLETED
                    else -> DeletionOutcome.NOT_REQUESTED
                },
            )
        )
    }

    /**
     * Whether to stop and have the converted originals removed now.
     *
     * Two triggers. The usual one is simply having accumulated a batch. The second is storage
     * falling below [LOW_SPACE_THRESHOLD_BYTES] — without it a run on a nearly-full device would
     * sail past the point of no return and then fail on every remaining photo.
     *
     * The free-space call is not cheap, so it is sampled rather than made per photo, and only once
     * enough originals are waiting for a prompt to actually be worth it.
     */
    private fun shouldFlushDeletions(pending: Int, index: Int): Boolean {
        if (pending >= DELETE_PROMPT_EVERY) return true
        if (pending < DELETE_PROMPT_MIN_LOW_SPACE) return false
        if (index % SPACE_CHECK_EVERY != 0) return false
        return encoder.freeStagingBytes() in 0 until LOW_SPACE_THRESHOLD_BYTES
    }

    /** Reads the header, and the tail too when the header alone cannot settle the question. */
    private fun inspect(photo: SourcePhoto): JpegMetadata {
        val header = try {
            repository.openOriginal(photo.uri, hasMediaLocationPermission)
                ?.use { JpegSegments.read(it) }
        } catch (e: Exception) {
            Log.w(TAG, "Could not read segments from ${photo.displayName}", e)
            null
        }
        return header ?: JpegMetadata(null, hasXmp = false, hasIccProfile = false, motionPhotoInHeader = false)
    }

    /** What a photo's motion video is, and whether we can carry it across. */
    private data class MotionInfo(
        val isMotionPhoto: Boolean,
        /**
         * The byte offset where the Samsung SEF trailer begins, when the video can be re-attached
         * by copying `[videoTrailerStart, EOF)` onto the new HEIC. Null for a motion photo whose
         * video we cannot preserve (a Google/Pixel motion photo, whose video lives in an XMP
         * container HeifWriter has no channel for).
         */
        val videoTrailerStart: Long?,
    )

    /**
     * Works out whether a photo is a motion photo and, if so, whether its video can be re-attached.
     *
     * Google records the clip in the XMP, so the header alone flags it — but that video sits in an
     * XMP container we cannot rebuild, so it is a motion photo we can only skip or flatten. Samsung
     * appends a self-contained SEF trailer whose video is a standalone MP4; that one we can carry
     * across verbatim. See [SefTrailer].
     */
    private fun motionInfo(photo: SourcePhoto, metadata: JpegMetadata): MotionInfo {
        val sef = repository.readSefInfo(photo.uri)
        if (sef != null && sef.hasVideo) {
            return MotionInfo(isMotionPhoto = true, videoTrailerStart = sef.sefStart)
        }
        // No re-attachable Samsung video; it may still be a motion photo we cannot preserve.
        return MotionInfo(isMotionPhoto = metadata.motionPhotoInHeader, videoTrailerStart = null)
    }

    /** Tally update shared by the dry run and the real one, for a photo that produced output. */
    private fun countConverted(
        tally: RunReport,
        photo: SourcePhoto,
        metadata: JpegMetadata,
        motionOutcome: MotionOutcome,
        mirrored: Boolean,
        outputBytes: Long,
        downscaled: Boolean,
    ): RunReport = tally.copy(
        converted = tally.converted + 1,
        downscaledToFit = tally.downscaledToFit + if (downscaled) 1 else 0,
        originalBytes = tally.originalBytes + photo.sizeBytes,
        heicBytes = tally.heicBytes + outputBytes,
        // The motion subset of the byte totals; stills fall out as the remainder in the report.
        motionOriginalBytes = tally.motionOriginalBytes +
            if (motionOutcome != MotionOutcome.NONE) photo.sizeBytes else 0,
        motionHeicBytes = tally.motionHeicBytes +
            if (motionOutcome != MotionOutcome.NONE) outputBytes else 0,
        exifPreserved = tally.exifPreserved + if (metadata.exif != null) 1 else 0,
        exifSynthesised = tally.exifSynthesised + if (metadata.exif == null) 1 else 0,
        droppedXmp = tally.droppedXmp + if (metadata.hasXmp) 1 else 0,
        flattenedColour = tally.flattenedColour + if (metadata.hasIccProfile) 1 else 0,
        relocated = tally.relocated +
            if (PhotoNaming.targetRelativePath(photo.relativePath) != photo.relativePath) 1 else 0,
        convertedMotionPhotos = tally.convertedMotionPhotos +
            if (motionOutcome == MotionOutcome.DROPPED) 1 else 0,
        motionPhotosPreserved = tally.motionPhotosPreserved +
            if (motionOutcome == MotionOutcome.PRESERVED) 1 else 0,
        mirroredOrientation = tally.mirroredOrientation + if (mirrored) 1 else 0,
    )

    /** Dry run: encode to a cache file purely to measure it, then throw the file away. */
    private fun measure(
        options: RunOptions,
        photo: SourcePhoto,
        metadata: JpegMetadata,
        motionOutcome: MotionOutcome,
        trailerStart: Long?,
        mirrored: Boolean,
        counterpartExists: Boolean,
        forEncoding: JpegMetadata,
        rotation: Int,
        tally: RunReport,
    ): RunReport {
        encoder.preflight(photo, options.shrinkOversized)?.let {
            return tally.withFailure(photo, it.reason)
        }

        val success = when (
            val result = encoder.encodeToStaging(
                photo, forEncoding, options.quality, rotation, options.shrinkOversized, trailerStart,
            )
        ) {
            is EncodeResult.Failure -> return tally.withFailure(photo, result.reason)
            is EncodeResult.Success -> result
        }

        try {
            if (options.onlyIfSmaller && photo.sizeBytes > 0 && success.bytes >= photo.sizeBytes) {
                return tally.copy(skippedNotSmaller = tally.skippedNotSmaller + 1)
            }
            // A HEIC already in the target folder means the real run would abandon this one, so
            // leave it out of the totals rather than promising a saving that will not happen.
            if (counterpartExists) {
                return tally.copy(renamedOutput = tally.renamedOutput + 1)
            }
            return countConverted(
                tally, photo, metadata, motionOutcome, mirrored, success.bytes, success.downscaled,
            )
        } finally {
            success.file?.delete()
        }
    }

    /**
     * Real run: create the row, encode straight into its file, then publish.
     *
     * Writing in place rather than staging and copying means each photo's bytes reach the flash
     * once instead of twice — which matters on a device with little room to spare.
     */
    private fun convertOne(
        options: RunOptions,
        photo: SourcePhoto,
        metadata: JpegMetadata,
        motionOutcome: MotionOutcome,
        trailerStart: Long?,
        mirrored: Boolean,
        forEncoding: JpegMetadata,
        rotation: Int,
        tally: RunReport,
        toDelete: MutableList<Uri>,
    ): RunReport {
        // Checked before any row exists, so a photo that was never going to work leaves nothing.
        encoder.preflight(photo, options.shrinkOversized)?.let {
            return tally.withFailure(photo, it.reason)
        }

        var failure: String? = null
        var written = 0L
        var downscaled = false

        val published = repository.publishHeic(photo) { uri ->
            when (
                val result = encodeInto(
                    uri, photo, forEncoding, options.quality, rotation, options.shrinkOversized, trailerStart,
                )
            ) {
                is EncodeResult.Failure -> {
                    failure = result.reason
                    0L
                }

                is EncodeResult.Success -> {
                    written = result.bytes
                    downscaled = result.downscaled
                    result.bytes
                }
            }
        } ?: return tally.withFailure(photo, failure ?: "could not be saved to the gallery")

        // MediaStore silently appends " (1)" when the name is taken. Getting a different name
        // back means something was already there, which is not what this was asked to do — so the
        // copy is undone and the original left alone, leaving the library as it was found.
        val savedAs = repository.displayNameOf(published.uri)
        if (savedAs != null && !savedAs.equals(photo.heicName, ignoreCase = true)) {
            repository.discard(published.uri)
            return tally.copy(renamedOutput = tally.renamedOutput + 1)
        }

        // A zero SIZE means MediaStore does not know how big the original was; comparing against
        // it would reject every such photo as "not smaller" and it would never convert.
        if (options.onlyIfSmaller && photo.sizeBytes > 0 && written >= photo.sizeBytes) {
            repository.discard(published.uri)
            return tally.copy(skippedNotSmaller = tally.skippedNotSmaller + 1)
        }

        toDelete += photo.uri
        val counted = countConverted(tally, photo, metadata, motionOutcome, mirrored, written, downscaled)
        return if (published.datesApplied) {
            counted
        } else {
            // The EXIF inside the file still carries the capture time, so a later rescan can
            // recover it — but the gallery will sort this one wrongly until then, and the
            // original is about to go, so say so.
            counted.copy(dateWriteFailed = counted.dateWriteFailed + 1)
        }
    }

    /**
     * Writes the encoded image into the row's file.
     *
     * Prefers encoding straight into the descriptor. If that turns out not to work on this device
     * — MediaMuxer writing through a content provider is the part with least guarantee — the very
     * first failure drops the whole run back to staging-and-copy, rather than failing every photo
     * for a reason that has nothing to do with the photos.
     */
    private fun encodeInto(
        uri: Uri,
        photo: SourcePhoto,
        forEncoding: JpegMetadata,
        quality: Int,
        rotation: Int,
        allowShrink: Boolean,
        trailerStart: Long?,
    ): EncodeResult {
        if (directWriteWorks) {
            val direct = repository.openForWrite(uri)?.use { descriptor ->
                encoder.encodeToDescriptor(
                    photo, forEncoding, quality, rotation, descriptor.fileDescriptor, allowShrink, trailerStart,
                )
            } ?: EncodeResult.Failure("could not open the new file for writing")

            if (direct is EncodeResult.Success) {
                directWriteProven = true
                return direct
            }
            if (directWriteProven) return direct

            Log.w(TAG, "Direct write unusable here; falling back to staging for this run")
            directWriteWorks = false
        }

        val staged = when (
            val result = encoder.encodeToStaging(photo, forEncoding, quality, rotation, allowShrink, trailerStart)
        ) {
            is EncodeResult.Failure -> return result
            is EncodeResult.Success -> result
        }
        return try {
            val copied = staged.file?.let { copyInto(it, uri) } ?: 0L
            if (copied > 0L) {
                EncodeResult.Success(copied, staged.width, staged.height, downscaled = staged.downscaled)
            } else {
                EncodeResult.Failure("could not copy the encoded file into the gallery")
            }
        } finally {
            staged.file?.delete()
        }
    }


    private fun copyInto(source: File, target: Uri): Long =
        app.contentResolver.openOutputStream(target, "wt")?.use { out ->
            source.inputStream().use { input -> input.copyTo(out) }
        } ?: 0L

    // endregion

    // region clean up

    /** Removes JPGs that already have a HEIC beside them — the leftovers of an interrupted run. */
    private suspend fun cleanUp(options: RunOptions) {
        val stranded = repository.findStrandedOriginals(DateRange(options.startMs, options.endMs))
        if (cancelRequested) {
            _state.value = UiState.Finished(RunReport(RunMode.CLEAN_UP, cancelled = true))
            return
        }
        if (stranded.isEmpty()) {
            _state.value = UiState.Finished(RunReport(RunMode.CLEAN_UP))
            return
        }

        _state.value = UiState.AwaitingDeletion(
            RunMode.CLEAN_UP, 0, stranded.size, stranded.size,
        )
        val answer = confirmDeletion(stranded.map { it.uri })
        _state.value = UiState.Finished(
            RunReport(
                RunMode.CLEAN_UP,
                originalBytes = stranded.sumOf { it.sizeBytes },
                deletedOriginals = answer.deleted,
                deletionOutcome = if (answer.granted) {
                    DeletionOutcome.COMPLETED
                } else {
                    DeletionOutcome.DECLINED
                },
            )
        )
    }

    // endregion

    // region capture time

    /**
     * Gives the EXIF what MediaProvider needs to date the HEIC: a `DateTimeOriginal` (PhotoScan
     * writes only `DateTime`) and a timezone for it. See [ExifEditor] for why a date with no zone
     * is recorded as no date at all.
     *
     * The zone is exact whenever the true instant is known (a PhotoScan filename, or the
     * original's MediaStore date) and [ExifEditor.HOME_ZONE] otherwise. A GPS timestamp is left to
     * speak for itself rather than be contradicted by an assumed zone.
     */
    private fun anchorCaptureTime(block: ByteArray, photo: SourcePhoto): ByteArray {
        val exif = ExifEditor.summarise(block) ?: return block
        val wallClock = exif.dateTimeOriginal ?: exif.fallbackDateTime ?: return block
        val local = ExifEditor.parseExifDate(wallClock) ?: return block
        val addOriginal = if (exif.dateTimeOriginal == null) wallClock else null

        val named = (FileNameTime.of(photo.displayName) as? FileNameTime.Instant)?.utcMs
        val offset = if (exif.hasOffsetTimeOriginal) {
            null
        } else {
            ExifEditor.exactOffset(local, named ?: photo.dateTakenMs)
                ?: if (exif.hasGpsTimestamp) null else ExifEditor.HOME_ZONE.rules.getOffset(local)
        }
        if (addOriginal == null && offset == null) return block
        return ExifEditor.edit(block, dateTimeOriginal = addOriginal, offset = offset) ?: block
    }

    // endregion

    // region videos

    /**
     * Re-encodes the H.264 videos in the range as HEVC.
     *
     * Each video is encoded to a staging file, has its capture time carried across, and is checked
     * against the original before it is published beside it as `<name>_HEVC.mp4`. Originals are
     * offered for deletion in batches; once one is gone, its replacement takes the original's name.
     */
    private suspend fun convertVideos(options: RunOptions) {
        val mode = RunMode.VIDEO
        if (!EncoderSupport.hasHevcEncoder) {
            _state.value = UiState.Finished(RunReport(mode).withFailure(NO_ENCODER))
            return
        }
        val videos = repository.queryVideos(options.startMs, options.endMs)
        if (videos.isEmpty()) {
            _state.value = UiState.Finished(RunReport(mode))
            return
        }
        if (!videoStaging.isDirectory) videoStaging.mkdirs()
        videoStaging.listFiles()?.forEach { it.delete() }

        var tally = RunReport(mode)
        val deleteAsWeGo = options.deleteOriginals
        /** Converted but not yet offered for deletion: original URI → (new URI, original name). */
        val waiting = LinkedHashMap<Uri, Pair<Uri, String>>()
        var deletedTotal = 0
        var declined = false

        // Pace by bytes, not by count: a two-minute clip takes forty times as long as a three-second one.
        val totalMb = (videos.sumOf { it.sizeBytes } / MB).toInt().coerceAtLeast(1)
        val eta = Eta(totalMb)
        var doneBytes = 0L

        fun progress(index: Int, video: SourceVideo, fraction: Float) {
            val doneMb = ((doneBytes + (video.sizeBytes * fraction).toLong()) / MB).toInt()
            _state.value = UiState.Working(
                mode,
                RunProgress(
                    done = index,
                    total = videos.size,
                    currentName = video.displayName,
                    converted = tally.converted,
                    savedBytes = tally.savedBytes,
                    failures = tally.failureCount,
                    skippedExisting = tally.skippedExisting,
                    skippedNotSmaller = tally.skippedNotSmaller,
                    pendingDeletions = waiting.size,
                    remainingMs = eta.remainingMs(doneMb),
                    itemFraction = fraction,
                ),
            )
        }

        /** Offers the waiting originals for deletion, then renames their replacements. */
        suspend fun flushDeletions(index: Int) {
            if (waiting.isEmpty()) return
            _state.value = UiState.AwaitingDeletion(mode, index, videos.size, waiting.size)
            val answer = confirmDeletion(waiting.keys.toList())
            val gone = answer.accepted.toHashSet()
            deletedTotal += answer.deleted
            for ((original, replacement) in waiting) {
                if (original in gone) repository.renameVideo(replacement.first, replacement.second)
            }
            waiting.clear()
            if (!answer.granted) declined = true
        }

        for ((index, video) in videos.withIndex()) {
            if (cancelRequested) break
            progress(index, video, 0f)
            try {
                when (val check = videoConverter.check(video)) {
                    is VideoConverter.Check.Skip -> tally = when (check.kind) {
                        VideoConverter.SkipKind.SPECIAL -> tally.copy(videoSkippedSpecial = tally.videoSkippedSpecial + 1)
                        VideoConverter.SkipKind.CODEC -> tally.copy(videoSkippedCodec = tally.videoSkippedCodec + 1)
                        VideoConverter.SkipKind.FORMAT -> tally.copy(videoSkippedFormat = tally.videoSkippedFormat + 1)
                    }
                    is VideoConverter.Check.Convert -> tally = convertOneVideo(options, video, check.info, tally, waiting) {
                        progress(index, video, it)
                    }
                }
            } finally {
                doneBytes += video.sizeBytes
            }

            if (deleteAsWeGo && !declined &&
                (waiting.size >= VIDEO_DELETE_EVERY || (waiting.isNotEmpty() && encoder.freeStagingBytes() in 0 until LOW_SPACE_THRESHOLD_BYTES))
            ) {
                flushDeletions(index + 1)
            }
        }
        if (deleteAsWeGo && !declined) flushDeletions(videos.size)
        videoStaging.listFiles()?.forEach { it.delete() }

        _state.value = UiState.Finished(
            tally.copy(
                cancelled = cancelRequested,
                deletedOriginals = deletedTotal,
                deletionOutcome = when {
                    !deleteAsWeGo || tally.converted == 0 -> DeletionOutcome.NOT_REQUESTED
                    declined -> DeletionOutcome.DECLINED
                    else -> DeletionOutcome.COMPLETED
                },
            )
        )
    }

    private suspend fun convertOneVideo(
        options: RunOptions,
        video: SourceVideo,
        info: Mp4Metadata.Info,
        tally: RunReport,
        waiting: MutableMap<Uri, Pair<Uri, String>>,
        onProgress: (Float) -> Unit,
    ): RunReport {
        // A copy from an earlier run is still waiting beside it.
        if (repository.videoExistsBeside(video, video.convertedName)) {
            return tally.copy(skippedExisting = tally.skippedExisting + 1)
        }
        // Staging and then publishing briefly needs room for the copy twice over.
        val free = encoder.freeStagingBytes()
        if (free in 0 until video.sizeBytes * 2 + MIN_FREE_SPACE_BYTES) {
            return tally.withFailure(video.uri, video.displayName, video.sizeBytes,
                "not enough free space to convert it (${free / MB} MB free)")
        }

        val staged = File(videoStaging, "${System.nanoTime()}.mp4")
        try {
            val outcome = videoConverter.convert(video, info, staged, onProgress)
            val bytes = when (outcome) {
                is VideoConverter.Outcome.Failed ->
                    return tally.withFailure(video.uri, video.displayName, video.sizeBytes, outcome.reason)
                is VideoConverter.Outcome.Converted -> outcome.bytes
            }
            if (options.onlyIfSmaller && bytes >= video.sizeBytes) {
                return tally.copy(skippedNotSmaller = tally.skippedNotSmaller + 1)
            }
            val published = repository.publishVideo(video, staged)
                ?: return tally.withFailure(video.uri, video.displayName, video.sizeBytes, "could not be saved to the gallery")
            if (options.deleteOriginals) waiting[video.uri] = published to video.displayName
            return tally.copy(
                converted = tally.converted + 1,
                originalBytes = tally.originalBytes + video.sizeBytes,
                heicBytes = tally.heicBytes + bytes,
            )
        } finally {
            staged.delete()
        }
    }

    // endregion

    // region repair

    /**
     * Repairs HEICs already in the library: rotation that viewers disagree on, and missing capture
     * dates. Checks every HEIC, fixes what it can directly, asks once per batch for the ones the
     * app no longer owns, then confirms MediaStore actually reads the new values.
     */
    private suspend fun repair() {
        val mode = RunMode.REPAIR
        val rows = repository.queryHeics()
        if (rows.isEmpty()) {
            _state.value = UiState.Finished(RunReport(mode))
            return
        }
        val knownTimes = repository.jpgCaptureTimesByName()

        var tally = RunReport(mode)
        val repaired = ArrayList<Pair<HeicRow, HeicRepair.Fix>>()
        val needsAccess = ArrayList<Pair<HeicRow, HeicRepair.Fix>>()
        var datesFixed = 0
        var rotationsFixed = 0

        fun progress(phase: RepairPhase, done: Int, total: Int, name: String, eta: Eta) {
            _state.value = UiState.Working(
                mode,
                RunProgress(
                    done = done,
                    total = total,
                    currentName = name,
                    failures = tally.failureCount,
                    repairedDates = datesFixed,
                    repairedRotations = rotationsFixed,
                    repairPhase = phase,
                    awaitingPermission = needsAccess.size,
                    remainingMs = eta.remainingMs(done),
                ),
            )
        }

        fun applyOne(row: HeicRow, fix: HeicRepair.Fix) {
            when (val outcome = repairer.apply(row.uri, row.displayName, fix)) {
                HeicRepair.Outcome.Repaired -> {
                    repaired += row to fix
                    if (fix.fixesDate) datesFixed++
                    if (fix.fixesRotation) rotationsFixed++
                }
                is HeicRepair.Outcome.Failed ->
                    tally = tally.withFailure(row.uri, row.displayName, row.sizeBytes, outcome.reason)
            }
        }

        val checking = Eta(rows.size)
        for ((index, row) in rows.withIndex()) {
            if (cancelRequested) break
            if (index % REPAIR_PROGRESS_EVERY == 0) progress(RepairPhase.CHECKING, index, rows.size, row.displayName, checking)
            val undated = (row.dateTakenMs ?: 0L) <= 0L
            val known = knownTimes[PhotoRepository.nameKey(row.displayName)]
            when (val diagnosis = repairer.diagnose(row.uri, row.displayName, undated, known)) {
                HeicRepair.Diagnosis.Fine -> Unit
                is HeicRepair.Diagnosis.Cannot -> when {
                    // A dated HEIC that cannot be parsed is most likely a camera original needing
                    // nothing; only an undated one is a photo actually left broken.
                    !undated -> Unit
                    diagnosis.reason.startsWith("no capture time") ->
                        tally = tally.copy(repairNoDateSource = tally.repairNoDateSource + 1)
                    else -> tally = tally.withFailure(row.uri, row.displayName, row.sizeBytes, diagnosis.reason)
                }
                is HeicRepair.Diagnosis.Needs -> try {
                    applyOne(row, diagnosis.fix)
                } catch (_: SecurityException) {
                    needsAccess += row to diagnosis.fix
                }
            }
            tally = tally.copy(repairChecked = index + 1)
        }

        if (needsAccess.isNotEmpty() && !cancelRequested) {
            _state.value = UiState.AwaitingDeletion(mode, tally.repairChecked, rows.size, needsAccess.size)
            val answer = confirmConsent(needsAccess.map { it.first.uri }, ConsentKind.MODIFY)
            val allowed = answer.accepted.toHashSet()
            var declined = 0
            val writing = Eta(needsAccess.size)
            for ((index, pair) in needsAccess.withIndex()) {
                if (cancelRequested) break
                val (row, fix) = pair
                if (index % REPAIR_PROGRESS_EVERY == 0) progress(RepairPhase.WRITING, index, needsAccess.size, row.displayName, writing)
                if (row.uri !in allowed) {
                    declined++
                    continue
                }
                try {
                    applyOne(row, fix)
                } catch (_: SecurityException) {
                    tally = tally.withFailure(row.uri, row.displayName, row.sizeBytes, "access was not granted")
                }
            }
            tally = tally.copy(repairDeclined = declined)
        }

        val verified = confirmRepairs(repaired) { done, total, eta ->
            progress(RepairPhase.REFRESHING, done, total, "", eta)
        }
        _state.value = UiState.Finished(
            tally.copy(
                cancelled = cancelRequested,
                repairedDates = verified.dates,
                repairedRotations = verified.rotations,
                repairUnverified = verified.unverified,
            )
        )
    }

    private data class Verified(val dates: Int, val rotations: Int, val unverified: Int)

    /**
     * Checks MediaStore reads each fix back.
     *
     * MediaProvider rescans a file after a writer closes it only some of the time — reliably when
     * it grew, rarely for a same-length rewrite, which is what a rotation-only fix is. Anything not
     * yet showing the change is handed to the media scanner explicitly, which re-reads a few files a
     * second; [onProgress] reports how far it has got, with an estimate of the time left.
     */
    private fun confirmRepairs(
        repaired: List<Pair<HeicRow, HeicRepair.Fix>>,
        onProgress: (done: Int, total: Int, eta: Eta) -> Unit,
    ): Verified {
        if (repaired.isEmpty()) return Verified(0, 0, 0)

        fun holds(fix: HeicRepair.Fix, state: CaptureState?): Boolean = state != null &&
            (!fix.fixesDate || (state.dateTakenMs ?: 0L) > 0L) &&
            (!fix.fixesRotation || state.orientation != 0)

        var states = repository.captureStates(repaired.map { it.first.id })
        val stale = repaired.filterNot { (row, fix) -> holds(fix, states[row.id]) }
        if (stale.isNotEmpty()) {
            val eta = Eta(stale.size)
            onProgress(0, stale.size, eta)
            repository.scanFiles(stale.mapNotNull { it.first.path }) { scanned ->
                if (cancelRequested) return@scanFiles false
                onProgress(scanned, stale.size, eta)
                true
            }
            states = repository.captureStates(repaired.map { it.first.id })
        }

        var dates = 0
        var rotations = 0
        var unverified = 0
        for ((row, fix) in repaired) {
            if (!holds(fix, states[row.id])) {
                unverified++
                continue
            }
            if (fix.fixesDate) dates++
            if (fix.fixesRotation) rotations++
        }
        return Verified(dates, rotations, unverified)
    }

    // endregion

    // region deletion

    /**
     * Raises the system delete confirmation for [uris] and suspends until every batch has been
     * answered.
     *
     * Suspending is the whole point: the run stops here, the space comes back, and only then does
     * conversion continue. Deletion cannot be done from the engine directly — these files belong
     * to the camera, so Android insists the user confirms.
     */
    private suspend fun confirmDeletion(uris: List<Uri>): DeletionAnswer = confirmConsent(uris, ConsentKind.DELETE)

    /** [confirmDeletion] for any kind of consent: the same batches, prompts and cancellation. */
    private suspend fun confirmConsent(uris: List<Uri>, kind: ConsentKind): DeletionAnswer {
        if (uris.isEmpty()) return DeletionAnswer(granted = true, deleted = 0)

        // The run now blocks on a confirmation dialog until the user acts, so make some noise:
        // a long batch often finishes with the phone away, and this is where attention is needed.
        DeletionAlert.beep()

        val batchSize = if (kind == ConsentKind.MODIFY) MODIFY_BATCH_SIZE else DELETE_BATCH_SIZE
        val request = PendingDeletion(uris.chunked(batchSize), kind)
        synchronized(deletionLock) { pendingDeletion = request }
        requestNextDeletionBatch()

        val granted = try {
            request.outcome.await()
        } finally {
            synchronized(deletionLock) { pendingDeletion = null }
            _deletionRequest.value = null
        }
        return DeletionAnswer(granted, request.deleted, synchronized(deletionLock) { request.accepted.toList() })
    }

    /**
     * Raises the confirmation for the next batch, unless one is already on screen. The Activity
     * calls this again whenever it comes back, so a prompt that was never answered — the app was
     * swiped away mid-dialog, say — gets picked up instead of leaving the run stuck.
     */
    fun requestNextDeletionBatch() {
        val batch = synchronized(deletionLock) {
            val request = pendingDeletion
            if (request == null || request.inFlight) null
            else request.batches.getOrNull(request.index)?.let { DeletionRequest(it, request.kind) }
        }
        _deletionRequest.value = batch
    }

    fun createDeleteIntentSender(uris: List<Uri>) =
        MediaStore.createDeleteRequest(app.contentResolver, uris).intentSender

    /** The system dialog for [request] — delete, or allow the app to modify. */
    fun createConsentIntentSender(request: DeletionRequest) = when (request.kind) {
        ConsentKind.DELETE -> MediaStore.createDeleteRequest(app.contentResolver, request.uris)
        ConsentKind.MODIFY -> MediaStore.createWriteRequest(app.contentResolver, request.uris)
    }.intentSender

    /** Clears the request once the Activity has launched it, so a restarted collector cannot
     *  replay it and raise the confirmation dialog twice. */
    fun consumeDeletionRequest() {
        synchronized(deletionLock) { pendingDeletion?.inFlight = true }
        _deletionRequest.value = null
    }

    fun onDeletionResult(granted: Boolean) {
        var more = false
        synchronized(deletionLock) {
            val request = pendingDeletion ?: return
            request.inFlight = false
            val batch = request.batches.getOrNull(request.index) ?: return
            if (granted) {
                request.deleted += batch.size
                request.accepted += batch
                request.index++
            }
            // Declining stops here. Continuing to convert without reclaiming space is exactly
            // what fills the device, and the leftovers can be cleared later.
            more = granted && request.index < request.batches.size
            if (!more) request.outcome.complete(granted)
        }
        _deletionRequest.value = null
        if (more) requestNextDeletionBatch()
    }

    fun onDeletionFailed(e: Exception) {
        Log.e(TAG, "Delete request failed", e)
        synchronized(deletionLock) { pendingDeletion?.outcome?.complete(false) }
        _deletionRequest.value = null
    }

    // endregion

    companion object {
        private const val TAG = "ConversionEngine"

        /** Keeps the URI list well inside the binder transaction limit for one delete dialog. */
        private const val DELETE_BATCH_SIZE = 500

        /**
         * Photos per "allow changes" prompt. Larger than the delete batch because a repair can
         * touch most of a library: at ~75 bytes a URI, a thousand is still a small fraction of the
         * binder limit, and it halves the number of prompts.
         */
        private const val MODIFY_BATCH_SIZE = 1000

        /** Videos to convert before stopping to have their originals removed: each is large, so
         *  the space is worth reclaiming sooner than the photo batch of 500. */
        private const val VIDEO_DELETE_EVERY = 25

        private const val MB = 1024L * 1024

        /** A repair checks tens of thousands of files; publishing every one would flood the UI. */
        private const val REPAIR_PROGRESS_EVERY = 20

        /**
         * How many converted originals to accumulate before stopping to have them removed. Equal
         * to the batch size, so one pause means exactly one dialog.
         */
        private const val DELETE_PROMPT_EVERY = 500

        /** Prompt early, before the batch is full, once free space drops under this. */
        private const val LOW_SPACE_THRESHOLD_BYTES = 1024L * 1024 * 1024

        /** Below this many waiting originals a prompt would not free enough to be worth it. */
        private const val DELETE_PROMPT_MIN_LOW_SPACE = 50

        /** Sample the free-space figure every this many photos rather than on each one. */
        private const val SPACE_CHECK_EVERY = 10

        /** Below this, a run cannot make meaningful progress and should not start. */
        private const val MIN_FREE_SPACE_BYTES = 256L * 1024 * 1024

        /** JPGs smaller than this (1 MB) are passed over: too little to gain to be worth re-encoding. */
        private const val MIN_CONVERT_SIZE_BYTES = 1024L * 1024

        const val NO_ENCODER = "This device has no HEIC (HEVC) encoder, so nothing can be converted."

        // Holds the *application* context, so it lives exactly as long as the process it is
        // meant to: that is the point of putting the run here instead of in a ViewModel.
        @SuppressLint("StaticFieldLeak")
        @Volatile
        private var instance: ConversionEngine? = null

        fun get(context: Context): ConversionEngine =
            instance ?: synchronized(this) {
                instance ?: ConversionEngine(context).also { instance = it }
            }
    }
}
