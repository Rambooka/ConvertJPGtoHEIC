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
}

data class RunOptions(
    val startMs: Long,
    val endMs: Long,
    val quality: Int,
    val deleteOriginals: Boolean,
    /** Discard a HEIC that came out no smaller than its JPG, rather than trade quality for nothing. */
    val onlyIfSmaller: Boolean,
    val skipAlreadyConverted: Boolean,
    /** Leave motion photos alone — converting one silently discards its embedded video. */
    val skipLossyMetadata: Boolean,
)

data class RunProgress(
    val done: Int,
    val total: Int,
    val currentName: String,
    /** Converted originals waiting to be deleted. */
    val pendingDeletions: Int = 0,
    /** How many they are waiting for, so the UI need not know the engine's batch size. */
    val promptAt: Int = 0,
    /** Running tallies, so a long run shows what it is quietly passing over. */
    val skippedMotion: Int = 0,
    val skippedOther: Int = 0,
    val failures: Int = 0,
)

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
    /** Written to a different folder, because the source folder is not one the images
     *  collection accepts. */
    val relocated: Int = 0,
    /** Published, but MediaStore would not accept the original capture date on the row. */
    val dateWriteFailed: Int = 0,
    /** Abandoned because a file of that name was already there — nothing was changed, e.g. `IMG_1234 (1).heic`. */
    val renamedOutput: Int = 0,
    val originalBytes: Long = 0,
    val heicBytes: Long = 0,
    /** Sample of failure messages, capped — see [failureCount] for the real total. */
    val failures: List<String> = emptyList(),
    val failureCount: Int = 0,
    val deletedOriginals: Int = 0,
    val deletionOutcome: DeletionOutcome = DeletionOutcome.NOT_REQUESTED,
) {
    /**
     * Records a failure without letting the list grow without bound.
     *
     * Appending to an immutable list once per photo is quadratic, and a run where everything fails
     * — a device with no working encoder, say — would build thousands of strings the UI never
     * shows. Only a sample is kept; the count stays exact.
     */
    fun withFailure(message: String): RunReport = copy(
        failures = if (failures.size < MAX_RETAINED_FAILURES) failures + message else failures,
        failureCount = failureCount + 1,
    )

    val savedBytes: Long get() = originalBytes - heicBytes
    val savedPercent: Int
        get() = if (originalBytes <= 0) 0 else ((savedBytes * 100) / originalBytes).toInt()
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

/** A batch of originals waiting on the system's delete confirmation dialog. */
data class DeletionRequest(val uris: List<Uri>)

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
    private class PendingDeletion(val batches: List<List<Uri>>) {
        var index = 0
        var deleted = 0

        /** A dialog is on screen; do not raise a second one for the same batch. */
        var inFlight = false
        val outcome = CompletableDeferred<Boolean>()
    }

    private data class DeletionAnswer(val granted: Boolean, val deleted: Int)

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
                        skippedMotion = tally.skippedMotionPhoto,
                        skippedOther = tally.skippedExisting + tally.skippedNotSmaller +
                            tally.renamedOutput,
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

                val metadata = inspect(photo)
                // Checked either way: if we are not skipping it, the lost video still belongs in
                // the report, because the original is about to be deleted.
                val motionPhoto = isMotionPhoto(photo, metadata)
                if (options.skipLossyMetadata && motionPhoto) {
                    tally = tally.copy(skippedMotionPhoto = tally.skippedMotionPhoto + 1)
                    continue
                }

                // A JPG with no EXIF of its own still needs a capture date in the file, or a later
                // media rescan would re-derive the HEIC's date as "whenever this ran".
                val ownExif = metadata.exif
                val sourceExif = ownExif ?: MinimalExif.forDate(photo.dateTakenMs)

                // Move the rotation from EXIF into the HEIF container, and blank the EXIF tag so
                // nothing turns the image a second time. Mirrored orientations cannot be expressed
                // as a rotation, so those are left exactly as they were.
                val orientation = ExifOrientation.read(sourceExif)
                val rotation = ExifOrientation.rotationDegrees(orientation)
                val outputExif = if (rotation != null) {
                    ExifOrientation.normalised(sourceExif)
                } else {
                    sourceExif
                }

                val forEncoding = JpegMetadata(
                    exif = outputExif,
                    hasXmp = metadata.hasXmp,
                    hasIccProfile = metadata.hasIccProfile,
                    motionPhotoInHeader = metadata.motionPhotoInHeader,
                )

                tally = if (mode == RunMode.DRY_RUN) {
                    measure(options, photo, metadata, motionPhoto, rotation == null,
                        counterpartExists, forEncoding, rotation ?: 0, tally)
                } else {
                    convertOne(options, photo, metadata, motionPhoto, rotation == null,
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

    /**
     * Google records the embedded clip in the XMP, so the header settles it. Samsung records
     * nothing up front, so its motion photos can only be found from the trailer — worth the extra
     * short read, because converting one destroys the video for good.
     */
    private fun isMotionPhoto(photo: SourcePhoto, metadata: JpegMetadata): Boolean {
        if (metadata.motionPhotoInHeader) return true
        val tail = repository.readTail(photo.uri, TAIL_SCAN_BYTES) ?: return false
        return JpegSegments.hasMotionPhotoTrailer(tail)
    }

    /** Tally update shared by the dry run and the real one, for a photo that produced output. */
    private fun countConverted(
        tally: RunReport,
        photo: SourcePhoto,
        metadata: JpegMetadata,
        motionPhoto: Boolean,
        mirrored: Boolean,
        outputBytes: Long,
    ): RunReport = tally.copy(
        converted = tally.converted + 1,
        originalBytes = tally.originalBytes + photo.sizeBytes,
        heicBytes = tally.heicBytes + outputBytes,
        exifPreserved = tally.exifPreserved + if (metadata.exif != null) 1 else 0,
        exifSynthesised = tally.exifSynthesised + if (metadata.exif == null) 1 else 0,
        droppedXmp = tally.droppedXmp + if (metadata.hasXmp) 1 else 0,
        flattenedColour = tally.flattenedColour + if (metadata.hasIccProfile) 1 else 0,
        relocated = tally.relocated +
            if (PhotoNaming.targetRelativePath(photo.relativePath) != photo.relativePath) 1 else 0,
        convertedMotionPhotos = tally.convertedMotionPhotos + if (motionPhoto) 1 else 0,
        mirroredOrientation = tally.mirroredOrientation + if (mirrored) 1 else 0,
    )

    /** Dry run: encode to a cache file purely to measure it, then throw the file away. */
    private fun measure(
        options: RunOptions,
        photo: SourcePhoto,
        metadata: JpegMetadata,
        motionPhoto: Boolean,
        mirrored: Boolean,
        counterpartExists: Boolean,
        forEncoding: JpegMetadata,
        rotation: Int,
        tally: RunReport,
    ): RunReport {
        encoder.preflight(photo)?.let {
            return tally.withFailure("${photo.displayName} — ${it.reason}")
        }

        val success = when (
            val result = encoder.encodeToStaging(photo, forEncoding, options.quality, rotation)
        ) {
            is EncodeResult.Failure -> return tally.withFailure("${photo.displayName} — ${result.reason}")
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
            return countConverted(tally, photo, metadata, motionPhoto, mirrored, success.bytes)
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
        motionPhoto: Boolean,
        mirrored: Boolean,
        forEncoding: JpegMetadata,
        rotation: Int,
        tally: RunReport,
        toDelete: MutableList<Uri>,
    ): RunReport {
        // Checked before any row exists, so a photo that was never going to work leaves nothing.
        encoder.preflight(photo)?.let {
            return tally.withFailure("${photo.displayName} — ${it.reason}")
        }

        var failure: String? = null
        var written = 0L

        val published = repository.publishHeic(photo) { uri ->
            when (val result = encodeInto(uri, photo, forEncoding, options.quality, rotation)) {
                is EncodeResult.Failure -> {
                    failure = result.reason
                    0L
                }

                is EncodeResult.Success -> {
                    written = result.bytes
                    result.bytes
                }
            }
        } ?: return tally.withFailure(
            "${photo.displayName} — ${failure ?: "could not be saved to the gallery"}"
        )

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
        val counted = countConverted(tally, photo, metadata, motionPhoto, mirrored, written)
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
    ): EncodeResult {
        if (directWriteWorks) {
            val direct = repository.openForWrite(uri)?.use { descriptor ->
                encoder.encodeToDescriptor(
                    photo, forEncoding, quality, rotation, descriptor.fileDescriptor,
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
            val result = encoder.encodeToStaging(photo, forEncoding, quality, rotation)
        ) {
            is EncodeResult.Failure -> return result
            is EncodeResult.Success -> result
        }
        return try {
            val copied = staged.file?.let { copyInto(it, uri) } ?: 0L
            if (copied > 0L) {
                EncodeResult.Success(copied, staged.width, staged.height)
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

    // region deletion

    /**
     * Raises the system delete confirmation for [uris] and suspends until every batch has been
     * answered.
     *
     * Suspending is the whole point: the run stops here, the space comes back, and only then does
     * conversion continue. Deletion cannot be done from the engine directly — these files belong
     * to the camera, so Android insists the user confirms.
     */
    private suspend fun confirmDeletion(uris: List<Uri>): DeletionAnswer {
        if (uris.isEmpty()) return DeletionAnswer(granted = true, deleted = 0)

        val request = PendingDeletion(uris.chunked(DELETE_BATCH_SIZE))
        synchronized(deletionLock) { pendingDeletion = request }
        requestNextDeletionBatch()

        val granted = try {
            request.outcome.await()
        } finally {
            synchronized(deletionLock) { pendingDeletion = null }
            _deletionRequest.value = null
        }
        return DeletionAnswer(granted, request.deleted)
    }

    /**
     * Raises the confirmation for the next batch, unless one is already on screen. The Activity
     * calls this again whenever it comes back, so a prompt that was never answered — the app was
     * swiped away mid-dialog, say — gets picked up instead of leaving the run stuck.
     */
    fun requestNextDeletionBatch() {
        val batch = synchronized(deletionLock) {
            val request = pendingDeletion
            if (request == null || request.inFlight) null else request.batches.getOrNull(request.index)
        }
        _deletionRequest.value = batch?.let { DeletionRequest(it) }
    }

    fun createDeleteIntentSender(uris: List<Uri>) =
        MediaStore.createDeleteRequest(app.contentResolver, uris).intentSender

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

        /** Motion-photo trailer markers sit at the very end of the file. */
        private const val TAIL_SCAN_BYTES = 64 * 1024

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
