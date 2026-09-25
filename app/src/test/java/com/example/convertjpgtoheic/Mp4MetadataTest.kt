package com.example.convertjpgtoheic

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.nio.ByteBuffer

class Mp4MetadataTest {

    // region synthetic MP4s, laid out like the S21's camera output

    private fun box(type: String, body: ByteArray) = ByteBuffer.allocate(4).putInt(8 + body.size).array() +
        type.toByteArray(Charsets.ISO_8859_1) + body
    private fun full(type: String, version: Int, body: ByteArray) = box(type, byteArrayOf(version.toByte(), 0, 0, 0) + body)
    private fun u32(v: Long) = ByteBuffer.allocate(4).putInt(v.toInt()).array()
    private fun u16(v: Int) = byteArrayOf((v shr 8).toByte(), v.toByte())

    private val created = 3_841_000_000L // 2025-09-18 in MP4 seconds

    private fun matrix(rotation: Int): ByteArray {
        val (a, b, c, d) = when (rotation) {
            90 -> listOf(0, 0x10000, -0x10000, 0)
            180 -> listOf(-0x10000, 0, 0, -0x10000)
            270 -> listOf(0, -0x10000, 0x10000, 0)
            else -> listOf(0x10000, 0, 0, 0x10000)
        }
        return ByteBuffer.allocate(36).putInt(a).putInt(b).putInt(0).putInt(c).putInt(d).putInt(0)
            .putInt(0).putInt(0).putInt(0x40000000).array()
    }

    private fun trak(handler: String, codec: String, rotation: Int, timescale: Int, duration: Long): ByteArray {
        val tkhd = full("tkhd", 0, u32(created) + u32(created) + u32(1) + u32(0) + u32(duration) + ByteArray(8) +
            u16(0) + u16(0) + u16(0) + u16(0) + matrix(rotation) + u32(1920L shl 16) + u32(1080L shl 16))
        val mdhd = full("mdhd", 0, u32(created) + u32(created) + u32(timescale.toLong()) + u32(duration) + u16(0x55c4) + u16(0))
        val hdlr = full("hdlr", 0, u32(0) + handler.toByteArray(Charsets.ISO_8859_1) + ByteArray(12) + byteArrayOf(0))
        val entry = box(codec, ByteArray(70))
        val stsd = full("stsd", 0, u32(1) + entry)
        val stbl = box("stbl", stsd)
        val minf = box("minf", stbl)
        return box("trak", tkhd + box("mdia", mdhd + hdlr + minf))
    }

    private fun mp4(
        codec: String = "avc1",
        rotation: Int = 90,
        location: String? = "-36.8466+174.6200/",
        audio: Boolean = true,
        sefd: Boolean = false,
    ): ByteArray {
        val mvhd = full("mvhd", 0, u32(created) + u32(created) + u32(1000) + u32(5000) + ByteArray(80))
        val udta = if (location == null) ByteArray(0) else box("udta", box("©xyz", u16(location.length) + u16(0x15c7) + location.toByteArray(Charsets.ISO_8859_1)))
        val tracks = trak("vide", codec, rotation, 90000, 450000) + (if (audio) trak("soun", "mp4a", 0, 48000, 240000) else ByteArray(0))
        val moov = box("moov", mvhd + udta + tracks)
        val special = if (sefd) box("sefd", ByteArray(20)) else ByteArray(0)
        return box("ftyp", "isom".toByteArray() + ByteArray(4)) + box("mdat", ByteArray(500)) + moov + special
    }

    // endregion

    @Test
    fun `reads what conversion needs from a camera video`() {
        val info = Mp4Metadata.read(ByteArraySource(mp4()))!!
        assertEquals(created, info.creationTime)
        assertEquals(5.0, info.durationSeconds!!, 0.001)
        assertEquals("avc1", info.video!!.codec)
        assertEquals(90, info.video!!.rotationDegrees)
        assertEquals("mp4a", info.audio!!.codec)
        assertEquals("-36.8466+174.6200/", info.location)
        assertFalse(info.hasSamsungTrailer)
    }

    @Test
    fun `reads every rotation`() {
        for (r in listOf(0, 90, 180, 270)) assertEquals(r, Mp4Metadata.read(ByteArraySource(mp4(rotation = r)))!!.video!!.rotationDegrees)
    }

    @Test
    fun `spots Samsung special modes`() {
        assertTrue(Mp4Metadata.read(ByteArraySource(mp4(sefd = true)))!!.hasSamsungTrailer)
    }

    @Test
    fun `a video with no sound or location is still read`() {
        val info = Mp4Metadata.read(ByteArraySource(mp4(audio = false, location = null)))!!
        assertNull(info.audio)
        assertNull(info.location)
        assertNotNull(info.video)
    }

    @Test
    fun `patching the capture time changes only the time fields`() {
        val original = mp4()
        val info = Mp4Metadata.read(ByteArraySource(original))!!
        // Creation and modification in mvhd, plus in each track's tkhd and mdhd: 2 + 2 tracks x 4.
        assertEquals(10, info.timeFields.size)
        val target = Mp4Metadata.toMp4Seconds(1_632_000_000_000L)
        val patched = MetadataTestData.applied(original, Mp4Metadata.timePatches(info, target))
        assertEquals(original.size, patched.size)

        val after = Mp4Metadata.read(ByteArraySource(patched))!!
        assertEquals(target, after.creationTime)
        assertEquals(info.video, after.video)
        assertEquals(info.audio!!.codec, after.audio!!.codec)
        assertEquals(info.location, after.location)
        // Only the 4-byte time fields differ.
        val changed = original.indices.count { original[it] != patched[it] }
        assertTrue(changed in 1..info.timeFields.size * 4)
    }

    @Test
    fun `converts between MP4 and Unix time`() {
        // The slow-mo pulled from the phone: mvhd says 2022-10-05 00:38:23 UTC.
        val ms = java.time.Instant.parse("2022-10-05T00:38:23Z").toEpochMilli()
        assertEquals(ms, Mp4Metadata.toUnixMs(Mp4Metadata.toMp4Seconds(ms)))
    }

    @Test
    fun `not an MP4`() {
        assertNull(Mp4Metadata.read(ByteArraySource(ByteArray(200))))
    }

    /** Real camera videos, when `VIDEO_SAMPLES_DIR` points at some. Never checked in. */
    @Test
    fun `reads real camera videos`() {
        val dir = System.getenv("VIDEO_SAMPLES_DIR")?.let(::File)
        assumeTrue(dir != null && dir.isDirectory)
        for (f in dir!!.listFiles { x -> x.name.endsWith(".mp4") && !x.name.startsWith("tail_") }!!.sorted()) {
            val info = java.io.RandomAccessFile(f, "r").use { raf ->
                Mp4Metadata.read(object : ByteSource {
                    override val size = raf.length()
                    override fun read(position: Long, length: Int) = ByteArray(length).also { raf.seek(position); raf.readFully(it) }
                })
            }
            println("${f.name}: $info")
            assertNotNull(f.name, info)
        }
    }
}
