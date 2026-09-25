package com.example.convertjpgtoheic

import com.example.convertjpgtoheic.MetadataTestData.Tag
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime
import java.time.ZoneOffset

class ExifEditorTest {

    // region reading

    @Test
    fun `reads what a lean Samsung block records`() {
        val s = ExifEditor.summarise(MetadataTestData.samsungLikeExif(6, "2017:07:28 08:20:28"))!!
        assertEquals(6, s.orientation)
        assertEquals("2017:07:28 08:20:28", s.dateTimeOriginal)
        assertFalse(s.hasOffsetTimeOriginal)
        assertFalse(s.hasGpsTimestamp)
    }

    @Test
    fun `PhotoScan has no DateTimeOriginal but offers DateTimeDigitized`() {
        val s = ExifEditor.summarise(MetadataTestData.photoScanExif("2018:12:18 15:13:41"))!!
        assertNull(s.dateTimeOriginal)
        assertEquals("2018:12:18 15:13:41", s.fallbackDateTime)
    }

    @Test
    fun `a camera's all-zero date counts as no date`() {
        val block = MetadataTestData.exifBlock(
            ifd0 = listOf(Tag.Ascii(0x0132, "0000:00:00 00:00:00")),
            exif = listOf(Tag.Ascii(0x9003, "0000:00:00 00:00:00")),
        )
        val s = ExifEditor.summarise(block)!!
        assertNull(s.dateTimeOriginal)
        assertNull(s.fallbackDateTime)
    }

    @Test
    fun `rejects blocks that are not EXIF`() {
        assertNull(ExifEditor.summarise(ByteArray(40)))
        assertNull(ExifEditor.summarise("Exif\u0000\u0000XXXX".toByteArray(Charsets.ISO_8859_1)))
    }

    // endregion

    // region editing

    @Test
    fun `patches Orientation in place without growing the block`() {
        val block = MetadataTestData.samsungLikeExif(1, "2017:07:28 08:20:28")
        val edited = ExifEditor.edit(block, orientation = 6)!!
        assertEquals(block.size, edited.size)
        assertEquals(6, ExifEditor.summarise(edited)!!.orientation)
    }

    @Test
    fun `adds the timezone and keeps every existing value readable`() {
        val block = MetadataTestData.samsungLikeExif(6, "2017:12:08 14:26:56")
        val edited = ExifEditor.edit(block, offset = ZoneOffset.ofHours(13))!!

        val s = ExifEditor.summarise(edited)!!
        assertTrue(s.hasOffsetTimeOriginal)
        assertEquals("2017:12:08 14:26:56", s.dateTimeOriginal)
        assertEquals(6, s.orientation)
        assertTrue(readAscii(edited, 0x9011) == "+13:00")
        assertTrue(readAscii(edited, 0x9010) == "+13:00")
        assertEquals("samsung", readAscii(edited, 0x010F, inExifIfd = false))
        assertEquals("SM-G930F", readAscii(edited, 0x0110, inExifIfd = false))
    }

    @Test
    fun `adds DateTimeOriginal where PhotoScan left only DateTime`() {
        val edited = ExifEditor.edit(
            MetadataTestData.photoScanExif("2018:12:18 15:13:41"),
            dateTimeOriginal = "2018:12:18 15:13:41",
            offset = ZoneOffset.ofHours(13),
        )!!
        val s = ExifEditor.summarise(edited)!!
        assertEquals("2018:12:18 15:13:41", s.dateTimeOriginal)
        assertTrue(s.hasOffsetTimeOriginal)
        assertEquals("GooglePhotoScan", readAscii(edited, 0x0131, inExifIfd = false))
    }

