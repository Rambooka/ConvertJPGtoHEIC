package com.example.convertjpgtoheic

import java.io.ByteArrayOutputStream
import java.io.EOFException
import java.io.InputStream
import java.io.OutputStream
import java.util.Base64

/**
 * Rewrites the tags of an Ogg Opus file.
 *
 * `MediaMuxer` writes Opus into Ogg with an empty OpusTags packet and offers no way to fill it, so
 * the converter writes the file first and then streams it through [retag]: the OpusHead page is
 * kept, OpusTags is replaced (spread over as many pages as album art needs), and every audio page
 * is copied byte for byte apart from its sequence number and checksum.
 *
 * Kept free of Android types so it can be tested on the JVM. See RFC 3533 (Ogg) and RFC 7845
 * (Ogg Opus).
 */
object OggOpus {

    class FormatException(message: String) : Exception(message)

    /** One Ogg page. [lacing] is its segment table; [body] the bytes the segments describe. */
    class Page(
        val headerType: Int,
        val granule: Long,
        val serial: Int,
        val sequence: Int,
        val lacing: IntArray,
        val body: ByteArray,
    ) {
        val continued: Boolean get() = headerType and FLAG_CONTINUED != 0

        /** Whether the page's last packet ends on it, rather than running on into the next. */
        val endsPacket: Boolean get() = lacing.isNotEmpty() && lacing.last() < 255
    }

    const val FLAG_CONTINUED = 0x01
    const val FLAG_FIRST = 0x02
    const val FLAG_LAST = 0x04

    /** Copies the Ogg Opus stream [input] to [out] with its tags replaced by [comments]. */
    fun retag(input: InputStream, out: OutputStream, comments: List<Pair<String, String>>) {
        val head = readPage(input) ?: throw FormatException("empty file")
        if (head.lacing.size != 1 || head.lacing[0] == 255 || !head.body.startsWith(OPUS_HEAD)) {
            throw FormatException("does not start with an OpusHead page")
        }

        // Gather the OpusTags packet, which may run over several pages.
        val tags = ByteArrayOutputStream()
        var oldHeaderPages = 1
        while (true) {
            val page = readPage(input) ?: throw FormatException("no OpusTags packet")
            oldHeaderPages++
            if (page.lacing.dropLast(1).any { it < 255 }) {
                // A packet ending before the last segment means audio shares the page: RFC 7845
                // forbids that, and splitting it here would need re-paging the audio too.
                throw FormatException("audio starts on the OpusTags page")
            }
            tags.write(page.body)
            if (page.endsPacket) break
        }
        val vendor = vendorOf(tags.toByteArray())

        writePage(out, Page(head.headerType, head.granule, head.serial, 0, head.lacing, head.body))
        val packet = commentPacket(vendor, comments)
        var sequence = 1
        for (page in paginate(packet, head.serial, firstSequence = 1)) {
            writePage(out, page)
            sequence++
        }

        val shift = sequence - oldHeaderPages
        while (true) {
            val page = readPage(input) ?: break
            writePage(out, Page(page.headerType, page.granule, page.serial, page.sequence + shift, page.lacing, page.body))
        }
        out.flush()
    }

    /** The vendor string and comments of the stream's OpusTags packet. */
    fun readComments(input: InputStream): Pair<String, List<Pair<String, String>>> {
        readPage(input) ?: throw FormatException("empty file")
        val tags = ByteArrayOutputStream()
        while (true) {
            val page = readPage(input) ?: throw FormatException("no OpusTags packet")
            tags.write(page.body)
            if (page.endsPacket) break
        }
        val packet = tags.toByteArray()
        val vendor = vendorOf(packet)
        var pos = OPUS_TAGS.size + 4 + le32(packet, OPUS_TAGS.size)
        val count = le32(packet, pos)
        pos += 4
        val out = ArrayList<Pair<String, String>>(count)
        repeat(count) {
            val len = le32(packet, pos)
            pos += 4
            if (len < 0 || pos + len > packet.size) throw FormatException("truncated comment")
            val text = String(packet, pos, len, Charsets.UTF_8)
            pos += len
            out += text.substringBefore('=') to text.substringAfter('=', "")
        }
        return vendor to out
    }

