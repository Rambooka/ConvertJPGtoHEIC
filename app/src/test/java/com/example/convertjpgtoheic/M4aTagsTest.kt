package com.example.convertjpgtoheic

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File

class M4aTagsTest {

    private fun box(type: String, vararg body: ByteArray): ByteArray {
        val content = body.fold(ByteArray(0)) { acc, b -> acc + b }
        val out = ByteArrayOutputStream()
        val size = 8 + content.size
        out.write(byteArrayOf((size ushr 24).toByte(), (size ushr 16).toByte(), (size ushr 8).toByte(), size.toByte()))
        out.write(type.toByteArray(Charsets.ISO_8859_1))
        out.write(content)
        return out.toByteArray()
    }

    private fun data(type: Int, value: ByteArray) = box("data", byteArrayOf(0, 0, 0, type.toByte(), 0, 0, 0, 0), value)
    private fun text(atom: String, value: String) = box(atom, data(1, value.toByteArray(Charsets.UTF_8)))

    /** ftyp, then [ilst] inside moov/udta/meta, with mdat in between as iTunes lays it out when moov is last. */
    private fun file(ilst: ByteArray, moovLast: Boolean = false): ByteArray {
        val ftyp = box("ftyp", "M4A ".toByteArray(), ByteArray(4), "M4A mp42isom".toByteArray())
        val meta = box("meta", ByteArray(4), box("hdlr", ByteArray(25)), ilst)
        val moov = box("moov", box("mvhd", ByteArray(100)), box("udta", meta))
        val mdat = box("mdat", ByteArray(500) { it.toByte() })
        return if (moovLast) ftyp + mdat + moov else ftyp + moov + mdat
    }

    private val cover = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 1, 2, 3)

    private val ilst = box(
        "ilst",
        text("©nam", "Salute"),
        text("©ART", "Little Mix "),
        text("aART", "Little Mix"),
        text("©alb", "Salute (Deluxe Edition)"),
        box("gnre", data(0, byteArrayOf(0, 14))),
        box("trkn", data(0, byteArrayOf(0, 0, 0, 1, 0, 12, 0, 0))),
        box("disk", data(0, byteArrayOf(0, 0, 0, 1, 0, 2))),
        box("cpil", data(21, byteArrayOf(0))),
        text("©day", "2013-11-08T08:00:00Z"),
        box(
            "----",
            box("mean", ByteArray(4), "com.apple.iTunes".toByteArray()),
            box("name", ByteArray(4), "iTunSMPB".toByteArray()),
            data(1, " 00000000 00000840".toByteArray()),
        ),
        box(
            "----",
            box("mean", ByteArray(4), "com.apple.iTunes".toByteArray()),
            box("name", ByteArray(4), "MusicBrainz Track Id".toByteArray()),
            data(1, "abc-123".toByteArray()),
        ),
        box("covr", data(13, cover), data(14, byteArrayOf(0x89.toByte(), 'P'.code.toByte()))),
    )

    @Test
    fun `iTunes tags become the same Vorbis comments an MP3 would give`() {
        val tags = M4aTags.read(ByteArraySource(file(ilst)))!!
        assertEquals(
            listOf(
                "TITLE" to "Salute",
                "ARTIST" to "Little Mix",
                "ALBUMARTIST" to "Little Mix",
                "ALBUM" to "Salute (Deluxe Edition)",
                "GENRE" to "Pop", // gnre is the ID3v1 index plus one: 14 → 13, Pop
                "TRACKNUMBER" to "1",
                "TRACKTOTAL" to "12",
                "DISCNUMBER" to "1",
                "DISCTOTAL" to "2",
                "DATE" to "2013-11-08",
                "MUSICBRAINZ TRACK ID" to "abc-123", // named as an MP3's TXXX would be

            ),
            tags.comments,
        )
        assertEquals(2, tags.pictures.size)
        assertEquals("image/jpeg", tags.pictures[0].mime)
        assertEquals(Id3Tags.FRONT_COVER, tags.pictures[0].type)
        assertArrayEquals(cover, tags.pictures[0].data)
        assertEquals("image/png", tags.pictures[1].mime)
        assertEquals(0, tags.pictures[1].type)
    }

    @Test
    fun `moov after mdat is found`() {
        assertEquals("Salute", M4aTags.read(ByteArraySource(file(ilst, moovLast = true)))!!.first("TITLE"))
    }

    @Test
    fun `a file with no tags reads as empty, and a non-MP4 as unreadable`() {
        val bare = box("ftyp", "M4A ".toByteArray(), ByteArray(4)) + box("moov", box("mvhd", ByteArray(100)))
        assertTrue(M4aTags.read(ByteArraySource(bare))!!.isEmpty)
        assertNull(M4aTags.read(ByteArraySource(ByteArray(64) { 0x41 })))
    }

    @Test
    fun `isMp4 tells an M4A from an MP3`() {
        assertTrue(M4aTags.isMp4(file(ilst).copyOf(10)))
        assertFalse(M4aTags.isMp4("ID3\u0003\u0000\u0000\u0000\u0000\u0000\u0000".toByteArray(Charsets.ISO_8859_1)))
    }

    /** Skipped unless `M4A_SAMPLES_DIR` names a folder of `.m4a` files, so no music is checked in. */
    @Test
    fun `reads real files`() {
        val dir = System.getenv("M4A_SAMPLES_DIR")?.let(::File)
        assumeTrue("set M4A_SAMPLES_DIR to run", dir != null && dir.isDirectory)
        for (f in dir!!.listFiles { f -> f.name.endsWith(".m4a", ignoreCase = true) }!!.sorted()) {
            val tags = M4aTags.read(ByteArraySource(f.readBytes()))!!
            println("${f.name}: ${tags.comments} + ${tags.pictures.map { "${it.mime} ${it.type} ${it.data.size} B" }}")
            assertTrue(tags.first("TITLE") != null && tags.pictures.isNotEmpty())
        }
    }
}
