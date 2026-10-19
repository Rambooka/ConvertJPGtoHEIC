package com.example.convertjpgtoheic

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File

class FlacTagsTest {

    private fun le32(v: Int) = byteArrayOf(v.toByte(), (v ushr 8).toByte(), (v ushr 16).toByte(), (v ushr 24).toByte())
    private fun be32(v: Int) = byteArrayOf((v ushr 24).toByte(), (v ushr 16).toByte(), (v ushr 8).toByte(), v.toByte())

    private fun block(type: Int, body: ByteArray, last: Boolean = false): ByteArray =
        byteArrayOf(((if (last) 0x80 else 0) or type).toByte(), (body.size ushr 16).toByte(), (body.size ushr 8).toByte(), body.size.toByte()) + body

    private fun comments(vararg c: String): ByteArray {
        val out = ByteArrayOutputStream()
        val vendor = "reference libFLAC 1.3.2".toByteArray()
        out.write(le32(vendor.size)); out.write(vendor)
        out.write(le32(c.size))
        for (s in c) { val b = s.toByteArray(); out.write(le32(b.size)); out.write(b) }
        return out.toByteArray()
    }

    private fun picture(type: Int, mime: String, data: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(be32(type)); out.write(be32(mime.length)); out.write(mime.toByteArray())
        out.write(be32(0)) // no description
        repeat(4) { out.write(be32(0)) }
        out.write(be32(data.size)); out.write(data)
        return out.toByteArray()
    }

    private val cover = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 9, 9)

    private val flac = "fLaC".toByteArray() +
        block(0, ByteArray(34)) +
        block(3, ByteArray(18)) +
        block(4, comments("TITLE=Champagne Problems", "album=Smile", "DATE=2020", "EMPTY=", "METADATA_BLOCK_PICTURE=xyz")) +
        block(6, picture(0, "image/png", byteArrayOf(1))) +
        block(6, picture(3, "image/jpeg", cover)) +
        block(1, ByteArray(100), last = true) +
        ByteArray(500) // audio frames

    @Test
    fun `Vorbis comments pass through with upper-cased keys, and the front cover comes first`() {
        val tags = FlacTags.read(ByteArraySource(flac))!!
        assertEquals(listOf("TITLE" to "Champagne Problems", "ALBUM" to "Smile", "DATE" to "2020"), tags.comments)
        assertEquals(listOf(Id3Tags.FRONT_COVER, 0), tags.pictures.map { it.type })
        assertEquals("image/jpeg", tags.pictures[0].mime)
        assertArrayEquals(cover, tags.pictures[0].data)
    }

    @Test
    fun `not FLAC, or a truncated block, reads as unreadable`() {
        assertNull(FlacTags.read(ByteArraySource("ID3\u0003 not flac at all".toByteArray())))
        assertNull(FlacTags.read(ByteArraySource(flac.copyOf(60))))
    }

    /** Skipped unless `FLAC_SAMPLES_DIR` names a folder of `.flac` files, so no music is checked in. */
    @Test
    fun `reads real files`() {
        val dir = System.getenv("FLAC_SAMPLES_DIR")?.let(::File)
        assumeTrue("set FLAC_SAMPLES_DIR to run", dir != null && dir.isDirectory)
        for (f in dir!!.listFiles { f -> f.name.endsWith(".flac", ignoreCase = true) }!!.sorted()) {
            val tags = FlacTags.read(ByteArraySource(f.readBytes()))!!
            println("${f.name}: ${tags.comments} + ${tags.pictures.map { "${it.mime} ${it.type} ${it.data.size} B" }}")
            assertTrue(tags.first("TITLE") != null && tags.pictures.isNotEmpty())
        }
    }
}
