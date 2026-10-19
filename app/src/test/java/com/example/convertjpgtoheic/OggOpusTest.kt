package com.example.convertjpgtoheic

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.Base64

class OggOpusTest {

    private val serial = 0x1234ABCD

    private fun opusHead(): ByteArray =
        "OpusHead".toByteArray() + byteArrayOf(1, 2, 0x38, 1, 0x80.toByte(), 0xBB.toByte(), 0, 0, 0, 0, 0)

    private fun page(type: Int, granule: Long, seq: Int, packet: ByteArray) =
        OggOpus.paginate(packet, serial, seq).single().let {
            OggOpus.Page(type, granule, serial, seq, it.lacing, it.body)
        }

    /** OpusHead, an empty OpusTags as MediaMuxer writes it, then [audio] pages, the last flagged EOS. */
    private fun stream(audio: List<ByteArray>): ByteArray {
        val out = ByteArrayOutputStream()
        OggOpus.writePage(out, page(OggOpus.FLAG_FIRST, 0, 0, opusHead()))
        OggOpus.writePage(out, page(0, 0, 1, OggOpus.commentPacket("AOSP", emptyList())))
        audio.forEachIndexed { i, pkt ->
            OggOpus.writePage(out, page(if (i == audio.lastIndex) OggOpus.FLAG_LAST else 0, 960L * (i + 1), 2 + i, pkt))
        }
        return out.toByteArray()
    }

    private fun pages(bytes: ByteArray): List<OggOpus.Page> {
        val input = ByteArrayInputStream(bytes)
        return generateSequence { OggOpus.readPage(input) }.toList()
    }

    private val audio = List(5) { i -> ByteArray(100 + i * 37) { (it * 7 + i).toByte() } }

    @Test
    fun `the first picture becomes the front cover only when there is none`() {
        fun pic(type: Int) = Id3Tags.Picture("image/jpeg", type, "", byteArrayOf(type.toByte()))
        assertEquals(listOf(3, 0), OggOpus.withFrontCover(listOf(pic(255), pic(0))).map { it.type })
        assertEquals(listOf(3, 255), OggOpus.withFrontCover(listOf(pic(3), pic(255))).map { it.type })
        assertEquals(listOf(0, 3), OggOpus.withFrontCover(listOf(pic(0), pic(3))).map { it.type })
        assertEquals(emptyList<Int>(), OggOpus.withFrontCover(emptyList()).map { it.type })
    }

    @Test
    fun `retagging keeps the audio identical, and any change to it is caught`() {
        val original = stream(audio)
        val retagged = ByteArrayOutputStream().also {
            OggOpus.retag(ByteArrayInputStream(original), it, listOf("DATE" to "2013", "YEAR" to "2013"))
        }.toByteArray()
        assertTrue(OggOpus.sameAudio(ByteArrayInputStream(original), ByteArrayInputStream(retagged)))

        val changed = audio.toMutableList().also { it[2] = it[2].copyOf().apply { this[5] = (this[5] + 1).toByte() } }
        assertFalse(OggOpus.sameAudio(ByteArrayInputStream(original), ByteArrayInputStream(stream(changed))))
        assertFalse(OggOpus.sameAudio(ByteArrayInputStream(original), ByteArrayInputStream(stream(audio.dropLast(1)))))
    }

    /** Skipped unless `OPUS_SAMPLES_DIR` names a folder of `.opus` files; repaired copies go to `<dir>/repaired/`. */
    @Test
    fun `the music repair works on real files`() {
        val dir = System.getenv("OPUS_SAMPLES_DIR")?.let(::File)
        assumeTrue("set OPUS_SAMPLES_DIR to run", dir != null && dir.isDirectory)
        val out = File(dir, "repaired").apply { mkdirs() }
        for (f in dir!!.listFiles { f -> f.name.endsWith(".opus") }!!.sorted()) {
            val before = OggOpus.readComments(f.inputStream().buffered()).second
            val fix = MusicRepair.diagnose(before) as? MusicRepair.Diagnosis.Needs ?: continue
            val repaired = File(out, f.name)
            f.inputStream().buffered().use { i -> repaired.outputStream().buffered().use { o -> OggOpus.retag(i, o, fix.comments) } }
            val after = OggOpus.readComments(repaired.inputStream().buffered()).second
            println("${f.name}: ${f.length()} -> ${repaired.length()} bytes, YEAR=${after.first { it.first == "YEAR" }.second}")
            assertEquals(fix.comments, after)
            assertTrue(OggOpus.sameAudio(f.inputStream().buffered(), repaired.inputStream().buffered()))
        }
    }

    @Test
    fun `the music repair adds a year only where one can be had`() {
        assertEquals(MusicRepair.Diagnosis.Fine, MusicRepair.diagnose(listOf("DATE" to "2013", "YEAR" to "2013")))
        assertEquals(MusicRepair.Diagnosis.NoDate, MusicRepair.diagnose(listOf("TITLE" to "Untitled")))
        assertEquals(
            MusicRepair.Diagnosis.Needs(listOf("TITLE" to "Move", "DATE" to "2013-11-08", "YEAR" to "2013")),
            MusicRepair.diagnose(listOf("TITLE" to "Move", "DATE" to "2013-11-08")),
        )
    }

    @Test
    fun `a YEAR is added from DATE for Android's music library`() {
        fun year(vararg c: Pair<String, String>) = OggOpus.withYear(c.toList()).filter { it.first == "YEAR" }.map { it.second }
        assertEquals(listOf("2013"), year("DATE" to "2013-11-08"))
        assertEquals(listOf("2003"), year("DATE" to "2003"))
        assertEquals(listOf("1999"), year("YEAR" to "1999", "DATE" to "2013")) // an existing YEAR wins
        assertEquals(emptyList<String>(), year("DATE" to "20131108")) // not a year
        assertEquals(emptyList<String>(), year("DATE" to "Summer"))
        assertEquals(emptyList<String>(), year("TITLE" to "No date"))
    }

