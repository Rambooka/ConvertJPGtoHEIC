package com.example.convertjpgtoheic

import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import androidx.annotation.OptIn
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.transformer.Composition
import androidx.media3.transformer.DefaultEncoderFactory
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.ProgressHolder
import androidx.media3.transformer.Transformer
import androidx.media3.transformer.VideoEncoderSettings
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import java.io.File
import java.io.RandomAccessFile
import kotlin.math.abs

/** A video in the library, with what conversion needs to judge and convert it. */
data class SourceVideo(
    val uri: Uri,
    val displayName: String,
    val relativePath: String,
    val volumeName: String,
    val sizeBytes: Long,
    val dateTakenMs: Long?,
    val durationMs: Long,
    val bitrate: Long?,
    val captureFramerate: Double?,
    val width: Int,
    val height: Int,
    val mimeType: String?,
) {
    val baseName: String get() = PhotoNaming.baseName(displayName)

    /** What the converted copy is called while the original still exists beside it. */
    val convertedName: String get() = "${baseName}$CONVERTED_SUFFIX.mp4"

    companion object {
        const val CONVERTED_SUFFIX = "_HEVC"
    }
}

/**
 * Re-encodes an H.264 MP4 as HEVC — the video codec HEIC is built on — through Media3 Transformer
 * and the phone's hardware encoder.
 *
 * Audio is copied through untouched. The capture time is copied from the original afterwards (see
 * [Mp4Metadata]), and the result is re-read and compared with the original before it is offered
 * back: codec, duration, orientation, date and location all have to match, or it is discarded.
 */
@OptIn(UnstableApi::class)
class VideoConverter(private val context: Context) {

    enum class SkipKind {
        /** Slow motion, hyperlapse or another Samsung special mode: re-encoding would lose it. */
        SPECIAL,

        /** Already HEVC (or another codec that would gain nothing). */
        CODEC,

        /** Not an MP4 this handles — .mov, .avi, 8K... */
        FORMAT,
    }

    sealed interface Check {
        data class Convert(val info: Mp4Metadata.Info) : Check
        data class Skip(val kind: SkipKind, val reason: String) : Check
    }

    sealed interface Outcome {
        data class Converted(val bytes: Long) : Outcome
        data class Failed(val reason: String) : Outcome
    }

    /** Decides whether [video] should be converted, from MediaStore and its own MP4 boxes. */
    fun check(video: SourceVideo): Check {
        if (video.mimeType != "video/mp4") return Check.Skip(SkipKind.FORMAT, "not an MP4 (${video.mimeType})")
        val capture = video.captureFramerate
        if (capture != null && capture > MAX_NORMAL_CAPTURE_FPS) {
            return Check.Skip(SkipKind.SPECIAL, "slow motion (captured at ${capture.toInt()} fps)")
        }
        if (video.width.toLong() * video.height > MAX_PIXELS) return Check.Skip(SkipKind.FORMAT, "larger than 4K")

        val info = try {
            context.contentResolver.openFileDescriptor(video.uri, "r")?.use { pfd ->
                Mp4Metadata.read(FdByteSource(pfd.fileDescriptor, pfd.statSize))
            }
        } catch (e: Exception) {
            Log.w(TAG, "Could not read ${video.displayName}", e)
            null
        } ?: return Check.Skip(SkipKind.FORMAT, "its MP4 structure could not be read")

        if (info.hasSamsungTrailer) return Check.Skip(SkipKind.SPECIAL, "slow motion, hyperlapse or another camera mode")
        val codec = info.video?.codec ?: return Check.Skip(SkipKind.FORMAT, "no video track")
        if (codec != "avc1" && codec != "avc3") return Check.Skip(SkipKind.CODEC, "already $codec")
        if (info.tracks.count { it.handler == "vide" } > 1) return Check.Skip(SkipKind.FORMAT, "more than one video track")
        return Check.Convert(info)
    }

