package com.example.convertjpgtoheic

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * This predicate decides which photos get converted and then deleted, so the awkward cases matter:
 * a device that writes 0 for "capture date unknown" instead of NULL must not have those photos
 * silently vanish from every scan, nor be stamped with 1970.
 */
class JpgQueryTest {

    @Test
    fun `placeholder count matches the argument count`() {
        // A mismatch here throws at query time on device and would never show up in a build.
        for (excluded in listOf(emptyList(), listOf("Android/"), listOf("Android/", "DCIM/Wallpaper/"))) {
            val placeholders = JpgQuery.selection(excluded).count { it == '?' }
            assertEquals(placeholders, JpgQuery.args(0L, 1L, excluded).size)
        }
    }

    @Test
    fun `date taken is matched in milliseconds and date modified in seconds`() {
        val start = 1_700_000_000_000L
        val end = 1_700_086_399_999L

        val args = JpgQuery.args(start, end, emptyList())

        assertEquals(start.toString(), args[3])
        assertEquals(end.toString(), args[4])
        assertEquals((start / 1000).toString(), args[5])
        assertEquals((end / 1000).toString(), args[6])
    }

    @Test
    fun `matches both jpeg mime spellings, and png`() {
        val args = JpgQuery.args(0L, 1L, emptyList())

        assertTrue(args.contains("image/jpeg"))
        assertTrue(args.contains("image/jpg"))
        assertTrue(args.contains("image/png"))
    }

    @Test
    fun `treats a zero date taken as unknown, not as a real date`() {
        assertTrue(
            "a 0 DATE_TAKEN must fall through to the DATE_MODIFIED branch",
            JpgQuery.selection(emptyList()).contains("> 0") && JpgQuery.selection(emptyList()).contains("<= 0"),
        )
    }

    @Test
    fun `excluded folders become escaped NOT LIKE prefixes`() {
        val folders = listOf("Android/", "DCIM/My_Pics/")
        assertEquals(" AND p NOT LIKE ? ESCAPE '\\' AND p NOT LIKE ? ESCAPE '\\'", FolderExclusion.sql("p", folders))
        assertEquals(listOf("Android/%", "DCIM/My\\_Pics/%"), FolderExclusion.args(folders).toList())
        assertEquals("", FolderExclusion.sql("p", emptyList()))
    }

    @Test
    fun `effective date prefers date taken when it is real`() {
        assertEquals(1_700_000_000_000L, JpgQuery.effectiveDateMs(1_700_000_000_000L, 1_600_000_000L))
    }

    @Test
    fun `effective date falls back to date modified when date taken is null`() {
        assertEquals(1_600_000_000_000L, JpgQuery.effectiveDateMs(null, 1_600_000_000L))
    }

    @Test
    fun `effective date falls back when date taken is zero rather than dating it to 1970`() {
        assertEquals(1_600_000_000_000L, JpgQuery.effectiveDateMs(0L, 1_600_000_000L))
    }

    @Test
    fun `effective date falls back on a negative date taken`() {
        assertEquals(1_600_000_000_000L, JpgQuery.effectiveDateMs(-1L, 1_600_000_000L))
    }
}
