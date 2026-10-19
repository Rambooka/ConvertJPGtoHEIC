package com.example.convertjpgtoheic

/**
 * Splits a FLAC file into its audio frames, for the decoder, without the platform's extractor.
 *
 * Samsung's extractor (`SFLACExtractor`, which Android picks for FLAC on this phone over its own)
 * ends the stream early — by 0.4 to 47 seconds a song, varying from run to run — and stamps every
 * frame at time 0. So the converter finds the frames itself. A frame starts with a sync code and a
 * header carrying its own CRC-8 and its frame or sample number, and ends with a CRC-16 over the
 * whole frame: a boundary is only taken where the next header checks out *and* the frame before it
 * does, which a sync pattern turning up by chance inside the audio will not.
 *
 * Pure Kotlin over a [ByteSource], so it can be tested without a device.
 */
object FlacFrames {

    class FormatException(message: String) : Exception(message)

    /** The STREAMINFO block: the stream's shape, and [raw], its 34 bytes as stored. */
    class StreamInfo(val raw: ByteArray) {
        val minBlockSize: Int = be(raw, 0, 2).toInt()
        val maxBlockSize: Int = be(raw, 2, 2).toInt()
        val maxFrameSize: Int = be(raw, 7, 3).toInt()
        val sampleRate: Int = (be(raw, 10, 3) ushr 4).toInt()
        val channels: Int = ((raw[12].toInt() ushr 1) and 0x07) + 1
        val bitsPerSample: Int = (((raw[12].toInt() and 0x01) shl 4) or ((raw[13].toInt() and 0xFF) ushr 4)) + 1
        /** Samples per channel in the whole stream; 0 if the encoder did not know. */
        val totalSamples: Long = be(raw, 13, 5) and 0xFFFFFFFFFL
    }

    class Frame(val offset: Long, val length: Int, val firstSample: Long, val blockSize: Int)

    /** STREAMINFO, and where the first audio frame starts; null if [src] is not FLAC. */
    fun open(src: ByteSource): Pair<StreamInfo, Long>? {
        if (src.size < 42 || !FlacTags.isFlac(src.read(0, 4))) return null
        var position = 4L
        var info: StreamInfo? = null
        while (position + 4 <= src.size) {
            val header = src.read(position, 4)
            val last = header[0].toInt() and 0x80 != 0
            val type = header[0].toInt() and 0x7F
            val length = be(header, 1, 3)
            position += 4
            if (position + length > src.size) return null
            if (type == 0 && length >= 34) info = StreamInfo(src.read(position, 34))
            position += length
            if (last) break
        }
        return (info ?: return null) to position
    }

    /** What the decoder is configured with: the `fLaC` marker and STREAMINFO, marked as the last block. */
    fun codecConfig(info: StreamInfo): ByteArray =
        "fLaC".toByteArray(Charsets.ISO_8859_1) + byteArrayOf(0x80.toByte(), 0, 0, 34) + info.raw

    /** Reads frames one after another from [start]. */
    class Reader(private val src: ByteSource, private val info: StreamInfo, start: Long) {
        private var position = start
        private var window = ByteArray(0)
        private var windowStart = 0L

        /** The next frame, or null at the end of the stream. */
        fun next(): Frame? {
            if (position >= src.size) return null
            var span = maxOf(INITIAL_WINDOW, info.maxFrameSize * 2 + HEADER_MAX)
            while (true) {
                load(position, span)
                val avail = (window.size - (position - windowStart)).toInt()
                val at = (position - windowStart).toInt()
                val header = header(window, at, avail, info)
                    ?: throw FormatException("no frame header at byte $position")
                val first = header.firstSample(info)
                val expectedNext = first + header.blockSize
                val atEnd = windowStart + window.size >= src.size

                var q = at + header.length + 2
                while (q + 1 < window.size) {
                    if (window[q] == 0xFF.toByte() && window[q + 1].toInt() and 0xFE == 0xF8) {
                        val next = header(window, q, window.size - q, info)
                        if (next != null && next.variable == header.variable &&
                            next.firstSample(info) == expectedNext && crc16Matches(window, at, q)
                        ) {
                            return frame(at, q, first, header.blockSize)
                        }
                    }
                    q++
                }
                if (atEnd) {
                    // The last frame runs to the end of the file, or to an ID3v1 tag after it.
                    if (crc16Matches(window, at, window.size)) return frame(at, window.size, first, header.blockSize)
                    val tag = window.size - 128
                    if (tag > at && String(window, tag, 3, Charsets.ISO_8859_1) == "TAG" && crc16Matches(window, at, tag)) {
                        return frame(at, tag, first, header.blockSize).also { position = src.size }
                    }
                    throw FormatException("the frame at byte $position is damaged")
                }
                if (span >= MAX_WINDOW) throw FormatException("no frame end within ${MAX_WINDOW / 1024 / 1024} MB of byte $position")
                span *= 2
            }
        }

        private fun frame(from: Int, until: Int, first: Long, blockSize: Int): Frame {
            val frame = Frame(windowStart + from, until - from, first, blockSize)
            position = windowStart + until
            return frame
        }

        /** Makes [window] hold at least [span] bytes from [from], or as many as the file has. */
        private fun load(from: Long, span: Int) {
            val wanted = minOf(span.toLong(), src.size - from).toInt()
            if (from >= windowStart && from + wanted <= windowStart + window.size) return
            val length = maxOf(wanted, minOf(READ_AHEAD.toLong(), src.size - from).toInt())
            window = src.read(from, length)
            windowStart = from
        }

        /** The bytes of [frame], for the decoder. */
        fun bytes(frame: Frame): ByteArray {
            val at = frame.offset - windowStart
            if (at >= 0 && at + frame.length <= window.size) return window.copyOfRange(at.toInt(), at.toInt() + frame.length)
            return src.read(frame.offset, frame.length)
        }
    }

