package com.example.convertjpgtoheic

/**
 * Reads the tags of a FLAC file as Vorbis comments, the same shape [Id3Tags] gives for an MP3.
 *
 * FLAC needs no mapping: its `VORBIS_COMMENT` block already is Vorbis comments, exactly what Opus
 * carries, so they are passed through with their keys upper-cased. Pictures sit in their own
 * `PICTURE` blocks, the same structure Opus wraps in base64 as `METADATA_BLOCK_PICTURE`. The blocks
 * all come first, after the `fLaC` marker, each with a 4-byte header: a last-block flag, a type and
 * a 24-bit length.
 *
 * Pure Kotlin over a [ByteSource], so it can be tested without a device.
 */
object FlacTags {

    /** Whether [header] (the file's first 4 bytes or more) opens a FLAC stream. */
    fun isFlac(header: ByteArray): Boolean =
        header.size >= 4 && String(header, 0, 4, Charsets.ISO_8859_1) == "fLaC"

    /** The tags of the FLAC file [src], or null if it is not one this can read. */
    fun read(src: ByteSource): Id3Tags.Tags? {
        if (src.size < 8 || !isFlac(src.read(0, 4))) return null
        val comments = ArrayList<Pair<String, String>>()
        val pictures = ArrayList<Id3Tags.Picture>()
        var position = 4L
        var total = 0L
        while (position + 4 <= src.size) {
            val header = src.read(position, 4)
            val last = header[0].toInt() and 0x80 != 0
            val type = header[0].toInt() and 0x7F
            val length = be(header, 1, 3).toInt()
            position += 4
            if (position + length > src.size) return null
            total += length
            if (total > MAX_METADATA_BYTES) return null
            when (type) {
                TYPE_VORBIS_COMMENT -> comments += vorbisComments(src.read(position, length)) ?: return null
                TYPE_PICTURE -> picture(src.read(position, length))?.let { pictures += it }
            }
            position += length
            if (last) break
        }
        pictures.sortBy { if (it.type == Id3Tags.FRONT_COVER) 0 else 1 }
        return Id3Tags.Tags(comments, pictures)
    }

    /** A `VORBIS_COMMENT` block: little-endian lengths, as in Ogg, and no framing bit. */
    private fun vorbisComments(b: ByteArray): List<Pair<String, String>>? {
        var at = 0
        fun le32(): Int? {
            if (at + 4 > b.size) return null
            val v = (b[at].toInt() and 0xFF) or ((b[at + 1].toInt() and 0xFF) shl 8) or
                ((b[at + 2].toInt() and 0xFF) shl 16) or ((b[at + 3].toInt() and 0xFF) shl 24)
            at += 4
            return v
        }
        val vendor = le32() ?: return null
        if (vendor < 0 || at + vendor > b.size) return null
        at += vendor
        val count = le32() ?: return null
        val out = ArrayList<Pair<String, String>>()
        repeat(count.coerceAtLeast(0)) {
            val len = le32() ?: return out
            if (len < 0 || at + len > b.size) return out
            val text = String(b, at, len, Charsets.UTF_8)
            at += len
            val key = text.substringBefore('=', "").trim().uppercase()
            val value = text.substringAfter('=', "").trim()
            // Pictures come from the PICTURE blocks; one smuggled in as a comment is left out
            // rather than carried twice.
            if (key.isEmpty() || value.isEmpty() || key == "METADATA_BLOCK_PICTURE") return@repeat
            out += key to value
        }
        return out
    }

    /** A `PICTURE` block: type, MIME type, description, four size hints, then the image. */
    private fun picture(b: ByteArray): Id3Tags.Picture? {
        if (b.size < 32) return null
        val type = be(b, 0, 4).toInt()
        val mimeLen = be(b, 4, 4).toInt()
        if (mimeLen < 0 || 8 + mimeLen + 4 > b.size) return null
        val mime = String(b, 8, mimeLen, Charsets.US_ASCII)
        var at = 8 + mimeLen
        val descLen = be(b, at, 4).toInt()
        at += 4
        if (descLen < 0 || at + descLen + 20 > b.size) return null
        val description = String(b, at, descLen, Charsets.UTF_8)
        at += descLen + 16 // width, height, colour depth, palette size
        val dataLen = be(b, at, 4).toInt()
        at += 4
        if (dataLen <= 0 || at + dataLen > b.size) return null
        if (mime == "-->") return null // a link to the picture, not the picture
        return Id3Tags.Picture(mime.ifEmpty { "image/jpeg" }, type, description, b.copyOfRange(at, at + dataLen))
    }

    private fun be(b: ByteArray, at: Int, n: Int): Long {
        var v = 0L
        for (i in 0 until n) v = (v shl 8) or (b[at + i].toLong() and 0xFF)
        return v
    }

    private const val TYPE_VORBIS_COMMENT = 4
    private const val TYPE_PICTURE = 6

    /** Far beyond any real tags, cover art included; guards against corrupt lengths. */
    private const val MAX_METADATA_BYTES = 64L * 1024 * 1024
}
