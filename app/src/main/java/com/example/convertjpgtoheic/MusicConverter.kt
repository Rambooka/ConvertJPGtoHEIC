package com.example.convertjpgtoheic

import android.content.Context
import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.media.MediaMuxer
import android.net.Uri
import android.os.SystemClock
import android.util.Log
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs
import kotlin.math.roundToInt

/** A song in the music library. */
data class SourceTrack(
    val uri: Uri,
    val displayName: String,
    val relativePath: String,
    val volumeName: String,
    val sizeBytes: Long,
    val durationMs: Long,
    /** Bits per second, as MediaStore reports it; null when it does not know. */
    val bitrate: Long?,
) {
    /** The converted file's name. It differs from the MP3's by extension, so both can sit side by side. */
    val opusName: String get() = PhotoNaming.baseName(displayName) + ".opus"
}

/**
 * Re-encodes an MP3 as Opus in an Ogg file, named `.opus`.
 *
 * `.opus` rather than `.ogg`, although both are the same Ogg container: tag editors built on
 * jaudiotagger (Pulsar+, for one) take an `.ogg` to be Vorbis and refuse to open an Opus one
 * ("Invalid Identification header for this Ogg File"), while they read and write `.opus` as Opus.
 * RFC 7845 recommends `.opus` for this format in any case.
 *
 * Opus rather than Vorbis because Android has no Vorbis encoder; Opus is the newer codec and
 * needs fewer bits for the same sound. The pipeline is the platform's: MediaExtractor and the MP3
 * decoder, [Resampler] to 48 kHz (the Opus encoder takes nothing in between), the Opus encoder and
 * MediaMuxer's Ogg writer. The MP3's ID3 tags are then written into the file with [OggOpus.retag],
 * and the result is read back by the platform's own parsers before it is offered back: it has to
 * be Opus, run as long as the original, and show the same title, artist, album and cover.
 */
class MusicConverter(private val context: Context) {

    sealed interface Outcome {
        data class Converted(val bytes: Long) : Outcome
        data class Failed(val reason: String) : Outcome
    }

    /** A reason this song cannot be converted, carried out of the encode loop. */
    private class Problem(message: String) : Exception(message)

    /**
     * Converts [track] into [out], using [scratch] for the untagged intermediate.
     *
     * @param onProgress fraction 0–1 of this song done.
     */
    fun convert(track: SourceTrack, out: File, scratch: File, onProgress: (Float) -> Unit): Outcome {
        out.delete()
        scratch.delete()
        try {
            val tags = readTags(track.uri)
            val sourceUs = encode(track, scratch, onProgress)

            val comments = tags.comments + tags.pictures.map(OggOpus::pictureComment)
            scratch.inputStream().buffered().use { input ->
                out.outputStream().buffered().use { output -> OggOpus.retag(input, output, comments) }
            }
            verify(out, tags, sourceUs)?.let { throw Problem(it) }
            return Outcome.Converted(out.length())
        } catch (e: Problem) {
            out.delete()
            return Outcome.Failed(e.message ?: "unknown")
        } catch (e: Exception) {
            Log.w(TAG, "Could not convert ${track.displayName}", e)
            out.delete()
            return Outcome.Failed("${e.javaClass.simpleName}: ${e.message ?: "no detail"}")
        } finally {
            scratch.delete()
        }
    }

    // region tags

    private fun readTags(uri: Uri): Id3Tags.Tags {
        val resolver = context.contentResolver
        val input = resolver.openInputStream(uri) ?: throw Problem("it could not be opened")
        val v2 = input.use {
            val header = it.readNBytesCompat(10)
            val length = Id3Tags.v2Length(header)
            when {
                length == 0 -> null // no ID3v2 tag: perfectly ordinary
                length > MAX_TAG_BYTES -> throw Problem("its tags are implausibly large (${length / 1024 / 1024} MB)")
                else -> header + it.readNBytesCompat(length - header.size)
            }
        }
        val v1 = resolver.openFileDescriptor(uri, "r")?.use { pfd ->
            val size = pfd.statSize
            if (size < 128) return@use null
            val tail = ByteArray(128)
            var done = 0
            while (done < tail.size) {
                val n = android.system.Os.pread(pfd.fileDescriptor, tail, done, tail.size - done, size - 128 + done)
                if (n <= 0) return@use null
                done += n
            }
            tail
        }
        return Id3Tags.parse(v2, v1)
    }

    private fun java.io.InputStream.readNBytesCompat(n: Int): ByteArray {
        val out = ByteArray(n)
        var done = 0
        while (done < n) {
            val r = read(out, done, n - done)
            if (r < 0) return out.copyOf(done)
            done += r
        }
        return out
    }

