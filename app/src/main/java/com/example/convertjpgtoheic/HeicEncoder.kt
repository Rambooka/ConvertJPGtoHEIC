package com.example.convertjpgtoheic

import android.app.ActivityManager
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.GLES20
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
        /** The image was shrunk to fit the encoder, so [width]x[height] is smaller than the source. */
        val downscaled: Boolean = false,
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

    private val activityManager = context.getSystemService(ActivityManager::class.java)

    /**
     * How much bitmap this app may reasonably hold *right now*, in bytes.
     *
     * Based on the memory actually free on the device, not [ActivityManager.largeMemoryClass]: since
     * Android 8 a bitmap's pixels live in the *native* heap, while largeMemoryClass describes the
     * Java heap — a pool bitmaps never touch. That figure (512 MB on a 12 GB phone) wrongly refuses
     * an 88-megapixel panorama the device has gigabytes of room to decode. `availMem` is the real
     * ceiling; the system's low-memory threshold is held back so a large decode does not trip the
     * low-memory killer, and the largeMemoryClass is kept as a floor so a briefly busy device still
     * lets an ordinary photo through.
     */
    private fun bitmapBudgetBytes(): Long {
        val manager = activityManager
            ?: return FALLBACK_MEMORY_CLASS_MB.toLong() * 1024 * 1024
        val info = ActivityManager.MemoryInfo()
        manager.getMemoryInfo(info)
        val available = ((info.availMem - info.threshold) * MEMORY_SAFETY_FRACTION).toLong()
        val floor = manager.largeMemoryClass.toLong() * 1024 * 1024
        return maxOf(floor, available)
    }

    /**
     * The largest image side this device can encode, in pixels.
     *
     * [HeifWriter] uploads the bitmap as a single OpenGL texture and blits tiles out of it, so an
     * image wider or taller than the GPU's `GL_MAX_TEXTURE_SIZE` fails inside `addBitmap` with
     * `GL_INVALID_VALUE` (0x501) — which is exactly what a ~23,000 px panorama does. Queried once
     * from a throwaway EGL context; a device that will not answer falls back to the ES 3.0 floor.
     */
    val maxEncodableDimension: Int by lazy { queryMaxTextureSize() }

    private fun queryMaxTextureSize(): Int {
        val display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
        if (display == EGL14.EGL_NO_DISPLAY) return SAFE_MAX_TEXTURE
        val version = IntArray(2)
        if (!EGL14.eglInitialize(display, version, 0, version, 1)) return SAFE_MAX_TEXTURE
        try {
            val configs = arrayOfNulls<EGLConfig>(1)
            val numConfig = IntArray(1)
            val configAttribs = intArrayOf(
                EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
                EGL14.EGL_SURFACE_TYPE, EGL14.EGL_PBUFFER_BIT,
                EGL14.EGL_NONE,
            )
            if (!EGL14.eglChooseConfig(display, configAttribs, 0, configs, 0, 1, numConfig, 0) ||
                numConfig[0] <= 0 || configs[0] == null
            ) {
                return SAFE_MAX_TEXTURE
            }
            val context = EGL14.eglCreateContext(
                display, configs[0], EGL14.EGL_NO_CONTEXT,
                intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE), 0,
            )
            if (context == EGL14.EGL_NO_CONTEXT) return SAFE_MAX_TEXTURE
            val surface = EGL14.eglCreatePbufferSurface(
                display, configs[0], intArrayOf(EGL14.EGL_WIDTH, 1, EGL14.EGL_HEIGHT, 1, EGL14.EGL_NONE), 0,
            )
            try {
                if (!EGL14.eglMakeCurrent(display, surface, surface, context)) return SAFE_MAX_TEXTURE
                val maxSize = IntArray(1)
                GLES20.glGetIntegerv(GLES20.GL_MAX_TEXTURE_SIZE, maxSize, 0)
                return if (maxSize[0] >= SAFE_MAX_TEXTURE) maxSize[0] else SAFE_MAX_TEXTURE
            } finally {
                EGL14.eglMakeCurrent(
                    display, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT,
                )
                if (surface != EGL14.EGL_NO_SURFACE) EGL14.eglDestroySurface(display, surface)
                EGL14.eglDestroyContext(display, context)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Could not query GL_MAX_TEXTURE_SIZE; assuming $SAFE_MAX_TEXTURE", e)
            return SAFE_MAX_TEXTURE
        } finally {
            EGL14.eglTerminate(display)
        }
    }

    /**
     * Everything that can be judged before committing to an output: memory headroom and free
     * space. Run this *before* creating a MediaStore row, so a photo that was never going to work
     * does not leave an empty row behind.
     *
     * @return the reason to refuse, or null to proceed.
     */
    fun preflight(photo: SourcePhoto, allowShrink: Boolean): EncodeResult.Failure? {
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

        return sizeFailure(photo, allowShrink)
    }

    /** Encodes into a cache file. Used by the dry run, and as the fallback path. */
    fun encodeToStaging(
        photo: SourcePhoto,
        metadata: JpegMetadata,
        quality: Int,
        rotationDegrees: Int,
        allowShrink: Boolean,
        trailerStart: Long? = null,
    ): EncodeResult {
        if (!ensureStagingDir()) {
            return EncodeResult.Failure("no working folder to write to (storage may be full)")
        }
        val file = File(cacheDir, "${photo.baseName}-${System.nanoTime()}.heic")
        file.delete()
        val result = encode(
            photo, metadata, quality, rotationDegrees, HeicTarget.Staging(file), allowShrink, trailerStart,
        )
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
        allowShrink: Boolean,
        trailerStart: Long? = null,
    ): EncodeResult = encode(
        photo, metadata, quality, rotationDegrees, HeicTarget.Descriptor(descriptor), allowShrink, trailerStart,
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
        allowShrink: Boolean,
        trailerStart: Long?,
    ): EncodeResult {
        val decoded = try {
            decode(photo, allowShrink)
        } catch (e: OutOfMemoryError) {
            Log.e(TAG, "Out of memory decoding ${photo.displayName}", e)
            return EncodeResult.Failure("too large to decode (out of memory)")
        } catch (e: Exception) {
            Log.e(TAG, "Could not decode ${photo.displayName}", e)
            return EncodeResult.Failure("could not be decoded")
        } ?: return EncodeResult.Failure("could not be decoded")

        val bitmap = decoded.bitmap
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
            EncodeResult.Success(bytes, width, height, (target as? HeicTarget.Staging)?.file, decoded.downscaled)
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

    /** A decoded image plus whether it had to be shrunk on the way in. */
    private class Decoded(val bitmap: Bitmap, val downscaled: Boolean)

    /**
     * Refuses up front if this image cannot be encoded — too big for memory, or (unless
     * [allowShrink]) larger than the encoder's texture limit.
     *
     * When [allowShrink] is set the decode will shrink an oversized image to fit, so there is
     * nothing to refuse here. Returns null when the image is fine, or when the dimensions could not
     * be read — in that case the decode is still attempted and the catch blocks remain the backstop.
     */
    private fun sizeFailure(photo: SourcePhoto, allowShrink: Boolean): EncodeResult.Failure? {
        if (allowShrink) return null

        val (w, h) = readDimensions(photo) ?: return null

        if (w > maxEncodableDimension || h > maxEncodableDimension) {
            return EncodeResult.Failure(
                "too large for this device's encoder (${w}x$h; limit ${maxEncodableDimension}px per " +
                    "side). Turn on \"Shrink oversized photos\" to convert it."
            )
        }
        val needed = w.toLong() * h * BYTES_PER_PIXEL
        return if (needed > bitmapBudgetBytes()) {
            EncodeResult.Failure(
                "${w}x$h needs ${needed / (1024 * 1024)} MB, more than the app can allocate"
            )
        } else {
            null
        }
    }

    /** Reads an image's pixel dimensions without decoding it, or null if they cannot be read. */
    private fun readDimensions(photo: SourcePhoto): Pair<Int, Int>? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        try {
            repository.openOriginal(photo.uri, requireOriginal = false)
                ?.use { BitmapFactory.decodeStream(it, null, bounds) }
        } catch (e: Exception) {
            Log.w(TAG, "Could not read dimensions of ${photo.displayName}", e)
            return null
        }
        return if (bounds.outWidth > 0 && bounds.outHeight > 0) {
            bounds.outWidth to bounds.outHeight
        } else {
            null
        }
    }

    /**
     * Decodes the stored pixels, shrinking the image if it is too large to encode.
     *
     * An image wider or taller than [maxEncodableDimension], or too big for the memory budget, is
     * downsampled by the smallest power of two that brings it under both limits — but only when
     * [allowShrink] is set. `inSampleSize` keeps the decode itself cheap: the large bitmap is never
     * held in full. Orientation is not applied here: it travels in the HEIF container via
     * [writeHeif]'s rotation, and baking it into the pixels too would turn the photo twice.
     */
    private fun decode(photo: SourcePhoto, allowShrink: Boolean): Decoded? {
        var sampleSize = 1
        if (allowShrink) {
            val dimensions = readDimensions(photo)
            if (dimensions != null) {
                val (w, h) = dimensions
                val budget = bitmapBudgetBytes()
                // Grow the step until both the texture limit and the memory budget are satisfied.
                while (
                    w / sampleSize > maxEncodableDimension ||
                    h / sampleSize > maxEncodableDimension ||
                    (w.toLong() / sampleSize) * (h / sampleSize) * BYTES_PER_PIXEL > budget
                ) {
                    sampleSize *= 2
                }
            }
        }

        val options = BitmapFactory.Options().apply {
            inPreferredConfig = Bitmap.Config.ARGB_8888
            inSampleSize = sampleSize
        }
        // Redaction only strips metadata, never pixels, so the decode does not need the original.
        val bitmap = repository.openOriginal(photo.uri, requireOriginal = false)
            ?.use { BitmapFactory.decodeStream(it, null, options) }
            ?: return null
        return Decoded(bitmap, downscaled = sampleSize > 1)
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

        /** Spend only this share of free memory on the bitmap, leaving room for the encoder's own
         *  buffers and other apps rather than driving the device to zero. */
        private const val MEMORY_SAFETY_FRACTION = 0.6

        /** Used only if the activity manager is unavailable; every supported device exceeds this. */
        private const val FALLBACK_MEMORY_CLASS_MB = 128

        /** GL_MAX_TEXTURE_SIZE floor: the value OpenGL ES 3.0 guarantees, used if the real one
         *  cannot be queried. Every supported GPU meets at least this. */
        private const val SAFE_MAX_TEXTURE = 8192

        /** Headroom required beyond the output itself, so the device is not driven to zero. */
        private const val FREE_SPACE_MARGIN_BYTES = 64L * 1024 * 1024
    }
}
