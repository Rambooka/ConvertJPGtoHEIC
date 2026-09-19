package com.example.convertjpgtoheic

import org.junit.Assert.assertEquals
import org.junit.Test

/** The saving figures are what the dry run exists to produce, and what a delete decision rests on. */
class RunReportTest {

    @Test
    fun `saving is the difference between the two totals`() {
        val report = RunReport(RunMode.DRY_RUN, originalBytes = 1_000_000, heicBytes = 400_000)

        assertEquals(600_000, report.savedBytes)
        assertEquals(60, report.savedPercent)
    }

    @Test
    fun `an empty run reports zero rather than dividing by zero`() {
        val report = RunReport(RunMode.DRY_RUN)

        assertEquals(0, report.savedBytes)
        assertEquals(0, report.savedPercent)
    }

    @Test
    fun `a HEIC larger than its JPG shows as a negative saving, not an overflow`() {
        val report = RunReport(RunMode.DRY_RUN, originalBytes = 100_000, heicBytes = 130_000)

        assertEquals(-30_000, report.savedBytes)
        assertEquals(-30, report.savedPercent)
    }

    @Test
    fun `large libraries do not overflow the percentage calculation`() {
        // 100 GB of originals: originalBytes * 100 exceeds Int, so the maths must stay in Long.
        val report = RunReport(
            RunMode.CONVERT,
            originalBytes = 100L * 1024 * 1024 * 1024,
            heicBytes = 50L * 1024 * 1024 * 1024,
        )

        assertEquals(50, report.savedPercent)
    }
}
