package com.example.convertjpgtoheic

/**
 * Reads the parts of an MP4 that decide whether and how a video can be converted, and patches the
 * capture time into a converted one.
 *
 * Pure Kotlin over a [ByteSource], like [HeifExif], so it can be tested without a device.
 *
 * ### Why the capture time needs patching
 *
 * A video's date in the gallery comes from the `mvhd` box's creation time. Android's MediaMuxer —
 * what Media3 Transformer writes through by default — stamps the moment of *writing* there, so
 * every converted video would jump to "today", exactly the fault photos had. The creation and
 * modification times in `mvhd`, `tkhd` and `mdhd` are fixed-width fields, so they are overwritten
 * in place from the original's: no box changes size and no offset moves.
 */
object Mp4Metadata {

    /** One track, as far as conversion cares. */
    data class Track(
        /** `vide`, `soun`, ... */
        val handler: String,
        /** Sample entry type: `avc1`, `hvc1`, `mp4a`, ... */
        val codec: String?,
        /** Clockwise rotation the track matrix applies on display. */
        val rotationDegrees: Int,
        val durationSeconds: Double?,
    )

    data class Info(
        /** Seconds since 1904-01-01, as MP4 stores it; 0 when unset. */
        val creationTime: Long,
        val durationSeconds: Double?,
        val tracks: List<Track>,
        /** A `©xyz` location string in `udta`, e.g. "-36.8466+174.6200/". */
        val location: String?,
        /** Samsung's special-mode data (slow motion, hyperlapse...), as a box or trailer. */
        val hasSamsungTrailer: Boolean,
        /** Absolute positions and widths of every creation/modification time field. */
        internal val timeFields: List<Pair<Long, Int>>,
    ) {
        val video: Track? get() = tracks.firstOrNull { it.handler == "vide" }
        val audio: Track? get() = tracks.firstOrNull { it.handler == "soun" }
    }

    /** Seconds between the MP4 epoch (1904) and the Unix epoch (1970). */
    const val MP4_EPOCH_OFFSET = 2_082_844_800L

    private const val MAX_MOOV_BYTES = 64 * 1024 * 1024

    fun read(src: ByteSource): Info? {
        var position = 0L
        var moov: Pair<Long, Long>? = null
        var moovHeader = 8
        var sefd = false
        var lastEnd = 0L
        while (position + 8 <= src.size) {
            val header = src.read(position, minOf(16L, src.size - position).toInt())
            var size = be(header, 0, 4)
            val type = String(header, 4, 4, Charsets.ISO_8859_1)
            var headerSize = 8
            if (size == 1L) {
                if (header.size < 16) break
                size = be(header, 8, 8)
                headerSize = 16
            } else if (size == 0L) {
                size = src.size - position
            }
            if (size < headerSize || position + size > src.size || !type.all { it.code in 0x20..0x7E }) break
            when (type) {
                "moov" -> { moov = position to size; moovHeader = headerSize }
                "sefd" -> sefd = true
            }
            position += size
            lastEnd = position
        }
        val (moovStart, moovSize) = moov ?: return null
        if (moovSize > MAX_MOOV_BYTES) return null
        val m = src.read(moovStart, moovSize.toInt())

        val timeFields = ArrayList<Pair<Long, Int>>()
        var creation = 0L
        var movieDuration: Double? = null
        var location: String? = null
        val tracks = ArrayList<Track>()

        for (box in children(m, moovHeader, m.size)) {
            when (box.type) {
                "mvhd" -> {
                    val v = m[box.body].toInt()
                    val w = if (v == 1) 8 else 4
                    creation = be(m, box.body + 4, w)
                    timeFields += (moovStart + box.body + 4) to w
                    timeFields += (moovStart + box.body + 4 + w) to w
                    val timescale = be(m, box.body + 4 + 2 * w, 4)
                    val duration = be(m, box.body + 8 + 2 * w, w)
                    if (timescale > 0) movieDuration = duration.toDouble() / timescale
                }
                "trak" -> tracks += readTrack(m, box, moovStart, timeFields)
                "udta" -> for (u in children(m, box.body, box.end)) {
                    if (u.type == "©xyz" && u.end - u.body > 4) {
                        location = String(m, u.body + 4, u.end - u.body - 4, Charsets.ISO_8859_1)
                    }
                }
            }
        }

        // Samsung may instead append the SEF block raw, after the last box.
        val trailer = lastEnd < src.size && run {
            val take = minOf(SefTrailer.TAIL_BYTES.toLong(), src.size).toInt()
            take >= 16 && SefTrailer.parse(src.read(src.size - take, take), src.size) != null
        }

        return Info(creation, movieDuration, tracks, location, sefd || trailer, timeFields)
    }

