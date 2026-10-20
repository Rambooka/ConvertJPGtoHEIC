package com.example.convertjpgtoheic

/**
 * Reads the iTunes-style tags of an MP4 audio file (`.m4a`) as Vorbis comments, the same shape
 * [Id3Tags] gives for an MP3, so the rest of the music pipeline need not know which it came from.
 *
 * The tags live in `moov/udta/meta/ilst`: one box per field (`©nam`, `©ART`, `trkn`...), each
 * holding a `data` box whose type says how to read it — 1 UTF-8, 2 UTF-16, 0 or 21 integers, 13 and
 * 14 JPEG and PNG pictures. iTunes' own `----` boxes carry a `mean` and a `name` ahead of the data;
 * those are kept by name, apart from iTunes' playback bookkeeping (iTunSMPB, iTunNORM...).
 *
 * Pure Kotlin over a [ByteSource], so it can be tested without a device.
 */
object M4aTags {

    /** Whether [header] (the file's first 8 bytes or more) opens an MP4 file. */
    fun isMp4(header: ByteArray): Boolean =
        header.size >= 8 && String(header, 4, 4, Charsets.ISO_8859_1) == "ftyp"

    /** The tags of the MP4 file [src]; empty if it has none, null if it is not one this can read. */
    fun read(src: ByteSource): Id3Tags.Tags? {
        val (start, size) = moov(src) ?: return null
        if (size > MAX_MOOV_BYTES) return null
        val m = src.read(start, size.toInt())
        val ilst = find(m, children(m, 8, m.size), "udta", "meta", "ilst")
            ?: return Id3Tags.Tags(emptyList(), emptyList())
        return parseIlst(m, ilst)
    }

    /** Where the top-level `moov` box is: often after `mdat`, so the file is walked box by box. */
    private fun moov(src: ByteSource): Pair<Long, Long>? {
        var position = 0L
        while (position + 8 <= src.size) {
            val header = src.read(position, minOf(16L, src.size - position).toInt())
            var size = be(header, 0, 4)
            val type = String(header, 4, 4, Charsets.ISO_8859_1)
            if (size == 1L) {
                if (header.size < 16) return null
                size = be(header, 8, 8)
            } else if (size == 0L) {
                size = src.size - position
            }
            if (size < 8 || position + size > src.size) return null
            if (type == "moov") return position to size
            position += size
        }
        return null
    }

    /** The box reached by following [path] down from [boxes]; `meta` is a full box, so 4 bytes more. */
    private fun find(b: ByteArray, boxes: List<Box>, vararg path: String): Box? {
        var level = boxes
        var found: Box? = null
        for ((i, type) in path.withIndex()) {
            found = level.firstOrNull { it.type == type } ?: return null
            if (i < path.lastIndex) {
                val body = found.body + if (type == "meta") 4 else 0
                level = children(b, body, found.end)
            }
        }
        return found
    }