    @Test
    fun `comment packet size is predicted exactly`() {
        val comments = listOf("TITLE" to "Café del Mar", "ARTIST" to "Ünïcødé", "EMPTY" to "")
        for (c in listOf(emptyList(), comments)) {
            assertEquals(
                OggOpus.commentPacket("libopus 1.4", c).size.toLong(),
                OggOpus.commentPacketSize("libopus 1.4", c),
            )
        }
    }

    @Test
    fun `retag writes the comments and leaves the audio alone`() {
        val comments = listOf("TITLE" to "Song", "ARTIST" to "Bjørk", "TRACKNUMBER" to "3")
        val out = ByteArrayOutputStream()
        OggOpus.retag(ByteArrayInputStream(stream(audio)), out, comments)

        val (vendor, read) = OggOpus.readComments(ByteArrayInputStream(out.toByteArray()))
        assertEquals("AOSP", vendor)
        assertEquals(comments, read)

        val written = pages(out.toByteArray()) // also checks every CRC
        assertEquals((0 until written.size).toList(), written.map { it.sequence })
        val audioPages = written.drop(2)
        assertEquals(audio.size, audioPages.size)
        audio.forEachIndexed { i, a ->
            assertArrayEquals(a, audioPages[i].body)
            assertEquals(960L * (i + 1), audioPages[i].granule)
        }
        assertEquals(OggOpus.FLAG_FIRST, written[0].headerType)
        assertEquals(OggOpus.FLAG_LAST, written.last().headerType)
    }

    @Test
    fun `a large cover spreads the tags over several pages and renumbers the audio`() {
        val art = ByteArray(200_000) { (it * 31).toByte() }
        val picture = Id3Tags.Picture("image/jpeg", 3, "front", art)
        val comments = listOf("TITLE" to "Song", OggOpus.pictureComment(picture))
        val out = ByteArrayOutputStream()
        OggOpus.retag(ByteArrayInputStream(stream(audio)), out, comments)

        val written = pages(out.toByteArray())
        val tagPages = written.size - 1 - audio.size
        assertTrue("expected several tag pages, got $tagPages", tagPages > 1)
        assertEquals((0 until written.size).toList(), written.map { it.sequence })
        written.subList(2, 1 + tagPages).forEach { assertEquals(OggOpus.FLAG_CONTINUED, it.headerType) }
        written.subList(1, 1 + tagPages).forEach { assertEquals(0L, it.granule) }
        // Audio starts on a fresh page, never continuing the tags.
        assertEquals(0, written[1 + tagPages].headerType and OggOpus.FLAG_CONTINUED)
        audio.forEachIndexed { i, a -> assertArrayEquals(a, written[1 + tagPages + i].body) }

        val read = OggOpus.readComments(ByteArrayInputStream(out.toByteArray())).second
        assertEquals(comments, read)
    }

    @Test
    fun `picture block layout is FLAC's`() {
        val art = byteArrayOf(9, 8, 7)
        val (key, value) = OggOpus.pictureComment(Id3Tags.Picture("image/png", 3, "d", art))
        assertEquals("METADATA_BLOCK_PICTURE", key)
        val b = Base64.getDecoder().decode(value)
        val expected = byteArrayOf(0, 0, 0, 3, 0, 0, 0, 9) + "image/png".toByteArray() +
            byteArrayOf(0, 0, 0, 1) + "d".toByteArray() + ByteArray(16) + byteArrayOf(0, 0, 0, 3) + art
        assertArrayEquals(expected, b)
    }

    @Test
    fun `packet sizes on the 255 boundary are laced correctly`() {
        for (size in listOf(0, 1, 254, 255, 256, 510, 255 * 255 - 1, 255 * 255, 255 * 255 + 1)) {
            val packet = ByteArray(size) { it.toByte() }
            val pages = OggOpus.paginate(packet, serial, 1)
            assertEquals("size $size", size, pages.sumOf { it.body.size })
            assertTrue("size $size", pages.last().endsPacket)
            pages.dropLast(1).forEach { assertTrue(!it.endsPacket) }
            assertArrayEquals(packet, pages.fold(ByteArray(0)) { acc, p -> acc + p.body })
        }
    }

    @Test
    fun `CRC matches the Ogg reference value`() {
        // Ogg's CRC is CRC-32/CKSUM without the final inversion; that catalogue's check value
        // for "123456789" is 0x765E7680.
        assertEquals(0x765E7680 xor -1, OggOpus.crc(0, "123456789".toByteArray()))
    }

    @Test
    fun `a corrupt page is refused, not passed on`() {
        val bytes = stream(audio)
        bytes[bytes.size - 3] = (bytes[bytes.size - 3] + 1).toByte()
        assertThrows(OggOpus.FormatException::class.java) {
            OggOpus.retag(ByteArrayInputStream(bytes), ByteArrayOutputStream(), emptyList())
        }
    }

    @Test
    fun `audio sharing the tags page is refused`() {
        val out = ByteArrayOutputStream()
        OggOpus.writePage(out, page(OggOpus.FLAG_FIRST, 0, 0, opusHead()))
        val tags = OggOpus.commentPacket("AOSP", emptyList())
        val both = OggOpus.Page(0, 0, serial, 1, intArrayOf(tags.size, 10), tags + ByteArray(10))
        OggOpus.writePage(out, both)
        assertThrows(OggOpus.FormatException::class.java) {
            OggOpus.retag(ByteArrayInputStream(out.toByteArray()), ByteArrayOutputStream(), emptyList())
        }
    }
}