    /** Writes that set every creation and modification time in [info]'s file to [mp4Seconds]. */
    fun timePatches(info: Info, mp4Seconds: Long): List<FileWrite> =
        info.timeFields.map { (at, width) -> FileWrite(at, toBe(mp4Seconds, width)) }

    fun toMp4Seconds(unixMs: Long): Long = unixMs / 1000 + MP4_EPOCH_OFFSET
    fun toUnixMs(mp4Seconds: Long): Long = (mp4Seconds - MP4_EPOCH_OFFSET) * 1000

    private fun readTrack(m: ByteArray, trak: Box, moovStart: Long, timeFields: MutableList<Pair<Long, Int>>): Track {
        var handler = ""
        var codec: String? = null
        var rotation = 0
        var duration: Double? = null
        for (box in children(m, trak.body, trak.end)) {
            when (box.type) {
                "tkhd" -> {
                    val v = m[box.body].toInt()
                    val w = if (v == 1) 8 else 4
                    timeFields += (moovStart + box.body + 4) to w
                    timeFields += (moovStart + box.body + 4 + w) to w
                    // Matrix follows: times, track id, reserved, duration, reserved x2, layer,
                    // alternate group, volume, reserved.
                    val matrix = box.body + 4 + 2 * w + 4 + 4 + w + 8 + 2 + 2 + 2 + 2
                    rotation = rotationOf(sbe(m, matrix), sbe(m, matrix + 4))
                }
                "mdia" -> for (md in children(m, box.body, box.end)) {
                    when (md.type) {
                        "mdhd" -> {
                            val v = m[md.body].toInt()
                            val w = if (v == 1) 8 else 4
                            timeFields += (moovStart + md.body + 4) to w
                            timeFields += (moovStart + md.body + 4 + w) to w
                            val timescale = be(m, md.body + 4 + 2 * w, 4)
                            val d = be(m, md.body + 8 + 2 * w, w)
                            if (timescale > 0) duration = d.toDouble() / timescale
                        }
                        "hdlr" -> handler = String(m, md.body + 8, 4, Charsets.ISO_8859_1)
                        "minf" -> codec = sampleEntry(m, md)
                    }
                }
            }
        }
        return Track(handler, codec, rotation, duration)
    }

    private fun sampleEntry(m: ByteArray, minf: Box): String? {
        val stbl = children(m, minf.body, minf.end).firstOrNull { it.type == "stbl" } ?: return null
        val stsd = children(m, stbl.body, stbl.end).firstOrNull { it.type == "stsd" } ?: return null
        val first = stsd.body + 8 // version/flags, entry count
        return if (first + 8 <= stsd.end) String(m, first + 4, 4, Charsets.ISO_8859_1) else null
    }

    /** Clockwise display rotation from the matrix's a and b terms (16.16 fixed point). */
    private fun rotationOf(a: Int, b: Int): Int = when {
        a == 0 && b > 0 -> 90
        a < 0 && b == 0 -> 180
        a == 0 && b < 0 -> 270
        else -> 0
    }

    private class Box(val type: String, val start: Int, val size: Int, val headerSize: Int) {
        val body: Int get() = start + headerSize
        val end: Int get() = start + size
    }

    private fun children(b: ByteArray, from: Int, until: Int): List<Box> {
        val out = ArrayList<Box>()
        var at = from
        while (at + 8 <= until) {
            var size = be(b, at, 4)
            var headerSize = 8
            if (size == 1L) {
                if (at + 16 > until) break
                size = be(b, at + 8, 8)
                headerSize = 16
            } else if (size == 0L) {
                size = (until - at).toLong()
            }
            if (size < headerSize || at + size > until) break
            out += Box(String(b, at + 4, 4, Charsets.ISO_8859_1), at, size.toInt(), headerSize)
            at += size.toInt()
        }
        return out
    }

    private fun be(b: ByteArray, at: Int, n: Int): Long {
        var v = 0L
        for (i in 0 until n) v = (v shl 8) or (b[at + i].toLong() and 0xFF)
        return v
    }

    private fun sbe(b: ByteArray, at: Int): Int = be(b, at, 4).toInt()

    private fun toBe(value: Long, n: Int): ByteArray = ByteArray(n) { i -> (value shr (8 * (n - 1 - i))).toByte() }
}