    @Test
    fun `adding Orientation and a timezone together keeps both`() {
        // No Orientation tag, so IFD0 has to be rebuilt as well as the Exif IFD. The rebuilt IFD0
        // must carry the Exif pointer as repointed, not the original one.
        val block = MetadataTestData.exifBlock(
            ifd0 = listOf(Tag.Ascii(0x010F, "samsung"), Tag.Ascii(0x0132, "2016:07:29 13:29:33")),
            exif = listOf(Tag.Ascii(0x9003, "2016:07:29 13:29:33")),
        )
        val edited = ExifEditor.edit(block, orientation = 8, offset = ZoneOffset.ofHours(12))!!
        val s = ExifEditor.summarise(edited)!!
        assertEquals(8, s.orientation)
        assertTrue(s.hasOffsetTimeOriginal)
        assertEquals("2016:07:29 13:29:33", s.dateTimeOriginal)
        assertEquals("samsung", readAscii(edited, 0x010F, inExifIfd = false))
    }

    @Test
    fun `creates an Exif IFD when the block has none`() {
        val block = MetadataTestData.exifBlock(ifd0 = listOf(Tag.Ascii(0x0132, "2019:01:04 15:47:12")))
        assertFalse(ExifEditor.summarise(block)!!.hasExifIfd)
        val edited = ExifEditor.edit(block, dateTimeOriginal = "2019:01:04 15:47:12", offset = ZoneOffset.ofHours(13))!!
        val s = ExifEditor.summarise(edited)!!
        assertTrue(s.hasExifIfd)
        assertEquals("2019:01:04 15:47:12", s.dateTimeOriginal)
        assertTrue(s.hasOffsetTimeOriginal)
    }

    @Test
    fun `works on big-endian blocks too`() {
        val block = MetadataTestData.exifBlock(
            ifd0 = listOf(Tag.Short(0x0112, 1), Tag.Ascii(0x010F, "Canon")),
            exif = listOf(Tag.Ascii(0x9003, "2014:05:25 13:18:00")),
            littleEndian = false,
        )
        val edited = ExifEditor.edit(block, orientation = 6, offset = ZoneOffset.ofHours(12))!!
        val s = ExifEditor.summarise(edited)!!
        assertEquals(6, s.orientation)
        assertTrue(s.hasOffsetTimeOriginal)
        assertEquals("Canon", readAscii(edited, 0x010F, inExifIfd = false, littleEndian = false))
    }

    @Test
    fun `does not depend on IFD entries being sorted`() {
        val block = MetadataTestData.exifBlock(
            ifd0 = listOf(Tag.Short(0x0112, 1), Tag.Ascii(0x0110, "SM-G930F"), Tag.Ascii(0x010F, "samsung")),
            exif = listOf(Tag.Ascii(0x9003, "2017:07:28 08:20:28")),
            sorted = false,
        )
        val edited = ExifEditor.edit(block, orientation = 6, offset = ZoneOffset.ofHours(12))!!
        val s = ExifEditor.summarise(edited)!!
        assertEquals(6, s.orientation)
        assertTrue(s.hasOffsetTimeOriginal)
    }

    @Test
    fun `never overwrites an existing date or timezone`() {
        val block = MetadataTestData.exifBlock(
            ifd0 = listOf(Tag.Ascii(0x010F, "samsung")),
            exif = listOf(Tag.Ascii(0x9003, "2022:02:25 17:45:23"), Tag.Ascii(0x9011, "+13:00")),
        )
        val edited = ExifEditor.edit(block, dateTimeOriginal = "1999:01:01 00:00:00", offset = ZoneOffset.ofHours(-5))!!
        assertEquals("2022:02:25 17:45:23", ExifEditor.summarise(edited)!!.dateTimeOriginal)
        assertEquals("+13:00", readAscii(edited, 0x9011))
    }

    @Test
    fun `an edit that asks for nothing returns the block unchanged`() {
        val block = MetadataTestData.samsungLikeExif(6, "2017:07:28 08:20:28")
        assertArrayEquals(block, ExifEditor.edit(block))
    }

    @Test
    fun `refuses to add a malformed date`() {
        assertNull(ExifEditor.edit(MetadataTestData.photoScanExif("2018:12:18 15:13:41"), dateTimeOriginal = "yesterday"))
    }

