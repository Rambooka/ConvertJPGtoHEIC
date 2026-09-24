package com.example.convertjpgtoheic

import android.app.ActivityManager
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.storage.StorageManager
import android.system.Os
import android.system.OsConstants
import android.util.Log
import androidx.heifwriter.HeifWriter
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileDescriptor
import java.io.FileOutputStream

/** Where the encoder should put the HEIC it produces. */
sealed interface HeicTarget {
    /** A cache file the caller will publish or throw away. Used by the dry run. */
    data class Staging(val file: File) : HeicTarget

    /**
     * An already-created MediaStore row, written in place.
     *
     * This is the path a real run takes. Staging first and copying afterwards would put every
     * photo's bytes on the flash twice, which is the last thing a nearly-full device needs.
     */
    data class Descriptor(val descriptor: FileDescriptor) : HeicTarget
}

sealed interface EncodeResult {
    /** [file] is set only for a staged encode; a descriptor write has no file of its own. */
    data class Success(
        val bytes: Long,
        val width: Int,
        val height: Int,
        val file: File? = null,
    ) : EncodeResult

    data class Failure(val reason: String) : EncodeResult
}

/** Re-encodes a JPG as HEIC via the device's HEVC encoder. */
class HeicEncoder(context: Context, private val repository: PhotoRepository) {

    private val cacheDir = File(context.cacheDir, "heic-staging")

    /**
     * The staging directory is not created once and trusted forever.
     *
     * Android evicts app cache directories when storage runs low, which is exactly when a large
     * conversion is running. If the directory vanishes, HeifWriter cannot create its output and
     * every remaining photo fails with FileNotFoundException until the app is restarted.
     */
    private fun ensureStagingDir(): Boolean = cacheDir.isDirectory || cacheDir.mkdirs()

    private val storageManager = context.getSystemService(StorageManager::class.java)

    /**
     * Space available to this app, counting what the system could reclaim.
     *
     * [File.getUsableSpace] reports only what is free right now, which on a nearly-full phone can
     * read as almost nothing while gigabytes of other apps' caches sit there waiting to be
     * evicted. getAllocatableBytes counts those, so it answers the question we actually have:
     * can this write succeed?
     */
    private fun allocatableBytes(): Long {
        if (!ensureStagingDir()) return -1L
        val manager = storageManager
        if (manager != null) {
            runCatching {
                return manager.getAllocatableBytes(manager.getUuidForPath(cacheDir))
            }.onFailure { Log.w(TAG, "getAllocatableBytes unavailable; using usableSpace", it) }
        }
        return runCatching { cacheDir.usableSpace }.getOrDefault(-1L)
    }

    /** Asks the system to actually free [bytes] by evicting cached data. Best effort. */
    private fun tryReserve(bytes: Long) {
        val manager = storageManager ?: return
        runCatching { manager.allocateBytes(manager.getUuidForPath(cacheDir), bytes) }
    }

    /**
     * How much bitmap this app may reasonably hold, in bytes.
     *
     * Taken from the activity manager rather than [Runtime.maxMemory], because since Android 8 a
     * bitmap's pixels live in the *native* heap — the Java heap figures describe a pool this app
     * barely touches and would wave through an image of any size.
     */
    private val bitmapBudgetBytes: Long =
        (context.getSystemService(ActivityManager::class.java)?.largeMemoryClass
            ?: FALLBACK_MEMORY_CLASS_MB).toLong() * 1024 * 1024

    /**
     * Everything that can be judged before committing to an output: memory headroom and free
     * space. Run this *before* creating a MediaStore row, so a photo that was never going to work
     * does not leave an empty row behind.
     *
     * @return the reason to refuse, or null to proceed.
     */
    fun preflight(photo: SourcePhoto): EncodeResult.Failure? {
        if (!ensureStagingDir()) {
            return EncodeResult.Failure("no working folder to write to (storage may be full)")
        }

        val required = photo.sizeBytes + FREE_SPACE_MARGIN_BYTES
        var free = allocatableBytes()
        if (free in 0 until required) {
            // The space may exist as evictable cache; ask the system for it before giving up.
            tryReserve(required)
            free = allocatableBytes()
        }
        if (free in 0 until required) {
            return EncodeResult.Failure("not enough free storage (${free / (1024 * 1024)} MB available)")
        }

        return headroomFailure(photo)
    }