    /**
     * Encodes [video] into [out], then fixes and verifies it.
     *
     * @param onProgress fraction 0–1 of this video done, called every half second or so.
     */
    suspend fun convert(video: SourceVideo, source: Mp4Metadata.Info, out: File, onProgress: (Float) -> Unit): Outcome {
        out.delete()
        val bitrate = targetBitrate(video)

        val encoded = encode(video.uri, out, bitrate, onProgress)
        if (encoded != null) {
            out.delete()
            return Outcome.Failed(encoded)
        }

        // Carry the capture time across. The original's own mvhd is the truth; MediaStore's date
        // is the fallback for the rare file that records none.
        val captured = source.creationTime.takeIf { it > 0 }
            ?: video.dateTakenMs?.let { Mp4Metadata.toMp4Seconds(it) }
        try {
            RandomAccessFile(out, "rw").use { file ->
                val written = Mp4Metadata.read(RafByteSource(file)) ?: return fail(out, "the new file could not be read back")
                if (captured != null) {
                    for (w in Mp4Metadata.timePatches(written, captured)) {
                        file.seek(w.position)
                        file.write(w.bytes)
                    }
                }
                file.fd.sync()
                val problem = verify(source, Mp4Metadata.read(RafByteSource(file)), captured)
                if (problem != null) return fail(out, problem)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Could not finish ${video.displayName}", e)
            return fail(out, "the new file could not be finished — ${e.message ?: e.javaClass.simpleName}")
        }
        return Outcome.Converted(out.length())
    }

    private fun fail(out: File, reason: String): Outcome.Failed {
        out.delete()
        return Outcome.Failed(reason)
    }

    /** Why [written] is not a faithful copy of [source], or null if it is. */
    private fun verify(source: Mp4Metadata.Info, written: Mp4Metadata.Info?, captured: Long?): String? {
        written ?: return "the new file could not be read back"
        val codec = written.video?.codec
        if (codec != "hvc1" && codec != "hev1") return "the encoder produced $codec, not HEVC"
        val srcDuration = source.durationSeconds ?: source.video?.durationSeconds
        val outDuration = written.durationSeconds ?: written.video?.durationSeconds
        if (srcDuration != null && outDuration != null &&
            abs(srcDuration - outDuration) > maxOf(DURATION_SLACK_SECONDS, srcDuration * DURATION_SLACK_FRACTION)
        ) {
            return "the new file runs %.1f s, the original %.1f s".format(outDuration, srcDuration)
        }
        if ((source.audio != null) != (written.audio != null)) return "the sound track was not carried across"
        if (source.video?.rotationDegrees != written.video?.rotationDegrees) {
            return "the orientation changed (${source.video?.rotationDegrees}° → ${written.video?.rotationDegrees}°)"
        }
        if (captured != null && written.creationTime != captured) return "the capture time could not be set"
        if (source.location != null && written.location == null) return "the location would be lost"
        return null
    }

    /** Runs Transformer to completion. Returns null on success, else why it failed. */
    private suspend fun encode(uri: Uri, out: File, bitrate: Int, onProgress: (Float) -> Unit): String? {
        val thread = HandlerThread("video-encode").apply { start() }
        val handler = Handler(thread.looper)
        val done = CompletableDeferred<String?>()
        // Written on the encoder's looper, read here: a plain local would not be safely shared.
        val percent = java.util.concurrent.atomic.AtomicInteger()

        val transformer = Transformer.Builder(context)
            .setLooper(thread.looper)
            .setVideoMimeType(MimeTypes.VIDEO_H265)
            .setEncoderFactory(
                DefaultEncoderFactory.Builder(context)
                    .setRequestedVideoEncoderSettings(VideoEncoderSettings.Builder().setBitrate(bitrate).build())
                    .setEnableFallback(true)
                    .build()
            )
            .addListener(object : Transformer.Listener {
                override fun onCompleted(composition: Composition, exportResult: ExportResult) {
                    done.complete(null)
                }

                override fun onError(composition: Composition, exportResult: ExportResult, exportException: ExportException) {
                    Log.w(TAG, "Encode failed", exportException)
                    done.complete("the encoder failed — ${exportException.errorCodeName}")
                }
            })
            .build()

        val holder = ProgressHolder()
        val poll = object : Runnable {
            override fun run() {
                if (done.isCompleted) return
                if (transformer.getProgress(holder) == Transformer.PROGRESS_STATE_AVAILABLE) percent.set(holder.progress)
                handler.postDelayed(this, PROGRESS_POLL_MS)
            }
        }
        handler.post {
            try {
                transformer.start(MediaItem.fromUri(uri), out.absolutePath)
                handler.postDelayed(poll, PROGRESS_POLL_MS)
            } catch (e: Exception) {
                done.complete("the encoder could not start — ${e.message ?: e.javaClass.simpleName}")
            }
        }

        try {
            while (!done.isCompleted) {
                onProgress(percent.get() / 100f)
                delay(PROGRESS_POLL_MS)
            }
            return done.await()
        } finally {
            // Also reached on cancellation: stop the encoder rather than let it run on unobserved.
            if (!done.isCompleted) handler.post { transformer.cancel() }
            handler.post { thread.quitSafely() }
        }
    }

    /**
     * The bitrate to ask the HEVC encoder for: a share of the original's. HEVC needs roughly half
     * the bits of H.264 for the same picture; a little over half leaves headroom for the phone's
     * hardware encoder, which is quicker than it is thorough.
     */
    private fun targetBitrate(video: SourceVideo): Int {
        val source = video.bitrate?.takeIf { it > 0 }
            ?: if (video.durationMs > 0) video.sizeBytes * 8 * 1000 / video.durationMs else DEFAULT_BITRATE.toLong()
        return (source * HEVC_BITRATE_SHARE).toLong().coerceIn(MIN_BITRATE.toLong(), MAX_BITRATE.toLong()).toInt()
    }

    private class FdByteSource(private val fd: java.io.FileDescriptor, override val size: Long) : ByteSource {
        override fun read(position: Long, length: Int): ByteArray {
            val out = ByteArray(length)
            var done = 0
            while (done < length) {
                val n = android.system.Os.pread(fd, out, done, length - done, position + done)
                if (n <= 0) throw IllegalStateException("short read")
                done += n
            }
            return out
        }
    }

    private class RafByteSource(private val file: RandomAccessFile) : ByteSource {
        override val size: Long get() = file.length()
        override fun read(position: Long, length: Int): ByteArray {
            val out = ByteArray(length)
            file.seek(position)
            file.readFully(out)
            return out
        }
    }

    companion object {
        private const val TAG = "VideoConverter"

        /** Above this a video was captured for slow motion. Normal recording tops out at 60. */
        private const val MAX_NORMAL_CAPTURE_FPS = 61.0
        private const val MAX_PIXELS = 3840L * 2160

        private const val HEVC_BITRATE_SHARE = 0.55
        private const val MIN_BITRATE = 1_000_000
        private const val MAX_BITRATE = 60_000_000
        private const val DEFAULT_BITRATE = 12_000_000

        private const val DURATION_SLACK_SECONDS = 0.5
        private const val DURATION_SLACK_FRACTION = 0.02

        private const val PROGRESS_POLL_MS = 500L
    }
}
