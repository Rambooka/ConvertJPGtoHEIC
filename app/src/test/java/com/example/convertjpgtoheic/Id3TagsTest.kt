package com.example.convertjpgtoheic

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream

class Id3TagsTest {

    // region builders

    private fun syncsafe(n: Int) = byteArrayOf((n shr 21 and 0x7F).toByte(), (n shr 14 and 0x7F).toByte(), (n shr 7 and 0x7F).toByte(), (n and 0x7F).toByte())
    private fun be32(n: Int) = byteArrayOf((n shr 24).toByte(), (n shr 16).toByte(), (n shr 8).toByte(), n.toByte())
    private fun be24(n: Int) = byteArrayOf((n shr 16).toByte(), (n shr 8).toByte(), n.toByte())

    private fun tag(major: Int, frames: List<ByteArray>, flags: Int = 0, padding: Int = 16): ByteArray {
        val body = ByteArrayOutputStream()
        frames.forEach { body.write(it) }
        body.write(ByteArray(padding))
        val b = body.toByteArray()
        return byteArrayOf('I'.code.toByte(), 'D'.code.toByte(), '3'.code.toByte(), major.toByte(), 0, flags.toByte()) + syncsafe(b.size) + b
    }

    private fun frame(major: Int, id: String, data: ByteArray, flags: Int = 0): ByteArray = when (major) {
        2 -> id.toByteArray() + be24(data.size) + data
        3 -> id.toByteArray() + be32(data.size) + byteArrayOf((flags shr 8).toByte(), flags.toByte()) + data
        else -> id.toByteArray() + syncsafe(data.size) + byteArrayOf((flags shr 8).toByte(), flags.toByte()) + data
    }

    private fun latin1(s: String) = byteArrayOf(0) + s.toByteArray(Charsets.ISO_8859_1)
    private fun utf16(s: String) = byteArrayOf(1) + s.toByteArray(Charsets.UTF_16LE).let { byteArrayOf(0xFF.toByte(), 0xFE.toByte()) + it }
    private fun utf8(s: String) = byteArrayOf(3) + s.toByteArray(Charsets.UTF_8)