    private fun parseIlst(m: ByteArray, ilst: Box): Id3Tags.Tags {
        val comments = ArrayList<Pair<String, String>>()
        val pictures = ArrayList<Id3Tags.Picture>()
        for (item in children(m, ilst.body, ilst.end)) {
            val parts = children(m, item.body, item.end)
            val values = parts.filter { it.type == "data" && it.size >= 16 }.map { d ->
                Value(be(m, d.body, 4).toInt() and 0xFFFFFF, m.copyOfRange(d.body + 8, d.end))
            }
            if (values.isEmpty()) continue
            when (item.type) {
                "covr" -> for (v in values) {
                    val mime = when (v.type) {
                        TYPE_JPEG -> "image/jpeg"
                        TYPE_PNG -> "image/png"
                        TYPE_BMP -> "image/bmp"
                        else -> continue
                    }
                    // iTunes has no picture types; the first is the cover it shows.
                    val type = if (pictures.isEmpty()) Id3Tags.FRONT_COVER else 0
                    pictures += Id3Tags.Picture(mime, type, "", v.bytes)
                }
                "trkn" -> pair(values[0], "TRACKNUMBER", "TRACKTOTAL", comments)
                "disk" -> pair(values[0], "DISCNUMBER", "DISCTOTAL", comments)
                "gnre" -> integer(values[0])?.let { Id3Tags.genreAt(it.toInt() - 1) }?.let { comments += "GENRE" to it }
                "tmpo" -> integer(values[0])?.takeIf { it > 0 }?.let { comments += "BPM" to it.toString() }
                "cpil" -> integer(values[0])?.takeIf { it == 1L }?.let { comments += "COMPILATION" to "1" }
                "©day" -> text(values[0])?.let { comments += "DATE" to it.substringBefore('T') }
                "----" -> {
                    val name = parts.firstOrNull { it.type == "name" && it.size >= 12 }
                        ?.let { String(m, it.body + 4, it.end - it.body - 4, Charsets.UTF_8) } ?: continue
                    if (name.startsWith("iTun")) continue
                    val key = Id3Tags.vorbisKey(name) ?: continue
                    for (v in values) text(v)?.let { comments += key to it }
                }
                else -> TEXT_KEYS[item.type]?.let { key -> for (v in values) text(v)?.let { comments += key to it } }
            }
        }
        return Id3Tags.Tags(comments, pictures)
    }

    private class Value(val type: Int, val bytes: ByteArray)

    private fun text(v: Value): String? {
        val s = when (v.type) {
            TYPE_UTF8 -> String(v.bytes, Charsets.UTF_8)
            TYPE_UTF16 -> String(v.bytes, Charsets.UTF_16BE)
            else -> return null
        }
        return s.trim('\u0000', ' ').takeIf { it.isNotEmpty() }
    }

    private fun integer(v: Value): Long? {
        if (v.type != TYPE_IMPLICIT && v.type != TYPE_INTEGER) return null
        if (v.bytes.size !in 1..8) return null
        return be(v.bytes, 0, v.bytes.size)
    }

    /** `trkn` and `disk`: 2 bytes padding, 2 number, 2 total (and 2 more padding in `trkn`). */
    private fun pair(v: Value, numberKey: String, totalKey: String, out: MutableList<Pair<String, String>>) {
        if (v.bytes.size < 6) return
        val number = be(v.bytes, 2, 2)
        val total = be(v.bytes, 4, 2)
        if (number > 0) out += numberKey to number.toString()
        if (total > 0) out += totalKey to total.toString()
    }

    // region boxes

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

    // endregion

    /** Far beyond any real `moov`, cover art included; guards against a corrupt size field. */
    private const val MAX_MOOV_BYTES = 64L * 1024 * 1024

    private const val TYPE_IMPLICIT = 0
    private const val TYPE_UTF8 = 1
    private const val TYPE_UTF16 = 2
    private const val TYPE_JPEG = 13
    private const val TYPE_PNG = 14
    private const val TYPE_INTEGER = 21
    private const val TYPE_BMP = 27

    /** iTunes text atoms to Vorbis field names, following MusicBrainz Picard's mapping. */
    private val TEXT_KEYS = mapOf(
        "©nam" to "TITLE",
        "©ART" to "ARTIST",
        "aART" to "ALBUMARTIST",
        "©alb" to "ALBUM",
        "©wrt" to "COMPOSER",
        "©grp" to "GROUPING",
        "©gen" to "GENRE",
        "©cmt" to "COMMENT",
        "©lyr" to "LYRICS",
        "cprt" to "COPYRIGHT",
        "desc" to "DESCRIPTION",
        "©st3" to "SUBTITLE",
        "soal" to "ALBUMSORT",
        "soar" to "ARTISTSORT",
        "soaa" to "ALBUMARTISTSORT",
        "sonm" to "TITLESORT",
        "soco" to "COMPOSERSORT",
    )
}
