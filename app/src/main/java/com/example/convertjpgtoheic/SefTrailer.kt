package com.example.convertjpgtoheic

/**
 * What a Samsung SEF (Samsung Extended Format) trailer tells us about a photo.
 *
 * Samsung stamps a SEF trailer on the end of *every* camera JPG — plain metadata such as
 * `Image_UTC_Data` — so the presence of a trailer means nothing on its own. Only a motion photo
 * carries a `MotionPhoto_Data` record, and that record is a complete, standalone MP4.
 *
 * @param sefStart the absolute byte offset where the SEF area begins, i.e. immediately after the
 *   still image. Everything from here to the end of the file is the trailer.
 * @param hasVideo a `MotionPhoto_Data` record is present, so this is a motion photo whose video can
 *   be carried across by copying `[sefStart, EOF)` verbatim onto a re-encoded still.
 */
data class SefInfo(
    val sefStart: Long,
    val hasVideo: Boolean,
)

/**
 * Reads the Samsung SEF trailer that sits after the image data in a Samsung JPG.
 *
 * Deliberately free of Android types, and driven purely by the file's tail bytes plus its total
 * size, so the awkward offset arithmetic can be unit-tested directly.
 *
 * ### Format (little-endian), reverse-engineered from Galaxy S21 files
 *
 * ```
 * [ still image ][ data records… ][ SEFH directory ][ dir_size:u32 ]["SEFT"]
 * ```
 *
 * The footer's last four bytes are `SEFT`; the `u32` before them is the size of the directory. The
 * directory begins `8 + dir_size` bytes from the end with the magic `SEFH`, a `u32` (constant
 * `0x6b`), a `u32` entry count, then that many 12-byte entries:
 *
 * ```
 * u16 flags ; u16 type ; u32 offset ; u32 size
 * ```
 *
 * `offset` is measured *backwards* from the start of `SEFH` to the start of the data record, and
 * `size` is the record's total length. Each record is `u16 unk ; u16 type ; u32 nameLen ; name ;
 * payload`. Because every offset is relative to `SEFH`, the whole trailer is position-independent:
 * it can be appended unchanged to a differently-sized primary image (a HEIC in place of the JPG)
 * and every internal offset still resolves.
 */
object SefTrailer {

    /** How many bytes of the file's tail are enough to hold the footer, directory and the small
     *  records near the end. The directory sits ~100 bytes from EOF even for a motion photo. */
    const val TAIL_BYTES = 8192

    private const val SEFT = 0x54464553L // "SEFT" read as a little-endian u32 ('S','E','F','T')
    private const val SEFH = 0x48464553L // "SEFH"

    /** The record type Samsung uses for the embedded video. Cross-checked against the record name
     *  where it is readable, but the video record itself sits megabytes back, out of the tail. */
    private const val TYPE_MOTION_VIDEO = 0x0a30

    private const val MAX_ENTRIES = 64
    private const val MAX_NAME_LEN = 64

    /**
     * Parses the SEF trailer from the tail of a file.
     *
     * @param tail the last bytes of the file — at least [TAIL_BYTES], or the whole file if smaller.
     * @param fileSize the file's true total length, which anchors the trailer's absolute offsets.
     * @return the trailer's shape, or null if there is no well-formed SEF trailer here.
     */
    fun parse(tail: ByteArray, fileSize: Long): SefInfo? {
        if (tail.size < 16 || fileSize < tail.size) return null
        if (u32(tail, tail.size - 4) != SEFT) return null

        val dirSize = u32(tail, tail.size - 8)
        val sefhAbs = fileSize - 8 - dirSize
        val tailStartAbs = fileSize - tail.size
        // The directory must fall inside the tail we were given, or we cannot read it.
        if (sefhAbs < tailStartAbs || sefhAbs < 0) return null

        val sefhIdx = (sefhAbs - tailStartAbs).toInt()
        if (sefhIdx + 12 > tail.size) return null
        if (u32(tail, sefhIdx) != SEFH) return null

        val count = u32(tail, sefhIdx + 8)
        if (count <= 0 || count > MAX_ENTRIES) return null

        var minRecStartAbs = Long.MAX_VALUE
        var hasVideo = false

        var o = sefhIdx + 12
        for (i in 0 until count.toInt()) {
            if (o + 12 > tail.size) return null
            val type = u16(tail, o + 2)
            val offset = u32(tail, o + 4)
            val recStartAbs = sefhAbs - offset
            if (recStartAbs < 0) return null
            if (recStartAbs < minRecStartAbs) minRecStartAbs = recStartAbs

            val name = nameAt(tail, tailStartAbs, recStartAbs)
            if (type == TYPE_MOTION_VIDEO || name == "MotionPhoto_Data") hasVideo = true
            o += 12
        }

        if (minRecStartAbs <= 0 || minRecStartAbs >= fileSize) return null
        return SefInfo(sefStart = minRecStartAbs, hasVideo = hasVideo)
    }

    /** The record header names the record; readable only when the record falls inside the tail. */
    private fun nameAt(tail: ByteArray, tailStartAbs: Long, recStartAbs: Long): String? {
        val idxLong = recStartAbs - tailStartAbs
        if (idxLong < 0 || idxLong + 8 > tail.size) return null
        val idx = idxLong.toInt()
        val nameLen = u32(tail, idx + 4)
        if (nameLen <= 0 || nameLen > MAX_NAME_LEN) return null
        if (idx + 8 + nameLen > tail.size) return null
        return String(tail, idx + 8, nameLen.toInt(), Charsets.ISO_8859_1)
    }

    // Read as Long so a 32-bit value near 4 GB never turns negative on us.
    private fun u32(b: ByteArray, o: Int): Long =
        (b[o].toLong() and 0xFF) or
            ((b[o + 1].toLong() and 0xFF) shl 8) or
            ((b[o + 2].toLong() and 0xFF) shl 16) or
            ((b[o + 3].toLong() and 0xFF) shl 24)

    private fun u16(b: ByteArray, o: Int): Int =
        (b[o].toInt() and 0xFF) or ((b[o + 1].toInt() and 0xFF) shl 8)
}
