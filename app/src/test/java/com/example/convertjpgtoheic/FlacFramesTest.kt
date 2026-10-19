package com.example.convertjpgtoheic

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File

class FlacFramesTest {

    private fun crc8(b: ByteArray): Int = FlacFrames.crc8(b, 0, b.size)

    private fun crc16(b: ByteArray): Int {
        var crc = 0
        for (x in b) {
            crc = crc xor ((x.toInt() and 0xFF) shl 8)
            repeat(8) { crc = if (crc and 0x8000 != 0) ((crc shl 1) xor 0x8005) and 0xFFFF else (crc shl 1) and 0xFFFF }
        }
        return crc
    }

    /** STREAMINFO for 44.1 kHz stereo 16-bit, 4096-sample blocks, [total] samples. */
    private fun streamInfo(total: Long): ByteArray {
        val b = ByteArray(34)
        b[0] = 0x10; b[1] = 0x00; b[2] = 0x10; b[3] = 0x00 // min and max block size 4096
        // 44100 Hz (20 bits) · 2 channels (3 bits, minus 1) · 16 bits (5 bits, minus 1) · total (36 bits)
        val packed = (44_100L shl 44) or (1L shl 41) or (15L shl 36) or total
        for (i in 0 until 8) b[10 + i] = (packed ushr (8 * (7 - i))).toByte()
        return b
    }

    /** A fixed-blocksize frame numbered [n] with [payload] standing in for the subframes. */
    private fun frame(n: Int, blockCode: Int, payload: ByteArray, explicitBlock: Int? = null): ByteArray {
        val h = ByteArrayOutputStream()
        h.write(0xFF); h.write(0xF8)
        h.write((blockCode shl 4) or 9) // 44.1 kHz
        h.write((1 shl 4) or (4 shl 1)) // left/right stereo, 16-bit
        require(n < 128); h.write(n) // UTF-8 frame number, one byte
        if (blockCode == 7) { h.write(explicitBlock!! - 1 ushr 8); h.write((explicitBlock - 1) and 0xFF) }
        h.write(crc8(h.toByteArray()))
        val body = h.toByteArray() + payload
        val crc = crc16(body)
        return body + byteArrayOf((crc ushr 8).toByte(), crc.toByte())
    }

    @Test
    fun `frames are split exactly, past sync patterns hidden in the audio`() {
        // Payloads salted with fake sync codes and fake headers that a naive search would split at.
        val fake = byteArrayOf(0xFF.toByte(), 0xF8.toByte(), 0xC9.toByte(), 0x18, 0x02, 0x00)
        val payloads = List(4) { i -> ByteArray(300 + 50 * i) { (it * 31 + i).toByte() } + fake + ByteArray(40) { 7 } }
        val total = 3L * 4096 + 1000
        val stream = ByteArrayOutputStream().apply {
            write("fLaC".toByteArray())
            write(byteArrayOf(0x80.toByte(), 0, 0, 34)); write(streamInfo(total))
            payloads.take(3).forEachIndexed { i, p -> write(frame(i, 12, p)) } // 12 → 4096 samples
            write(frame(3, 7, payloads[3], explicitBlock = 1000))
        }.toByteArray()

        val src = ByteArraySource(stream)
        val (info, start) = FlacFrames.open(src)!!
        assertEquals(44_100, info.sampleRate)
        assertEquals(2, info.channels)
        assertEquals(16, info.bitsPerSample)
        assertEquals(total, info.totalSamples)
        assertEquals(42L, start)

        val reader = FlacFrames.Reader(src, info, start)
        val frames = generateSequence { reader.next() }.toList()
        assertEquals(listOf(0L, 4096L, 8192L, 12288L), frames.map { it.firstSample })
        assertEquals(listOf(4096, 4096, 4096, 1000), frames.map { it.blockSize })
        assertEquals(stream.size.toLong(), frames.last().offset + frames.last().length)
        frames.zipWithNext().forEach { (a, b) -> assertEquals(a.offset + a.length, b.offset) }
    }

    @Test
    fun `a damaged frame is refused, not passed on`() {
        val stream = "fLaC".toByteArray() + byteArrayOf(0x80.toByte(), 0, 0, 34) + streamInfo(4096) +
            frame(0, 12, ByteArray(200) { it.toByte() })
        stream[stream.size - 50] = (stream[stream.size - 50] + 1).toByte()
        val src = ByteArraySource(stream)
        val (info, start) = FlacFrames.open(src)!!
        assertThrows(FlacFrames.FormatException::class.java) { FlacFrames.Reader(src, info, start).next() }
    }

    /** Skipped unless `FLAC_SAMPLES_DIR` names a folder of `.flac` files. */
    @Test
    fun `real files split into every frame, end to end`() {
        val dir = System.getenv("FLAC_SAMPLES_DIR")?.let(::File)
        assumeTrue("set FLAC_SAMPLES_DIR to run", dir != null && dir.isDirectory)
        for (f in dir!!.listFiles { f -> f.name.endsWith(".flac", ignoreCase = true) }!!.sorted()) {
            val src = ByteArraySource(f.readBytes())
            val (info, start) = FlacFrames.open(src)!!
            val reader = FlacFrames.Reader(src, info, start)
            val frames = generateSequence { reader.next() }.toList()
            var expected = start
            var samples = 0L
            for (fr in frames) {
                assertEquals("gap or overlap at frame ${frames.indexOf(fr)}", expected, fr.offset)
                assertEquals(samples, fr.firstSample)
                expected += fr.length
                samples += fr.blockSize
            }
            println("${f.name}: ${frames.size} frames, $samples samples (STREAMINFO ${info.totalSamples}), ends at $expected of ${f.length()}")
            assertEquals(f.length(), expected)
            assertEquals(info.totalSamples, samples)
        }
    }
}
