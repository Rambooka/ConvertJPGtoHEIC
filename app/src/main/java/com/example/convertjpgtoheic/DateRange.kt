package com.example.convertjpgtoheic

import java.util.Calendar
import java.util.TimeZone

/**
 * An inclusive span of local calendar days, held as the epoch-millisecond bounds that MediaStore
 * wants.
 *
 * This is deliberately a plain class with no Android dependencies: it decides which photos get
 * converted and — because originals are deleted afterwards — an off-by-one-day bug here destroys
 * the wrong day's photos. That makes it the one piece of logic most worth unit testing.
 */
data class DateRange(val startMs: Long, val endMs: Long) {

    val isValid: Boolean get() = startMs <= endMs

    companion object {

        /**
         * Converts the pair MaterialDatePicker returns into local-time bounds.
         *
         * The picker reports midnight **UTC** on the day the user tapped, but photos are stamped in
         * local time. Reading the calendar date back out in UTC and re-anchoring it to the local
         * day is what keeps someone in UTC+13 or UTC-8 from losing or gaining a day at each end.
         */
        fun fromUtcDayPicks(
            startUtcMs: Long,
            endUtcMs: Long,
            zone: TimeZone = TimeZone.getDefault(),
        ): DateRange = DateRange(
            startMs = bound(startUtcMs, startOfDay = true, zone = zone),
            endMs = bound(endUtcMs, startOfDay = false, zone = zone),
        )

        /** Inverse of [fromUtcDayPicks], so reopening the picker lands on the same days. */
        fun toUtcDayPick(localMs: Long, zone: TimeZone = TimeZone.getDefault()): Long {
            val local = Calendar.getInstance(zone).apply { timeInMillis = localMs }
            return Calendar.getInstance(UTC).apply {
                clear()
                set(local.get(Calendar.YEAR), local.get(Calendar.MONTH), local.get(Calendar.DAY_OF_MONTH))
            }.timeInMillis
        }

        private fun bound(utcDayMs: Long, startOfDay: Boolean, zone: TimeZone): Long {
            val utc = Calendar.getInstance(UTC).apply { timeInMillis = utcDayMs }
            return Calendar.getInstance(zone).apply {
                clear()
                set(
                    utc.get(Calendar.YEAR),
                    utc.get(Calendar.MONTH),
                    utc.get(Calendar.DAY_OF_MONTH),
                    if (startOfDay) 0 else 23,
                    if (startOfDay) 0 else 59,
                    if (startOfDay) 0 else 59,
                )
                set(Calendar.MILLISECOND, if (startOfDay) 0 else 999)
            }.timeInMillis
        }

        private val UTC: TimeZone get() = TimeZone.getTimeZone("UTC")
    }
}