    private val jpeg = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE0.toByte(), 1, 2, 3, 0, 0xFF.toByte(), 0xD9.toByte())

    // endregion

    @Test
    fun `v2_3 text frames map to Vorbis fields`() {
        val t = tag(3, listOf(
            frame(3, "TIT2", latin1("Song")),
            frame(3, "TPE1", utf16("Bjørk")),
            frame(3, "TALB", latin1("Album")),
            frame(3, "TPE2", latin1("Various")),
            frame(3, "TRCK", latin1("3/12")),
            frame(3, "TPOS", latin1("1/2")),
            frame(3, "TYER", latin1("1997")),
            frame(3, "TDAT", latin1("2209")),
            frame(3, "TCON", latin1("(17)")),
        ))
        val tags = Id3Tags.parse(t, null)
        assertEquals("Song", tags.first("TITLE"))
        assertEquals("Bjørk", tags.first("ARTIST"))
        assertEquals("Album", tags.first("ALBUM"))
        assertEquals("Various", tags.first("ALBUMARTIST"))
        assertEquals("3", tags.first("TRACKNUMBER"))
        assertEquals("12", tags.first("TRACKTOTAL"))
        assertEquals("1", tags.first("DISCNUMBER"))
        assertEquals("2", tags.first("DISCTOTAL"))
        assertEquals("1997-09-22", tags.first("DATE"))
        assertEquals("Rock", tags.first("GENRE"))
    }

    @Test
    fun `v2_4 handles UTF-8, multiple values and syncsafe sizes`() {
        // 200 bytes: a size whose syncsafe and plain encodings differ.
        val long = "x".repeat(199)
        val t = tag(4, listOf(
            frame(4, "TIT2", utf8(long)),
            frame(4, "TPE1", utf8("A\u0000B")),
            frame(4, "TDRC", utf8("2001-05-04")),
            frame(4, "TCON", utf8("13\u0000Synthwave")),
        ))
        val tags = Id3Tags.parse(t, null)
        assertEquals(long, tags.first("TITLE"))
        assertEquals(listOf("A", "B"), tags.comments.filter { it.first == "ARTIST" }.map { it.second })
        assertEquals("2001-05-04", tags.first("DATE"))
        assertEquals(listOf("Pop", "Synthwave"), tags.comments.filter { it.first == "GENRE" }.map { it.second })
    }

    @Test
    fun `v2_4 tolerates iTunes' plain frame sizes`() {
        val data = utf8("y".repeat(299))
        val bad = "TIT2".toByteArray() + be32(data.size) + byteArrayOf(0, 0) + data
        val t = tag(4, listOf(bad, frame(4, "TPE1", utf8("Artist"))))
        val tags = Id3Tags.parse(t, null)
        assertEquals("y".repeat(299), tags.first("TITLE"))
        assertEquals("Artist", tags.first("ARTIST"))
    }

    @Test
    fun `v2_2 three-letter frames and PIC`() {
        val pic = byteArrayOf(0) + "PNG".toByteArray() + byteArrayOf(3) + "cover".toByteArray() + byteArrayOf(0) + jpeg
        val t = tag(2, listOf(
            frame(2, "TT2", latin1("Old Song")),
            frame(2, "TP1", latin1("Old Artist")),
            frame(2, "TRK", latin1("7")),
            frame(2, "PIC", pic),
        ))
        val tags = Id3Tags.parse(t, null)
        assertEquals("Old Song", tags.first("TITLE"))
        assertEquals("Old Artist", tags.first("ARTIST"))
        assertEquals("7", tags.first("TRACKNUMBER"))
        assertNull(tags.first("TRACKTOTAL"))
        assertEquals(1, tags.pictures.size)
        assertEquals("image/png", tags.pictures[0].mime)
        assertEquals("cover", tags.pictures[0].description)
        assertArrayEquals(jpeg, tags.pictures[0].data)
    }

    @Test
    fun `APIC with a UTF-16 description, front cover listed first`() {
        fun apic(type: Int, desc: String) = byteArrayOf(1) + "image/jpeg".toByteArray() + byteArrayOf(0, type.toByte()) +
            byteArrayOf(0xFF.toByte(), 0xFE.toByte()) + desc.toByteArray(Charsets.UTF_16LE) + byteArrayOf(0, 0) + jpeg
        val t = tag(3, listOf(frame(3, "APIC", apic(4, "back")), frame(3, "APIC", apic(3, "front"))))
        val tags = Id3Tags.parse(t, null)
        assertEquals(listOf("front", "back"), tags.pictures.map { it.description })
        assertEquals(Id3Tags.FRONT_COVER, tags.pictures[0].type)
        assertArrayEquals(jpeg, tags.pictures[0].data)
    }

    @Test
    fun `whole-tag unsynchronisation in v2_3 is undone`() {
        val raw = frame(3, "APIC", latin1("image/jpeg") + byteArrayOf(0, 3, 0) + jpeg)
        // Stuff a zero after every 0xFF, as an unsynchronising writer would.
        val stuffed = ByteArrayOutputStream()
        for (b in raw) { stuffed.write(b.toInt()); if (b == 0xFF.toByte()) stuffed.write(0) }
        val t = tag(3, listOf(stuffed.toByteArray()), flags = 0x80)
        assertArrayEquals(jpeg, Id3Tags.parse(t, null).pictures.single().data)
    }

    @Test
    fun `comments keep only the plain one, user text becomes its own field`() {
        fun comm(desc: String, text: String) = byteArrayOf(0) + "eng".toByteArray() +
            desc.toByteArray() + byteArrayOf(0) + text.toByteArray()
        val txxx = latin1("REPLAYGAIN_TRACK_GAIN") + byteArrayOf(0) + "-6.5 dB".toByteArray()
        val t = tag(3, listOf(
            frame(3, "COMM", comm("iTunNORM", " 0000 0001")),
            frame(3, "COMM", comm("", "Nice one")),
            frame(3, "TXXX", txxx),
        ))
        val tags = Id3Tags.parse(t, null)
        assertEquals(listOf("Nice one"), tags.comments.filter { it.first == "COMMENT" }.map { it.second })
        assertEquals("-6.5 dB", tags.first("REPLAYGAIN_TRACK_GAIN"))
    }

    @Test
    fun `compressed and encrypted frames are skipped, not misread`() {
        val t = tag(3, listOf(
            frame(3, "TIT2", latin1("garbage"), flags = 0x0080),
            frame(3, "TPE1", latin1("Real")),
        ))
        val tags = Id3Tags.parse(t, null)
        assertNull(tags.first("TITLE"))
        assertEquals("Real", tags.first("ARTIST"))
    }

    @Test
    fun `ID3v1 fills only what v2 lacks`() {
        val v1 = ByteArray(128)
        "TAG".toByteArray().copyInto(v1, 0)
        "V1 Title".toByteArray().copyInto(v1, 3)
        "V1 Artist".toByteArray().copyInto(v1, 33)
        "V1 Album".toByteArray().copyInto(v1, 63)
        "1985".toByteArray().copyInto(v1, 93)
        v1[126] = 5 // ID3v1.1 track
        v1[127] = 8 // Jazz
        val t = tag(3, listOf(frame(3, "TIT2", latin1("V2 Title"))))
        val tags = Id3Tags.parse(t, byteArrayOf(1, 2, 3) + v1)
        assertEquals("V2 Title", tags.first("TITLE"))
        assertEquals("V1 Artist", tags.first("ARTIST"))
        assertEquals("V1 Album", tags.first("ALBUM"))
        assertEquals("1985", tags.first("DATE"))
        assertEquals("5", tags.first("TRACKNUMBER"))
        assertEquals("Jazz", tags.first("GENRE"))
    }

    @Test
    fun `no tags at all`() {
        assertEquals(0, Id3Tags.v2Length(ByteArray(10)))
        assertTrue(Id3Tags.parse(null, ByteArray(128)).isEmpty)
    }

    @Test
    fun `tag length includes header and v2_4 footer`() {
        val header = byteArrayOf('I'.code.toByte(), 'D'.code.toByte(), '3'.code.toByte(), 4, 0, 0x10) + syncsafe(1000)
        assertEquals(1020, Id3Tags.v2Length(header))
        val v3 = byteArrayOf('I'.code.toByte(), 'D'.code.toByte(), '3'.code.toByte(), 3, 0, 0) + syncsafe(1000)
        assertEquals(1010, Id3Tags.v2Length(v3))
    }

    @Test
    fun `genre references`() {
        assertEquals(listOf("Rock"), Id3Tags.genres("(17)"))
        assertEquals(listOf("Rock"), Id3Tags.genres("17"))
        assertEquals(listOf("Eurodisco"), Id3Tags.genres("(4)Eurodisco"))
        assertEquals(listOf("Rock", "Pop"), Id3Tags.genres("(17)(13)"))
        assertEquals(listOf("Remix"), Id3Tags.genres("(RX)"))
        assertEquals(listOf("(Live)"), Id3Tags.genres("((Live)"))
        assertEquals(listOf("Shoegaze"), Id3Tags.genres("Shoegaze"))
    }

    @Test
    fun `truncated and corrupt tags do not throw`() {
        val good = tag(3, listOf(frame(3, "TIT2", latin1("Song")), frame(3, "APIC", latin1("image/jpeg") + byteArrayOf(0, 3, 0) + jpeg)))
        for (cut in 0 until good.size) Id3Tags.parse(good.copyOf(cut), null)
        val rnd = java.util.Random(7)
        repeat(500) {
            val bad = good.copyOf()
            repeat(4) { bad[10 + rnd.nextInt(bad.size - 10)] = rnd.nextInt(256).toByte() }
            Id3Tags.parse(bad, null)
        }
    }
}