    /** The OpusTags packet: magic, vendor, then each comment as `KEY=value`. */
    fun commentPacket(vendor: String, comments: List<Pair<String, String>>): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(OPUS_TAGS)
        val v = vendor.toByteArray(Charsets.UTF_8)
        writeLe32(out, v.size)
        out.write(v)
        writeLe32(out, comments.size)
        for ((key, value) in comments) {
            val c = "$key=$value".toByteArray(Charsets.UTF_8)
            writeLe32(out, c.size)
            out.write(c)
        }
        return out.toByteArray()
    }

    /**
     * A picture as a `METADATA_BLOCK_PICTURE` value: a FLAC picture block, base64-encoded. Width,
     * height and depth are optional hints and left at zero.
     */
    fun pictureComment(picture: Id3Tags.Picture): Pair<String, String> {
        val out = ByteArrayOutputStream(picture.data.size + 64)
        val mime = picture.mime.toByteArray(Charsets.US_ASCII)
        val desc = picture.description.toByteArray(Charsets.UTF_8)
        writeBe32(out, picture.type)
        writeBe32(out, mime.size)
        out.write(mime)
        writeBe32(out, desc.size)
        out.write(desc)
        repeat(4) { writeBe32(out, 0) } // width, height, colour depth, palette size
        writeBe32(out, picture.data.size)
        out.write(picture.data)
        return "METADATA_BLOCK_PICTURE" to Base64.getEncoder().encodeToString(out.toByteArray())
    }

    /** Splits one header packet into pages of at most 255 segments, all with granule 0. */
    internal fun paginate(packet: ByteArray, serial: Int, firstSequence: Int): List<Page> {
        // A packet of n bytes is n / 255 full segments plus a final shorter one, possibly empty.
        val segments = packet.size / 255 + 1
        val pages = ArrayList<Page>()
        var seg = 0
        var offset = 0
        while (seg < segments) {
            val count = minOf(255, segments - seg)
            val lacing = IntArray(count) { i ->
                if (seg + i < segments - 1) 255 else packet.size % 255
            }
            val bytes = lacing.sum()
            pages += Page(
                headerType = if (seg == 0) 0 else FLAG_CONTINUED,
                granule = 0,
                serial = serial,
                sequence = firstSequence + pages.size,
                lacing = lacing,
                body = packet.copyOfRange(offset, offset + bytes),
            )
            offset += bytes
            seg += count
        }
        return pages
    }

    /** The next page, or null at a clean end of stream. */
    fun readPage(input: InputStream): Page? {
        val header = ByteArray(27)
        val first = input.read()
        if (first < 0) return null
        header[0] = first.toByte()
        readFully(input, header, 1, 26)
        if (!header.startsWith(CAPTURE)) throw FormatException("lost page sync")
        if (header[4].toInt() != 0) throw FormatException("unknown Ogg version")
        val lacing = IntArray(header[26].toInt() and 0xFF)
        val table = ByteArray(lacing.size)
        readFully(input, table, 0, table.size)
        for (i in lacing.indices) lacing[i] = table[i].toInt() and 0xFF
        val body = ByteArray(lacing.sum())
        readFully(input, body, 0, body.size)

        val stored = le32(header, 22)
        header[22] = 0; header[23] = 0; header[24] = 0; header[25] = 0
        val crc = crc(crc(crc(0, header), table), body)
        if (crc != stored) throw FormatException("page checksum mismatch")

        return Page(
            headerType = header[5].toInt() and 0xFF,
            granule = le64(header, 6),
            serial = le32(header, 14),
            sequence = le32(header, 18),
            lacing = lacing,
            body = body,
        )
    }

    fun writePage(out: OutputStream, page: Page) {
        val header = ByteArray(27 + page.lacing.size)
        System.arraycopy(CAPTURE, 0, header, 0, 4)
        header[4] = 0
        header[5] = page.headerType.toByte()
        putLe64(header, 6, page.granule)
        putLe32(header, 14, page.serial)
        putLe32(header, 18, page.sequence)
        header[26] = page.lacing.size.toByte()
        for (i in page.lacing.indices) header[27 + i] = page.lacing[i].toByte()
        putLe32(header, 22, crc(crc(0, header), page.body))
        out.write(header)
        out.write(page.body)
    }

    private fun vendorOf(packet: ByteArray): String {
        if (!packet.startsWith(OPUS_TAGS)) throw FormatException("second packet is not OpusTags")
        val len = le32(packet, OPUS_TAGS.size)
        if (len < 0 || OPUS_TAGS.size + 4 + len > packet.size) throw FormatException("truncated vendor string")
        return String(packet, OPUS_TAGS.size + 4, len, Charsets.UTF_8)
    }

    // region bytes

    private val CAPTURE = "OggS".toByteArray(Charsets.US_ASCII)
    private val OPUS_HEAD = "OpusHead".toByteArray(Charsets.US_ASCII)
    private val OPUS_TAGS = "OpusTags".toByteArray(Charsets.US_ASCII)

    /** Ogg's CRC-32: polynomial 0x04C11DB7, not reflected, zero initial value and no final XOR. */
    private val CRC_TABLE = IntArray(256) { i ->
        var r = i shl 24
        repeat(8) { r = if (r and 0x80000000.toInt() != 0) (r shl 1) xor 0x04C11DB7 else r shl 1 }
        r
    }

    internal fun crc(start: Int, data: ByteArray): Int {
        var crc = start
        for (b in data) crc = (crc shl 8) xor CRC_TABLE[((crc ushr 24) xor (b.toInt() and 0xFF)) and 0xFF]
        return crc
    }

    private fun ByteArray.startsWith(prefix: ByteArray): Boolean =
        size >= prefix.size && prefix.indices.all { this[it] == prefix[it] }

    private fun readFully(input: InputStream, into: ByteArray, offset: Int, length: Int) {
        var done = 0
        while (done < length) {
            val n = input.read(into, offset + done, length - done)
            if (n < 0) throw EOFException("truncated Ogg page")
            done += n
        }
    }

    private fun le32(b: ByteArray, at: Int): Int {
        if (at < 0 || at + 4 > b.size) throw FormatException("truncated")
        return (b[at].toInt() and 0xFF) or ((b[at + 1].toInt() and 0xFF) shl 8) or
            ((b[at + 2].toInt() and 0xFF) shl 16) or ((b[at + 3].toInt() and 0xFF) shl 24)
    }

    private fun le64(b: ByteArray, at: Int): Long =
        (le32(b, at).toLong() and 0xFFFFFFFFL) or (le32(b, at + 4).toLong() shl 32)

    private fun putLe32(b: ByteArray, at: Int, v: Int) {
        for (i in 0 until 4) b[at + i] = (v ushr (8 * i)).toByte()
    }

    private fun putLe64(b: ByteArray, at: Int, v: Long) {
        for (i in 0 until 8) b[at + i] = (v ushr (8 * i)).toByte()
    }

    private fun writeLe32(out: OutputStream, v: Int) {
        for (i in 0 until 4) out.write(v ushr (8 * i))
    }

    private fun writeBe32(out: OutputStream, v: Int) {
        for (i in 3 downTo 0) out.write(v ushr (8 * i))
    }

    // endregion
}
