package com.example.convertjpgtoheic

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneOffset

/** The repair's decisions: what gets changed, and what is deliberately left alone. */
class HeicRepairTest {

    private fun diagnose(
        exif: ByteArray,
        rotationCcw: Int = 270,
        mirrored: Boolean = false,
        name: String = "20170728_082028.heic",
        undated: Boolean = false,
        known: Long? = null,
    ) = HeicRepair.diagnose(
        ByteArraySource(MetadataTestData.heif(exif, rotationCcw, mirrored).bytes), name, undated, known,
    )

    private fun needs(d: HeicRepair.Diagnosis) = (d as HeicRepair.Diagnosis.Needs).fix

    // region rotation

    @Test
    fun `the converter's old output gets an EXIF orientation matching the container`() {
        assertEquals(6, needs(diagnose(MetadataTestData.samsungLikeExif(1, "2017:07:28 08:20:28"), 270)).orientation)
        assertEquals(8, needs(diagnose(MetadataTestData.samsungLikeExif(1, "2017:12:08 14:26:56"), 90)).orientation)
        assertEquals(3, needs(diagnose(MetadataTestData.samsungLikeExif(1, "2017:12:08 14:26:56"), 180)).orientation)
    }

    @Test
    fun `a file that already agrees is fine`() {
        // How Samsung's camera writes HEIC: container and EXIF both say "turn 90 clockwise".
        assertEquals(HeicRepair.Diagnosis.Fine, diagnose(MetadataTestData.samsungLikeExif(6, "2026:09:12 08:49:15"), 270))
        // No rotation anywhere.
        assertEquals(HeicRepair.Diagnosis.Fine, diagnose(MetadataTestData.samsungLikeExif(1, "2020:10:10 14:44:57"), 0))
    }

    @Test
    fun `other disagreements are left for a person to judge`() {
        assertEquals(HeicRepair.Diagnosis.Fine, diagnose(MetadataTestData.samsungLikeExif(3, "2017:07:28 08:20:28"), 270))
        assertEquals(HeicRepair.Diagnosis.Fine, diagnose(MetadataTestData.samsungLikeExif(1, "2017:07:28 08:20:28"), 270, mirrored = true))
    }

    // endregion

    // region dates

    @Test
    fun `an undated photo gets the timezone for its date`() {
        val fix = needs(diagnose(MetadataTestData.samsungLikeExif(6, "2017:12:08 14:26:56"), undated = true))
        assertNull(fix.dateTimeOriginal)
        assertEquals(ZoneOffset.ofHours(13), fix.offset)
        assertNull(fix.orientation)
    }

    @Test
    fun `a surviving original pins the timezone exactly`() {
        val abroad = java.time.Instant.parse("2019-06-01T16:00:00Z").toEpochMilli()
        val fix = needs(diagnose(MetadataTestData.samsungLikeExif(6, "2019:06:01 09:00:00"), undated = true, known = abroad))
        assertEquals(ZoneOffset.ofHours(-7), fix.offset)
    }

    @Test
    fun `PhotoScan gets DateTimeOriginal from DateTime and an exact zone from its name`() {
        val fix = needs(
            diagnose(
                MetadataTestData.photoScanExif("2018:12:18 15:13:41"),
                rotationCcw = 0,
                name = "1545099221420-75da4984-5155-43c3-90e8-7bdf5258ebfc.heic",
                undated = true,
            )
        )
        assertEquals("2018:12:18 15:13:41", fix.dateTimeOriginal)
        assertEquals(ZoneOffset.ofHours(13), fix.offset)
    }

    @Test
    fun `with no EXIF date a timestamped name supplies one`() {
        val bare = MetadataTestData.exifBlock(ifd0 = listOf(MetadataTestData.Tag.Ascii(0x010F, "unknown")))
        val fix = needs(diagnose(bare, rotationCcw = 0, name = "1545099221420-x.heic", undated = true))
        assertEquals("2018:12:18 15:13:41", fix.dateTimeOriginal)
        assertEquals(ZoneOffset.ofHours(13), fix.offset)

        val camera = needs(diagnose(bare, rotationCcw = 0, name = "20171208_142656.heic", undated = true))
        assertEquals("2017:12:08 14:26:56", camera.dateTimeOriginal)
        assertEquals(ZoneOffset.ofHours(13), camera.offset)
    }

    @Test
    fun `nothing to go on is reported, not guessed`() {
        val bare = MetadataTestData.exifBlock(ifd0 = listOf(MetadataTestData.Tag.Ascii(0x010F, "unknown")))
        val d = diagnose(bare, rotationCcw = 0, name = "holiday.heic", undated = true)
        assertTrue(d is HeicRepair.Diagnosis.Cannot && d.reason.startsWith("no capture time"))
    }

    @Test
    fun `a dated photo's date is left alone`() {
        assertEquals(HeicRepair.Diagnosis.Fine, diagnose(MetadataTestData.samsungLikeExif(6, "2017:12:08 14:26:56"), undated = false))
    }

    @Test
    fun `rotation and date are fixed together`() {
        val fix = needs(diagnose(MetadataTestData.samsungLikeExif(1, "2017:07:28 08:20:28"), 270, undated = true))
        assertEquals(6, fix.orientation)
        assertEquals(ZoneOffset.ofHours(12), fix.offset)
    }

    // endregion

    // region names

    @Test
    fun `reads capture times from filenames`() {
        assertEquals(FileNameTime.Instant(1_787_293_551_227L), FileNameTime.of("1787293551227-b349057d-fa2a-4187-bf55-165f8d4c5230_.jpg"))
        assertEquals(FileNameTime.Local("2017:12:08 14:26:56"), FileNameTime.of("20171208_142656.heic"))
        assertEquals(FileNameTime.Local("2020:12:05 10:23:27"), FileNameTime.of("IMG_20201205_102327.heic"))
    }

    @Test
    fun `ignores numbers that are not capture times`() {
        assertNull(FileNameTime.of("received_231656647985780.heic")) // 15 digits
        assertNull(FileNameTime.of("0000000000001-x.jpg"))            // 1970
        assertNull(FileNameTime.of("20171399_999999.jpg"))            // not a date
        assertNull(FileNameTime.of("P45 Ray and Belinda.jpg"))
    }

    // endregion
}
