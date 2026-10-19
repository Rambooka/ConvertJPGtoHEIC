package com.example.convertjpgtoheic

import android.media.MediaCodec
import android.media.MediaCodecList
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.sin

/**
 * Media made on the device for the device tests: nothing of the user's library is read or touched,
 * and nothing copyrighted needs checking in. Everything goes in the app's cache.
 */
object TestMedia {

    const val RATE = 44_100
    const val CHANNELS = 2

    /** Stereo 16-bit PCM: a 440 Hz tone left, 660 Hz right, at half scale. */
    fun tone(seconds: Double): ByteArray {
        val frames = (seconds * RATE).toInt()
        val out = ByteBuffer.allocate(frames * CHANNELS * 2).order(ByteOrder.LITTLE_ENDIAN)
        for (i in 0 until frames) {
            out.putShort((sin(2 * PI * 440 * i / RATE) * 16000).toInt().toShort())
            out.putShort((sin(2 * PI * 660 * i / RATE) * 16000).toInt().toShort())
        }
        return out.array()
    }

    fun hasEncoder(mime: String): Boolean =
        MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos.any { it.isEncoder && it.supportedTypes.any { t -> t.equals(mime, true) } }

    /** An AAC song in an MP4, as an `.m4a` from iTunes is. */
    fun aacM4a(file: File, seconds: Double) {
        val format = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, RATE, CHANNELS).apply {
            setInteger(MediaFormat.KEY_BIT_RATE, 192_000)
            setInteger(MediaFormat.KEY_AAC_PROFILE, android.media.MediaCodecInfo.CodecProfileLevel.AACObjectLC)
        }
        val muxer = MediaMuxer(file.path, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        var track = -1
        encode(format, tone(seconds), onFormat = { track = muxer.addTrack(it); muxer.start() }) { buf, info ->
            if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0) muxer.writeSampleData(track, buf, info)
        }
        muxer.stop()
        muxer.release()
    }

    /**
     * A FLAC file: the encoder's configuration (the `fLaC` marker and metadata blocks) and then its
     * frames, written straight out — the FLAC encoder's output is the file format itself.
     */
    fun flac(file: File, seconds: Double) {
        val format = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_FLAC, RATE, CHANNELS).apply {
            setInteger(MediaFormat.KEY_FLAC_COMPRESSION_LEVEL, 5)
        }
        FileOutputStream(file).use { out ->
            encode(format, tone(seconds), onFormat = {}) { buf, _ ->
                val bytes = ByteArray(buf.remaining())
                buf.get(bytes)
                out.write(bytes)
            }
        }
    }

    /** Runs [pcm] through an encoder for [format], handing every output buffer to [sink]. */
    private fun encode(
        format: MediaFormat,
        pcm: ByteArray,
        onFormat: (MediaFormat) -> Unit,
        sink: (ByteBuffer, MediaCodec.BufferInfo) -> Unit,
    ) {
        val codec = MediaCodec.createEncoderByType(format.getString(MediaFormat.KEY_MIME)!!)
        codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        codec.start()
        val info = MediaCodec.BufferInfo()
        var offset = 0
        var inputDone = false
        var outputDone = false
        val frameBytes = CHANNELS * 2
        try {
            while (!outputDone) {
                if (!inputDone) {
                    val i = codec.dequeueInputBuffer(10_000)
                    if (i >= 0) {
                        val buf = codec.getInputBuffer(i)!!
                        val n = minOf(buf.remaining() / frameBytes * frameBytes, pcm.size - offset)
                        val pts = offset.toLong() / frameBytes * 1_000_000 / RATE
                        if (n <= 0) {
                            codec.queueInputBuffer(i, 0, 0, pts, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputDone = true
                        } else {
                            buf.put(pcm, offset, n)
                            codec.queueInputBuffer(i, 0, n, pts, 0)
                            offset += n
                        }
                    }
                }
                val o = codec.dequeueOutputBuffer(info, 10_000)
                when {
                    o == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> onFormat(codec.outputFormat)
                    o >= 0 -> {
                        val buf = codec.getOutputBuffer(o)!!
                        buf.position(info.offset).limit(info.offset + info.size)
                        if (info.size > 0) sink(buf, info)
                        codec.releaseOutputBuffer(o, false)
                        if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) outputDone = true
                    }
                }
            }
        } finally {
            codec.stop()
            codec.release()
        }
    }

    /** The audio track's length as the platform reads it, in milliseconds. */
    fun durationMs(file: File): Long {
        val extractor = MediaExtractor()
        try {
            extractor.setDataSource(file.path)
            return extractor.getTrackFormat(0).getLong(MediaFormat.KEY_DURATION) / 1000
        } finally {
            extractor.release()
        }
    }
}
