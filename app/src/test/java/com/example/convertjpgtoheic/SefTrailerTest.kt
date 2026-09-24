package com.example.convertjpgtoheic

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.assertFalse
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Exercises the SEF trailer parser against synthetic trailers built to the same byte layout as the
 * real Galaxy S21 files the format was reverse-engineered from.
 */
class SefTrailerTest {

    private class Record(val type: Int, val name: String, val payload: ByteArray) {
        /** u16 unk ; u16 type ; u32 nameLen ; name ; payload */
        fun bytes(): ByteArray {
            val nameBytes = name.toByteArray(Charsets.ISO_8859_1)
            val buf = ByteBuffer.allocate(8 + nameBytes.size + payload.size).order(ByteOrder.LITTLE_ENDIAN)
            buf.putShort(0)
            buf.putShort(type.toShort())
            buf.putInt(nameBytes.size)
            buf.put(nameBytes)
            buf.put(payload)
            return buf.array()
        }
    }

    /** Builds `[image][records…][SEFH directory][dir_size][SEFT]`, the observed on-disk shape. */
    private fun buildFile(imageLen: Int, records: List<Record>): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(ByteArray(imageLen) { 0x11 })

        val recordBytes = records.map { it.bytes() }
        val recordStart = IntArray(records.size)
        var pos = imageLen
        for (i in records.indices) {
            recordStart[i] = pos
            out.write(recordBytes[i])
            pos += recordBytes[i].size
        }
        val sefhPos = pos

        val dir = ByteBuffer.allocate(12 + records.size * 12).order(ByteOrder.LITTLE_ENDIAN)
        dir.put("SEFH".toByteArray(Charsets.ISO_8859_1))
        dir.putInt(0x6b)
        dir.putInt(records.size)
        for (i in records.indices) {
            dir.putShort(0)                                 // flags
            dir.putShort(records[i].type.toShort())         // type
            dir.putInt(sefhPos - recordStart[i])            // offset back from SEFH
            dir.putInt(recordBytes[i].size)                 // record size
        }
        val dirBytes = dir.array()
        out.write(dirBytes)

        val footer = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN)
        footer.putInt(dirBytes.size)
        footer.put("SEFT".toByteArray(Charsets.ISO_8859_1))
        out.write(footer.array())

        return out.toByteArray()
    }

    @Test
    fun `finds the video and the trailer start of a motion photo`() {
        val imageLen = 500
        val file = buildFile(
            imageLen,
            listOf(
                Record(0x0a01, "Image_UTC_Data", "1788746908069".toByteArray()),
                Record(0x0a30, "MotionPhoto_Data", ByteArray(2048) { 0x22 }), // stands in for the MP4
                Record(0x0a31, "MotionPhoto_Version", "mpv3".toByteArray()),
            ),
        )

        val info = SefTrailer.parse(file, file.size.toLong())

        assertTrue(info != null && info.hasVideo)
        assertEquals(imageLen.toLong(), info!!.sefStart)
    }

    @Test
    fun `a plain samsung photo has a trailer but no video`() {
        val imageLen = 300
        val file = buildFile(
            imageLen,
            listOf(
                Record(0x0a01, "Image_UTC_Data", "1788847172644".toByteArray()),
                Record(0x0aa1, "MCC_Data", "530".toByteArray()),
            ),
        )

        val info = SefTrailer.parse(file, file.size.toLong())

        assertTrue(info != null)
        assertFalse(info!!.hasVideo)
        assertEquals(imageLen.toLong(), info.sefStart)
    }

    @Test
    fun `detects the video by name even when only the tail is available`() {
        // A real video record sits megabytes back, outside the tail; detection must still work off
        // the directory's type, and the trailer start must be computed from the file size.
        val imageLen = 2_000_000
        val file = buildFile(
            imageLen,
            listOf(
                Record(0x0a30, "MotionPhoto_Data", ByteArray(1_500_000) { 0x22 }),
                Record(0x0a31, "MotionPhoto_Version", "mpv3".toByteArray()),
            ),
        )
        // Only hand the parser the last 8 KB, as the app does.
        val tail = file.copyOfRange(file.size - SefTrailer.TAIL_BYTES, file.size)

        val info = SefTrailer.parse(tail, file.size.toLong())

        assertTrue(info != null && info.hasVideo)
        assertEquals(imageLen.toLong(), info!!.sefStart)
    }

    @Test
    fun `plain bytes are not a SEF trailer`() {
        assertNull(SefTrailer.parse(ByteArray(4096) { 0x5A }, 4096))
    }

    @Test
    fun `a truncated tail that omits the directory is rejected`() {
        val file = buildFile(100, listOf(Record(0x0a30, "MotionPhoto_Data", ByteArray(64))))
        // Last 8 bytes only: the SEFT footer is present but the SEFH directory is out of reach.
        val tail = file.copyOfRange(file.size - 8, file.size)

        assertNull(SefTrailer.parse(tail, file.size.toLong()))
    }
}
