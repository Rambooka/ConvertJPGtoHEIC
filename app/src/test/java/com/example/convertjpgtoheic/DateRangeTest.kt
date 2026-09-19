package com.example.convertjpgtoheic

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar
import java.util.TimeZone

/**
 * MaterialDatePicker reports midnight **UTC**, but photos are stamped in local time. Getting this
 * wrong converts and then deletes the wrong day's photos, so both ends are checked in zones either
 * side of UTC.
 */
class DateRangeTest {

    private val auckland = TimeZone.getTimeZone("Pacific/Auckland") // UTC+12/+13
    private val losAngeles = TimeZone.getTimeZone("America/Los_Angeles") // UTC-8/-7
    private val utc = TimeZone.getTimeZone("UTC")

    @Test
    fun `start bound is local midnight, not utc midnight`() {
        val pick = utcMidnight(2026, Calendar.MARCH, 14)

        val range = DateRange.fromUtcDayPicks(pick, pick, auckland)

        assertEquals(localFields(range.startMs, auckland), listOf(2026, Calendar.MARCH, 14, 0, 0, 0))
    }

    @Test
    fun `end bound is the last millisecond of the local day`() {
        val pick = utcMidnight(2026, Calendar.MARCH, 14)

        val range = DateRange.fromUtcDayPicks(pick, pick, auckland)

        assertEquals(localFields(range.endMs, auckland), listOf(2026, Calendar.MARCH, 14, 23, 59, 59))
        assertEquals(999, Calendar.getInstance(auckland).apply { timeInMillis = range.endMs }
            .get(Calendar.MILLISECOND))
    }

    @Test
    fun `a single day covers exactly 24 hours`() {
        val pick = utcMidnight(2026, Calendar.JUNE, 1)

        val range = DateRange.fromUtcDayPicks(pick, pick, utc)

        assertEquals(24L * 60 * 60 * 1000 - 1, range.endMs - range.startMs)
    }

    @Test
    fun `west of utc does not shift the day backwards`() {
        val pick = utcMidnight(2026, Calendar.MARCH, 14)

        val range = DateRange.fromUtcDayPicks(pick, pick, losAngeles)

        // Naively reading the UTC instant in Los Angeles would land on the 13th.
        assertEquals(14, Calendar.getInstance(losAngeles).apply { timeInMillis = range.startMs }
            .get(Calendar.DAY_OF_MONTH))
    }

    @Test
    fun `east of utc does not shift the day forwards`() {
        val pick = utcMidnight(2026, Calendar.MARCH, 14)

        val range = DateRange.fromUtcDayPicks(pick, pick, auckland)

        assertEquals(14, Calendar.getInstance(auckland).apply { timeInMillis = range.startMs }
            .get(Calendar.DAY_OF_MONTH))
    }

    @Test
    fun `multi day range spans from the first midnight to the last midnight-minus-one`() {
        val from = utcMidnight(2026, Calendar.JANUARY, 10)
        val to = utcMidnight(2026, Calendar.JANUARY, 12)

        val range = DateRange.fromUtcDayPicks(from, to, utc)

        assertEquals(listOf(2026, Calendar.JANUARY, 10, 0, 0, 0), localFields(range.startMs, utc))
        assertEquals(listOf(2026, Calendar.JANUARY, 12, 23, 59, 59), localFields(range.endMs, utc))
        assertTrue(range.isValid)
    }

    @Test
    fun `round trips back to the same picker day`() {
        val pick = utcMidnight(2026, Calendar.SEPTEMBER, 30)

        val range = DateRange.fromUtcDayPicks(pick, pick, auckland)

        assertEquals(pick, DateRange.toUtcDayPick(range.startMs, auckland))
        assertEquals(pick, DateRange.toUtcDayPick(range.endMs, auckland))
    }

    /** Auckland moves to DST on 2026-09-27, so this day is only 23 hours long. */
    @Test
    fun `survives a daylight saving transition`() {
        val pick = utcMidnight(2026, Calendar.SEPTEMBER, 27)

        val range = DateRange.fromUtcDayPicks(pick, pick, auckland)

        assertTrue(range.isValid)
        assertEquals(27, Calendar.getInstance(auckland).apply { timeInMillis = range.startMs }
            .get(Calendar.DAY_OF_MONTH))
        assertEquals(27, Calendar.getInstance(auckland).apply { timeInMillis = range.endMs }
            .get(Calendar.DAY_OF_MONTH))
    }

    private fun utcMidnight(year: Int, month: Int, day: Int): Long =
        Calendar.getInstance(utc).apply {
            clear()
            set(year, month, day)
        }.timeInMillis

    private fun localFields(ms: Long, zone: TimeZone): List<Int> =
        Calendar.getInstance(zone).apply { timeInMillis = ms }.let {
            listOf(
                it.get(Calendar.YEAR),
                it.get(Calendar.MONTH),
                it.get(Calendar.DAY_OF_MONTH),
                it.get(Calendar.HOUR_OF_DAY),
                it.get(Calendar.MINUTE),
                it.get(Calendar.SECOND),
            )
        }
}