    /** Encodes into a cache file. Used by the dry run, and as the fallback path. */
    fun encodeToStaging(
        photo: SourcePhoto,
        metadata: JpegMetadata,
        quality: Int,
        rotationDegrees: Int,
        trailerStart: Long? = null,
    ): EncodeResult {
        if (!ensureStagingDir()) {
            return EncodeResult.Failure("no working folder to write to (storage may be full)")
        }
        val file = File(cacheDir, "${photo.baseName}-${System.nanoTime()}.heic")
        file.delete()
        val result = encode(photo, metadata, quality, rotationDegrees, HeicTarget.Staging(file), trailerStart)
        if (result is EncodeResult.Failure) file.delete()
        return result
    }

    /** Encodes straight into an open MediaStore file — one write instead of two. */
    fun encodeToDescriptor(
        photo: SourcePhoto,
        metadata: JpegMetadata,
        quality: Int,
        rotationDegrees: Int,
        descriptor: FileDescriptor,
        trailerStart: Long? = null,
    ): EncodeResult = encode(
        photo, metadata, quality, rotationDegrees, HeicTarget.Descriptor(descriptor), trailerStart,
    )

    /**
     * @param trailerStart when non-null, the byte offset in the *original* file where its Samsung
     *   SEF trailer begins. That trailer — the embedded motion-photo video and its metadata — is
     *   copied verbatim onto the end of the finished HEIC, which keeps the result a playable motion
     *   photo. Its offsets are self-relative, so a HEIC of a different size than the source JPG does
     *   not disturb them. See [SefTrailer].
     */
    private fun encode(
        photo: SourcePhoto,
        metadata: JpegMetadata,
        quality: Int,
        rotationDegrees: Int,
        target: HeicTarget,
        trailerStart: Long?,
    ): EncodeResult {
        val bitmap = try {
            decode(photo)
        } catch (e: OutOfMemoryError) {
            Log.e(TAG, "Out of memory decoding ${photo.displayName}", e)
            return EncodeResult.Failure("too large to decode (out of memory)")
        } catch (e: Exception) {
            Log.e(TAG, "Could not decode ${photo.displayName}", e)
            return EncodeResult.Failure("could not be decoded")
        } ?: return EncodeResult.Failure("could not be decoded")

        val width = bitmap.width
        val height = bitmap.height

        return try {
            writeHeif(target, bitmap, quality, metadata.exif, rotationDegrees)
            var bytes = sizeOf(target)
            if (bytes <= 0L) {
                return EncodeResult.Failure("encoder produced an empty file")
            }
            if (trailerStart != null) {
                val appended = appendTrailer(target, bytes, photo.uri, trailerStart)
                if (appended <= 0L) {
                    return EncodeResult.Failure("could not attach the motion photo's video")
                }
                bytes = sizeOf(target)
            }
            EncodeResult.Success(bytes, width, height, (target as? HeicTarget.Staging)?.file)
        } catch (e: Exception) {
            Log.e(TAG, "HEIC encode failed for ${photo.displayName}", e)
            // Include the message, not just the class. FileNotFoundException alone cannot
            // distinguish "no space left on device" from "the folder is gone".
            val detail = e.message?.takeIf { it.isNotBlank() } ?: e.javaClass.simpleName
            EncodeResult.Failure("HEIC encoding failed — $detail")
        } catch (e: OutOfMemoryError) {
            Log.e(TAG, "Out of memory encoding ${photo.displayName}", e)
            EncodeResult.Failure("too large to encode (out of memory)")
        } finally {
            bitmap.recycle()
        }
    }

    private fun sizeOf(target: HeicTarget): Long = when (target) {
        is HeicTarget.Staging -> target.file.length()
        // The descriptor has no path to stat, so ask the kernel about the open file itself.
        is HeicTarget.Descriptor -> runCatching { Os.fstat(target.descriptor).st_size }.getOrDefault(-1L)
    }

    /**
     * Copies the source file's SEF trailer — the motion-photo video — onto the end of the HEIC that
     * has just been written, and returns how many bytes were appended.
     *
     * The HEIC is a complete ISO-BMFF file; a compliant decoder stops at its last box and ignores
     * whatever follows, which is exactly how Samsung's own motion photos carry their video. The
     * trailer's internal offsets are relative to its own directory, so appending it after a HEIC of
     * a different size than the source JPG leaves them all valid.
     */
    private fun appendTrailer(
        target: HeicTarget,
        heicSize: Long,
        sourceUri: android.net.Uri,
        trailerStart: Long,
    ): Long = when (target) {
        is HeicTarget.Staging ->
            BufferedOutputStream(FileOutputStream(target.file, /* append = */ true)).use { out ->
                repository.copyRange(sourceUri, trailerStart, out).also { out.flush() }
            }

        is HeicTarget.Descriptor -> {
            // Position at the end of the HEIC the muxer just wrote, then stream the trailer in.
            // The FileOutputStream must not be closed — the descriptor belongs to the caller.
            Os.lseek(target.descriptor, heicSize, OsConstants.SEEK_SET)
            val out = BufferedOutputStream(FileOutputStream(target.descriptor))
            repository.copyRange(sourceUri, trailerStart, out).also { out.flush() }
        }
    }