    // endregion

    // region encode

    /**
     * Decodes, resamples and encodes [track] into the Ogg file [out].
     *
     * Synchronous MediaCodec, all on the calling thread, so nothing is left running if this throws.
     * Unlike HeifWriter's `addBitmap` no call here blocks, so a stalled codec is noticed
     * ([STALL_MS]) rather than wedging the run.
     *
     * @return the source's decoded length in microseconds, to check the result against.
     */
    private fun encode(track: SourceTrack, out: File, onProgress: (Float) -> Unit): Long {
        val extractor = MediaExtractor()
        var decoder: MediaCodec? = null
        var encoder: MediaCodec? = null
        var muxer: MediaMuxer? = null
        var muxerStarted = false
        try {
            context.contentResolver.openFileDescriptor(track.uri, "r")?.use { extractor.setDataSource(it.fileDescriptor) }
                ?: throw Problem("it could not be opened")
            val index = (0 until extractor.trackCount).firstOrNull {
                extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
            } ?: throw Problem("it has no sound track")
            extractor.selectTrack(index)
            val inFormat = extractor.getTrackFormat(index)
            val mime = inFormat.getString(MediaFormat.KEY_MIME)!!
            if (mime != MediaFormat.MIMETYPE_AUDIO_MPEG) throw Problem("it is $mime, not MP3")
            val expectedUs = if (inFormat.containsKey(MediaFormat.KEY_DURATION)) {
                inFormat.getLong(MediaFormat.KEY_DURATION)
            } else {
                track.durationMs * 1000
            }

            val dec = MediaCodec.createDecoderByType(mime).also { decoder = it }
            dec.configure(inFormat, null, null, 0)
            dec.start()

            val info = MediaCodec.BufferInfo()
            val pcm = PcmQueue()
            var resampler: Resampler? = null
            var channels = 0
            var inRate = 0
            var decodedFrames = 0L
            var queuedFrames = 0L
            var muxerTrack = -1
            var extractorDone = false
            var decoderDone = false
            var encoderInputDone = false
            var encoderDone = false
            var lastActivity = SystemClock.elapsedRealtime()
            var lastProgress = 0L

            fun setUp(format: MediaFormat) {
                val rate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                val ch = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                val encoding = if (format.containsKey(MediaFormat.KEY_PCM_ENCODING)) {
                    format.getInteger(MediaFormat.KEY_PCM_ENCODING)
                } else {
                    AudioFormat.ENCODING_PCM_16BIT
                }
                if (encoder != null) {
                    if (rate != inRate || ch != channels) throw Problem("its sample rate or channels change part-way through")
                    return
                }
                if (encoding != AudioFormat.ENCODING_PCM_16BIT) throw Problem("the decoder gave PCM encoding $encoding")
                if (ch !in 1..2) throw Problem("it has $ch channels")
                channels = ch
                inRate = rate
                resampler = if (rate == OPUS_RATE) null else Resampler(rate, OPUS_RATE, ch)
                val bitrate = targetBitrate(track, ch)
                val format48 = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_OPUS, OPUS_RATE, ch).apply {
                    setInteger(MediaFormat.KEY_BIT_RATE, bitrate)
                    setInteger(MediaFormat.KEY_COMPLEXITY, OPUS_COMPLEXITY)
                }
                val enc = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_OPUS).also { encoder = it }
                enc.configure(format48, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
                enc.start()
            }

            // Each stage takes everything that is ready without waiting; only when nothing at all
            // moved does the loop pause. Waiting inside each stage instead would cap the whole
            // pipeline at one buffer per stage per wait.
            while (!encoderDone) {
                var moved = false

                // Compressed MP3 frames into the decoder.
                while (!extractorDone) {
                    val i = dec.dequeueInputBuffer(0)
                    if (i < 0) break
                    val n = extractor.readSampleData(dec.getInputBuffer(i)!!, 0)
                    if (n < 0) {
                        dec.queueInputBuffer(i, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                        extractorDone = true
                    } else {
                        dec.queueInputBuffer(i, 0, n, extractor.sampleTime, 0)
                        extractor.advance()
                    }
                    moved = true
                }

                // PCM out of the decoder, resampled, into the queue — unless the encoder is behind.
                while (!decoderDone && pcm.size < MAX_PENDING_BYTES) {
                    val i = dec.dequeueOutputBuffer(info, 0)
                    if (i == MediaCodec.INFO_TRY_AGAIN_LATER) break
                    moved = true
                    if (i == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                        setUp(dec.outputFormat)
                    } else if (i >= 0) {
                        if (encoder == null) setUp(dec.outputFormat)
                        if (info.size > 0) {
                            val buf = dec.getOutputBuffer(i)!!
                            buf.position(info.offset).limit(info.offset + info.size)
                            val frames = info.size / (2 * channels)
                            decodedFrames += frames
                            val rs = resampler
                            if (rs == null) {
                                pcm.write(buf, frames * 2 * channels)
                            } else {
                                val shorts = ShortArray(frames * channels)
                                buf.order(ByteOrder.nativeOrder()).asShortBuffer().get(shorts)
                                pcm.write(toPcm16(rs.process(FloatArray(shorts.size) { shorts[it] / 32768f }, frames)))
                            }
                        }
                        dec.releaseOutputBuffer(i, false)
                        if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                            decoderDone = true
                            resampler?.let { pcm.write(toPcm16(it.flush())) }
                        }
                    }
                }

                val enc = encoder
                if (enc == null) {
                    if (decoderDone) throw Problem("no sound could be decoded from it")
                } else {
                    // PCM from the queue into the encoder; end of stream once everything is in.
                    while (!encoderInputDone && (pcm.size > 0 || decoderDone)) {
                        val i = enc.dequeueInputBuffer(0)
                        if (i < 0) break
                        val frameBytes = 2 * channels
                        val buf = enc.getInputBuffer(i)!!
                        buf.clear()
                        val n = minOf(pcm.size, buf.remaining()) / frameBytes * frameBytes
                        val ptsUs = queuedFrames * 1_000_000 / OPUS_RATE
                        if (n == 0 && decoderDone) {
                            enc.queueInputBuffer(i, 0, 0, ptsUs, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            encoderInputDone = true
                        } else {
                            pcm.read(buf, n)
                            queuedFrames += n / frameBytes
                            enc.queueInputBuffer(i, 0, n, ptsUs, 0)
                        }
                        moved = true
                    }

                    // Opus packets out of the encoder into the Ogg file.
                    while (!encoderDone) {
                        val o = enc.dequeueOutputBuffer(info, 0)
                        if (o == MediaCodec.INFO_TRY_AGAIN_LATER) break
                        moved = true
                        if (o == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                            if (muxerStarted) throw Problem("the encoder changed format part-way through")
                            val mux = MediaMuxer(out.path, MediaMuxer.OutputFormat.MUXER_OUTPUT_OGG).also { muxer = it }
                            muxerTrack = mux.addTrack(enc.outputFormat)
                            mux.start()
                            muxerStarted = true
                        } else if (o >= 0) {
                            val isConfig = info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0
                            if (!isConfig && info.size > 0) {
                                if (!muxerStarted) throw Problem("the encoder sent sound before its format")
                                val buf = enc.getOutputBuffer(o)!!
                                buf.position(info.offset).limit(info.offset + info.size)
                                muxer!!.writeSampleData(muxerTrack, buf, info)
                            }
                            enc.releaseOutputBuffer(o, false)
                            if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) encoderDone = true
                        }
                    }
                }

                val now = SystemClock.elapsedRealtime()
                if (moved) {
                    lastActivity = now
                } else if (now - lastActivity > STALL_MS) {
                    throw Problem("the encoder stopped responding")
                } else {
                    // The codecs work on their own threads; give them a moment.
                    Thread.sleep(IDLE_SLEEP_MS)
                }
                if (now - lastProgress > PROGRESS_MS && expectedUs > 0 && inRate > 0) {
                    lastProgress = now
                    onProgress((decodedFrames * 1_000_000f / inRate / expectedUs).coerceIn(0f, 1f))
                }
            }

            if (!muxerStarted) throw Problem("the encoder produced nothing")
            muxer!!.stop()
            muxerStarted = false
            return decodedFrames * 1_000_000 / inRate
        } finally {
            runCatching { if (muxerStarted) muxer?.stop() }
            runCatching { muxer?.release() }
            runCatching { encoder?.stop() }
            runCatching { encoder?.release() }
            runCatching { decoder?.stop() }
            runCatching { decoder?.release() }
            runCatching { extractor.release() }
        }
    }

    /** Float samples to 16-bit PCM in the platform's byte order, which is what MediaCodec expects. */
    private fun toPcm16(samples: FloatArray): ByteArray {
        val out = ByteBuffer.allocate(samples.size * 2).order(ByteOrder.nativeOrder())
        for (s in samples) out.putShort((s * 32768f).roundToInt().coerceIn(-32768, 32767).toShort())
        return out.array()
    }

    /**
     * A share of the MP3's bitrate. Opus matches MP3 at well under its bitrate, but a lossy
     * source re-encoded gains artefacts of its own, so this stays generous: a 128 kbps MP3 gets
     * 96 kbps, and nothing gets more than 160 kbps, which Opus treats as transparent for stereo.
     */
    private fun targetBitrate(track: SourceTrack, channels: Int): Int {
        val source = track.bitrate?.takeIf { it > 0 }
            ?: if (track.durationMs > 0) track.sizeBytes * 8 * 1000 / track.durationMs else 192_000L
        val (min, max) = if (channels == 1) MIN_BITRATE_MONO to MAX_BITRATE_MONO else MIN_BITRATE to MAX_BITRATE
        return (source * BITRATE_SHARE).toLong().coerceIn(min.toLong(), max.toLong()).toInt()
    }

    /** Growable FIFO of PCM bytes between the decoder and the encoder. */
    private class PcmQueue {
        private var data = ByteArray(64 * 1024)
        private var start = 0
        private var end = 0
        val size: Int get() = end - start

        fun write(bytes: ByteArray) = write(ByteBuffer.wrap(bytes), bytes.size)

        fun write(src: ByteBuffer, length: Int) {
            ensure(length)
            src.get(data, end, length)
            end += length
        }

        fun read(dst: ByteBuffer, length: Int) {
            dst.put(data, start, length)
            start += length
            if (start == end) {
                start = 0
                end = 0
            }
        }

        /** Makes room for [extra] more bytes at the end: compacts first, grows only if that is not enough. */
        private fun ensure(extra: Int) {
            if (end + extra <= data.size) return
            val live = size
            val target = if (live + extra <= data.size) data else ByteArray(maxOf(data.size * 2, live + extra))
            System.arraycopy(data, start, target, 0, live)
            data = target
            start = 0
            end = live
        }
    }

    // endregion

    // region verify

    /** Why [file] is not a faithful copy, or null if it is. */
    private fun verify(file: File, tags: Id3Tags.Tags, sourceUs: Long): String? {
        val extractor = MediaExtractor()
        try {
            extractor.setDataSource(file.path)
            if (extractor.trackCount != 1) return "the new file has ${extractor.trackCount} tracks"
            val format = extractor.getTrackFormat(0)
            val mime = format.getString(MediaFormat.KEY_MIME)
            if (mime != MediaFormat.MIMETYPE_AUDIO_OPUS) return "the new file is $mime, not Opus"
            if (format.containsKey(MediaFormat.KEY_DURATION)) {
                val outUs = format.getLong(MediaFormat.KEY_DURATION)
                if (abs(outUs - sourceUs) > maxOf(DURATION_SLACK_US, (sourceUs * DURATION_SLACK_FRACTION).toLong())) {
                    return "the new file runs %.2f s, the original %.2f s".format(outUs / 1e6, sourceUs / 1e6)
                }
            } else {
                return "the new file's length could not be read"
            }
        } finally {
            extractor.release()
        }

        // Read back by the platform's own tag parser — what MediaStore and the music apps will see.
        MediaMetadataRetriever().use { r ->
            r.setDataSource(file.path)
            for ((key, field, label) in CHECKED_TAGS) {
                val want = tags.first(key) ?: continue
                val got = r.extractMetadata(field)
                if (got?.trim() != want.trim()) return "the $label would be lost (read back \"$got\", not \"$want\")"
            }
            if (tags.pictures.isNotEmpty() && r.embeddedPicture == null) return "the album art would be lost"
        }
        return null
    }

    // endregion

    companion object {
        private const val TAG = "MusicConverter"

        private const val OPUS_RATE = 48_000
        private const val OPUS_COMPLEXITY = 10

        private const val BITRATE_SHARE = 0.75
        private const val MIN_BITRATE = 64_000
        private const val MAX_BITRATE = 160_000
        private const val MIN_BITRATE_MONO = 32_000
        private const val MAX_BITRATE_MONO = 96_000

        private const val IDLE_SLEEP_MS = 2L
        private const val STALL_MS = 30_000L
        private const val PROGRESS_MS = 250L

        /** Decoded PCM allowed to wait for the encoder — about 5 s of 48 kHz stereo. */
        private const val MAX_PENDING_BYTES = 1024 * 1024

        /** Far beyond any real tag, cover art included; guards against a corrupt size field. */
        private const val MAX_TAG_BYTES = 64 * 1024 * 1024

        private const val DURATION_SLACK_US = 250_000L
        private const val DURATION_SLACK_FRACTION = 0.005

        private val CHECKED_TAGS = listOf(
            Triple("TITLE", MediaMetadataRetriever.METADATA_KEY_TITLE, "title"),
            Triple("ARTIST", MediaMetadataRetriever.METADATA_KEY_ARTIST, "artist"),
            Triple("ALBUM", MediaMetadataRetriever.METADATA_KEY_ALBUM, "album"),
        )
    }
}
