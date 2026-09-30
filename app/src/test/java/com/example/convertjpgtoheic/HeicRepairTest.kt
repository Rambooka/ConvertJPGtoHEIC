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
    fun `a HEIC with no EXIF at all gets one, dated from its name`() {
        fun bare(name: String, undated: Boolean = true) = HeicRepair.diagnose(
            ByteArraySource(MetadataTestData.heif(ByteArray(0), rotationCcw = 0, withExif = false).bytes), name, undated, null,
        )
        val fix = needs(bare("Santa1-26-11-24-5933.heic"))
        assertTrue(fix.createsExif)
        assertEquals("2024:11:26 12:00:00", fix.dateTimeOriginal)
        assertEquals(ZoneOffset.ofHours(13), fix.offset)

        assertEquals(HeicRepair.Diagnosis.Fine, bare("Santa1-26-11-24-5933.heic", undated = false))
        val nothing = bare("cover.heic")
        assertTrue(nothing is HeicRepair.Diagnosis.Cannot && nothing.reason.startsWith("no capture time"))
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

    // region twins

    @Test
    fun `an undated HEIC borrows the date of a dated HEIC of the same name`() {
        val times = PhotoRepository.withTwinTimes(
            mapOf("20191221_081314" to 1L),
            listOf(
                "SYTR0FFHM5498-SYTR717105097.heic" to 1_674_532_288_000L, // DCIM/2024, dated
                "SYTR0FFHM5498-SYTR717105097.heic" to null,               // JPG_to_HEIC, undated
                "20191221_081314.heic" to 999L,                           // a JPG already dates it
                "clash.heic" to 1_000_000L,
                "clash.heic" to 9_000_000L,
            ),
        )
        assertEquals(1_674_532_288_000L, times["sytr0ffhm5498-sytr717105097"])
        assertEquals(1L, times["20191221_081314"])
        assertNull(times["clash"])

        val fix = needs(
            HeicRepair.diagnose(
                ByteArraySource(MetadataTestData.heif(ByteArray(0), rotationCcw = 0, withExif = false).bytes),
                "SYTR0FFHM5498-SYTR717105097.heic", undated = true, knownUtcMs = times["sytr0ffhm5498-sytr717105097"],
            )
        )
        assertTrue(fix.createsExif)
        assertEquals("2023:01:24 16:51:28", fix.dateTimeOriginal)
        assertEquals(ZoneOffset.ofHours(13), fix.offset)
    }

    @Test
    fun `with no date anywhere the photo goes back to where its original sat`() {
        // 6188-Heinemann_Sydney-d8-template: EXIF with resolution only, no date in the name; the
        // surviving JPG was last modified 4 Jan 2026 16:49:08 NZDT.
        val bare = MetadataTestData.exifBlock(ifd0 = listOf(MetadataTestData.Tag.Ascii(0x010F, "unknown")))
        val name = "6188-Heinemann_Sydney-d8-template.heic"
        val fix = needs(
            HeicRepair.diagnose(
                ByteArraySource(MetadataTestData.heif(bare, 0).bytes), name, undated = true, knownUtcMs = null,
                lastResortUtcMs = 1_767_498_548_000L,
            )
        )
        assertEquals("2026:01:04 16:49:08", fix.dateTimeOriginal)
        assertEquals(ZoneOffset.ofHours(13), fix.offset)

        // Anything better still wins: an EXIF date keeps its own wall clock.
        val dated = HeicRepair.diagnose(
            ByteArraySource(MetadataTestData.heif(MetadataTestData.samsungLikeExif(1, "2017:12:08 14:26:56"), 0).bytes),
            name, undated = true, knownUtcMs = null, lastResortUtcMs = 1_767_498_548_000L,
        )
        assertNull(needs(dated).dateTimeOriginal)
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
    fun `reads dates without times from filenames, placing them at midday`() {
        assertEquals(FileNameTime.Local("2015:06:06 12:00:00"), FileNameTime.of("IMG-20150606-WA0009.heic"))
        assertEquals(FileNameTime.Local("2024:03:14 12:00:00"), FileNameTime.of("IMG-20240314-WA0004_resized.heic"))
        assertEquals(FileNameTime.Local("2016:07:28 12:00:00"), FileNameTime.of("IMG_20160728_0016.heic"))
        assertEquals(FileNameTime.Local("2017:05:07 12:00:00"), FileNameTime.of("20170507_NAT2743Edit2.heic"))
        assertEquals(FileNameTime.Local("2024:11:26 12:00:00"), FileNameTime.of("Santa1-26-11-24-5933.heic"))
        assertEquals(FileNameTime.Local("2024:11:26 12:00:00"), FileNameTime.of("party 26.11.2024.jpg"))
        assertEquals(FileNameTime.Local("2024:11:26 12:00:00"), FileNameTime.of("Screenshot_2024-11-26.jpg"))
        assertEquals(FileNameTime.Local("2016:07:28 14:22:10"), FileNameTime.of("2016-07-28 14.22.10.jpg"))
        assertEquals(FileNameTime.Instant(1_764_976_585_000L), FileNameTime.of("photo_1764976585.heic"))
    }

    @Test
    fun `ignores numbers that are not capture times`() {
        assertNull(FileNameTime.of("received_231656647985780.heic")) // 15 digits
        assertNull(FileNameTime.of("0000000000001-x.jpg"))            // 1970
        assertNull(FileNameTime.of("20171399_999999.jpg"))            // not a date
        assertNull(FileNameTime.of("P45 Ray and Belinda.jpg"))
        // IDs from the real library that must not be read as dates.
        assertNull(FileNameTime.of("375700-572277836150055-1107221278-n-1_PerfectlyClear.heic"))
        assertNull(FileNameTime.of("SYTR0FFHM5498-SYTR717105097.heic"))
        assertNull(FileNameTime.of("CF4D1A24-3531-42CB-BAFC-DFA50FE47B3D-1.heic"))
        assertNull(FileNameTime.of("6188-Heinemann_Sydney-d8-template.heic"))
        assertNull(FileNameTime.of("31-02-24.jpg"))                   // no 31 February
    }

    // endregion
}