    // region frame header

    private class Header(val length: Int, val blockSize: Int, val number: Long, val variable: Boolean) {
        fun firstSample(info: StreamInfo): Long = if (variable) number else number * info.maxBlockSize
    }

    /** The frame header at [at], if it is one and agrees with [info]; null otherwise. */
    private fun header(b: ByteArray, at: Int, avail: Int, info: StreamInfo): Header? {
        if (avail < 6) return null
        if (b[at] != 0xFF.toByte() || b[at + 1].toInt() and 0xFE != 0xF8) return null
        val variable = b[at + 1].toInt() and 0x01 != 0
        val blockCode = (b[at + 2].toInt() and 0xFF) ushr 4
        val rateCode = b[at + 2].toInt() and 0x0F
        val channelCode = (b[at + 3].toInt() and 0xFF) ushr 4
        val sizeCode = (b[at + 3].toInt() ushr 1) and 0x07
        if (b[at + 3].toInt() and 0x01 != 0 || blockCode == 0 || rateCode == 15 || channelCode > 10) return null
        if (sizeCode == 3 || sizeCode == 7) return null // reserved
        val channels = if (channelCode < 8) channelCode + 1 else 2
        if (channels != info.channels) return null
        val bits = when (sizeCode) { 0 -> info.bitsPerSample; 1 -> 8; 2 -> 12; 4 -> 16; 5 -> 20; else -> 24 }
        if (bits != info.bitsPerSample) return null

        // The frame or sample number, UTF-8 style: up to 7 bytes for 36 bits.
        var p = at + 4
        val lead = b[p].toInt() and 0xFF
        val extra = when {
            lead and 0x80 == 0 -> 0
            lead and 0xE0 == 0xC0 -> 1
            lead and 0xF0 == 0xE0 -> 2
            lead and 0xF8 == 0xF0 -> 3
            lead and 0xFC == 0xF8 -> 4
            lead and 0xFE == 0xFC -> 5
            lead == 0xFE -> 6
            else -> return null
        }
        if (p + 1 + extra + 4 > at + avail) return null
        // The lead byte keeps 7 bits when alone, else 0xFF >> (extra + 2): 5 bits for 110xxxxx...
        var number = when (extra) {
            0 -> (lead and 0x7F).toLong()
            6 -> 0L
            else -> (lead and (0xFF ushr (extra + 2))).toLong()
        }
        for (i in 1..extra) {
            val c = b[p + i].toInt() and 0xFF
            if (c and 0xC0 != 0x80) return null
            number = (number shl 6) or (c and 0x3F).toLong()
        }
        p += 1 + extra

        val blockSize = when (blockCode) {
            1 -> 192
            in 2..5 -> 576 shl (blockCode - 2)
            6 -> (b[p++].toInt() and 0xFF) + 1
            7 -> (be(b, p, 2).toInt() + 1).also { p += 2 }
            else -> 256 shl (blockCode - 8)
        }
        val rate = when (rateCode) {
            0 -> info.sampleRate
            1 -> 88_200; 2 -> 176_400; 3 -> 192_000; 4 -> 8_000; 5 -> 16_000; 6 -> 22_050
            7 -> 24_000; 8 -> 32_000; 9 -> 44_100; 10 -> 48_000; 11 -> 96_000
            12 -> (b[p++].toInt() and 0xFF) * 1000
            13 -> be(b, p, 2).toInt().also { p += 2 }
            else -> (be(b, p, 2).toInt() * 10).also { p += 2 }
        }
        if (rate != info.sampleRate || blockSize > info.maxBlockSize) return null
        if (p >= at + avail) return null
        if (crc8(b, at, p) != (b[p].toInt() and 0xFF)) return null
        return Header(p + 1 - at, blockSize, number, variable)
    }

    // endregion

    // region checksums

    /** CRC-8, polynomial 0x07, over [from, until). */
    internal fun crc8(b: ByteArray, from: Int, until: Int): Int {
        var crc = 0
        for (i in from until until) crc = CRC8[(crc xor (b[i].toInt() and 0xFF)) and 0xFF]
        return crc
    }

    /** Whether the last two bytes of [from, until) are the CRC-16 (polynomial 0x8005) of the rest. */
    private fun crc16Matches(b: ByteArray, from: Int, until: Int): Boolean {
        if (until - from < 8) return false
        var crc = 0
        for (i in from until until - 2) crc = ((crc shl 8) xor CRC16[((crc ushr 8) xor (b[i].toInt() and 0xFF)) and 0xFF]) and 0xFFFF
        return crc == be(b, until - 2, 2).toInt()
    }

    private val CRC8 = IntArray(256) { i ->
        var c = i
        repeat(8) { c = if (c and 0x80 != 0) ((c shl 1) xor 0x07) and 0xFF else (c shl 1) and 0xFF }
        c
    }

    private val CRC16 = IntArray(256) { i ->
        var c = i shl 8
        repeat(8) { c = if (c and 0x8000 != 0) ((c shl 1) xor 0x8005) and 0xFFFF else (c shl 1) and 0xFFFF }
        c
    }

    // endregion

    private fun be(b: ByteArray, at: Int, n: Int): Long {
        var v = 0L
        for (i in 0 until n) v = (v shl 8) or (b[at + i].toLong() and 0xFF)
        return v
    }

    /** Bytes beyond the largest frame a frame-end search looks at first, and how far it may widen. */
    private const val HEADER_MAX = 16
    private const val INITIAL_WINDOW = 64 * 1024
    private const val MAX_WINDOW = 16 * 1024 * 1024

    /** Read in big slices, so most frames are found without touching the file again. */
    private const val READ_AHEAD = 1024 * 1024
}