    /**
     * Refuses up front if decoding this image would not comfortably fit in memory.
     *
     * Returns null when there is room, or when the dimensions could not be read — in that case the
     * decode is still attempted and the OutOfMemoryError catch remains the backstop.
     */
    private fun headroomFailure(photo: SourcePhoto): EncodeResult.Failure? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        try {
            repository.openOriginal(photo.uri, requireOriginal = false)
                ?.use { BitmapFactory.decodeStream(it, null, bounds) }
        } catch (e: Exception) {
            Log.w(TAG, "Could not read dimensions of ${photo.displayName}", e)
            return null
        }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

        val needed = bounds.outWidth.toLong() * bounds.outHeight * BYTES_PER_PIXEL
        return if (needed > bitmapBudgetBytes * MEMORY_SAFETY_FRACTION) {
            EncodeResult.Failure(
                "${bounds.outWidth}x${bounds.outHeight} needs ${needed / (1024 * 1024)} MB, " +
                    "more than the app can allocate"
            )
        } else {
            null
        }
    }

    /**
     * Decodes the stored pixels as-is. Orientation is not applied here: it travels in the HEIF
     * container via [writeHeif]'s rotation, and baking it into the pixels as well would turn the
     * photo twice.
     */
    private fun decode(photo: SourcePhoto): Bitmap? {
        val options = BitmapFactory.Options().apply {
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        // Redaction only strips metadata, never pixels, so the decode does not need the original.
        return repository.openOriginal(photo.uri, requireOriginal = false)
            ?.use { BitmapFactory.decodeStream(it, null, options) }
    }

    private fun writeHeif(
        target: HeicTarget,
        bitmap: Bitmap,
        quality: Int,
        exif: ByteArray?,
        rotationDegrees: Int,
    ) {
        val builder = when (target) {
            is HeicTarget.Staging ->
                HeifWriter.Builder(
                    target.file.absolutePath, bitmap.width, bitmap.height, HeifWriter.INPUT_MODE_BITMAP,
                )

            is HeicTarget.Descriptor ->
                HeifWriter.Builder(
                    target.descriptor, bitmap.width, bitmap.height, HeifWriter.INPUT_MODE_BITMAP,
                )
        }

        // Grid mode (on by default) tiles the image for the HEVC encoder, which is what lets a
        // full-resolution phone photo through at all.
        builder
            .setQuality(quality)
            .setMaxImages(1)
            .setPrimaryIndex(0)
            .setGridEnabled(true)
            // Becomes MediaMuxer.setOrientationHint, which is the rotation Android's HEIF
            // decoder actually applies. The EXIF copy has its Orientation zeroed to match.
            .setRotation(rotationDegrees)
            .build()
            .use { writer ->
                writer.start()
                if (exif != null) writer.addExifData(0, exif, 0, exif.size)
                writer.addBitmap(bitmap)
                writer.stop(STOP_TIMEOUT_MS)
            }
    }

    /** Clears anything a crashed or cancelled run left behind. */
    fun clearStaging() {
        runCatching { cacheDir.listFiles()?.forEach { it.delete() } }
    }

    /** Space this app can obtain on the staging volume, or -1 if it cannot be read. */
    fun freeStagingBytes(): Long = allocatableBytes()

    companion object {
        private const val TAG = "HeicEncoder"
        private const val STOP_TIMEOUT_MS = 60_000L

        /** ARGB_8888. */
        private const val BYTES_PER_PIXEL = 4

        /** Leave room for the encoder's own buffers rather than spending the whole budget. */
        private const val MEMORY_SAFETY_FRACTION = 0.5

        /** Used only if the activity manager is unavailable; every supported device exceeds this. */
        private const val FALLBACK_MEMORY_CLASS_MB = 128

        /** Headroom required beyond the output itself, so the device is not driven to zero. */
        private const val FREE_SPACE_MARGIN_BYTES = 64L * 1024 * 1024
    }
}
