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
        val placeholders = JpgQuery.selection.count { it == '?' }

        assertEquals(placeholders, JpgQuery.args(0L, 1L).size)
    }

    @Test
    fun `date taken is matched in milliseconds and date modified in seconds`() {
        val start = 1_700_000_000_000L
        val end = 1_700_086_399_999L

        val args = JpgQuery.args(start, end)

        assertEquals(start.toString(), args[2])
        assertEquals(end.toString(), args[3])
        assertEquals((start / 1000).toString(), args[4])
        assertEquals((end / 1000).toString(), args[5])
    }

    @Test
    fun `matches both jpeg mime spellings`() {
        val args = JpgQuery.args(0L, 1L)

        assertTrue(args.contains("image/jpeg"))
        assertTrue(args.contains("image/jpg"))
    }

    @Test
    fun `treats a zero date taken as unknown, not as a real date`() {
        assertTrue(
            "a 0 DATE_TAKEN must fall through to the DATE_MODIFIED branch",
            JpgQuery.selection.contains("> 0") && JpgQuery.selection.contains("<= 0"),
        )
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