    @Test
    fun `MinimalExif output gains a timezone`() {
        val utc = 1_512_696_416_000L // 2017-12-08 01:26:56Z
        val block = MinimalExif.forDate(utc, java.util.TimeZone.getTimeZone("Pacific/Auckland"))
        val offset = ExifEditor.offsetFor(ExifEditor.summarise(block)!!.dateTimeOriginal!!, utc)
        val edited = ExifEditor.edit(block, offset = offset)!!
        assertTrue(ExifEditor.summarise(edited)!!.hasOffsetTimeOriginal)
        assertEquals("+13:00", readAscii(edited, 0x9011))
    }

    // endregion

    // region timezones

    @Test
    fun `offset is exact when the true instant is known`() {
        // PhotoScan: 1545099221420 ms is 02:13:41Z; the phone recorded 15:13:41 local.
        assertEquals(ZoneOffset.ofHours(13), ExifEditor.offsetFor("2018:12:18 15:13:41", 1_545_099_221_420L))
        // A photo taken abroad keeps its own zone when the instant is known.
        assertEquals(ZoneOffset.ofHours(-7), ExifEditor.offsetFor("2019:06:01 09:00:00", instant("2019-06-01T16:00:00Z")))
    }

    @Test
    fun `falls back to New Zealand time with daylight saving by date`() {
        assertEquals(ZoneOffset.ofHours(13), ExifEditor.offsetFor("2017:12:08 14:26:56", null)) // summer
        assertEquals(ZoneOffset.ofHours(12), ExifEditor.offsetFor("2017:07:28 08:20:28", null)) // winter
    }

    @Test
    fun `an instant too far from the wall clock is not trusted`() {
        // A modified-time fallback years after capture says nothing about the zone.
        assertEquals(ZoneOffset.ofHours(12), ExifEditor.offsetFor("2017:07:28 08:20:28", instant("2026-09-19T00:00:00Z")))
    }

    @Test
    fun `rounds small clock skew to a real offset`() {
        val local = LocalDateTime.of(2018, 12, 18, 15, 13, 41)
        assertEquals(ZoneOffset.ofHours(13), ExifEditor.exactOffset(local, 1_545_099_221_420L + 40_000))
    }

    @Test
    fun `formats offsets the way EXIF readers expect`() {
        assertEquals("+13:00", ExifEditor.formatOffset(ZoneOffset.ofHours(13)))
        assertEquals("-05:30", ExifEditor.formatOffset(ZoneOffset.ofHoursMinutes(-5, -30)))
        assertEquals("+00:00", ExifEditor.formatOffset(ZoneOffset.UTC))
    }

    @Test
    fun `renders an instant as New Zealand wall-clock time`() {
        assertEquals("2018:12:18 15:13:41", ExifEditor.formatExifDate(1_545_099_221_420L))
    }

    // endregion

    // region helpers

    private fun instant(iso: String) = java.time.Instant.parse(iso).toEpochMilli()

    /** Reads an ASCII tag straight from the bytes, independently of the code under test. */
    private fun readAscii(block: ByteArray, tag: Int, inExifIfd: Boolean = true, littleEndian: Boolean = true): String? {
        fun u16(at: Int): Int {
            val a = block[6 + at].toInt() and 0xFF
            val b = block[6 + at + 1].toInt() and 0xFF
            return if (littleEndian) (b shl 8) or a else (a shl 8) or b
        }
        fun u32(at: Int): Int {
            var v = 0
            for (i in 0 until 4) {
                val byte = block[6 + at + i].toInt() and 0xFF
                v = if (littleEndian) v or (byte shl (8 * i)) else (v shl 8) or byte
            }
            return v
        }
        fun entries(ifd: Int) = (0 until u16(ifd)).map { ifd + 2 + it * 12 }
        var ifd = u32(4)
        if (inExifIfd) ifd = u32(entries(ifd).first { u16(it) == 0x8769 } + 8)
        val entry = entries(ifd).firstOrNull { u16(it) == tag } ?: return null
        val count = u32(entry + 4)
        val at = if (count <= 4) entry + 8 else u32(entry + 8)
        return String(block, 6 + at, count - 1, Charsets.ISO_8859_1)
    }

    // endregion

    @Test
    fun `helper reads what the builder wrote`() {
        assertNotNull(readAscii(MetadataTestData.photoScanExif("2018:12:18 15:13:41"), 0x9004))
    }
}
