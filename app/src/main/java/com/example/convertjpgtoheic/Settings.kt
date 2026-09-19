package com.example.convertjpgtoheic

import android.content.Context
import androidx.core.content.edit

/** Remembers the last run's choices so a repeat run does not have to be re-configured. */
class Settings(context: Context) {

    private val prefs = context.getSharedPreferences("conversion", Context.MODE_PRIVATE)

    /**
     * Clamped and snapped on read. The slider throws if handed a value outside its range or off
     * its step, so a stored value from an older build would otherwise crash the app on startup.
     */
    var quality: Int
        get() = prefs.getInt(KEY_QUALITY, DEFAULT_QUALITY)
            .coerceIn(MIN_QUALITY, MAX_QUALITY)
            .let { (it / QUALITY_STEP) * QUALITY_STEP }
        set(value) = prefs.edit { putInt(KEY_QUALITY, value) }

    var deleteOriginals: Boolean
        get() = prefs.getBoolean(KEY_DELETE, true)
        set(value) = prefs.edit { putBoolean(KEY_DELETE, value) }

    var onlyIfSmaller: Boolean
        get() = prefs.getBoolean(KEY_ONLY_SMALLER, true)
        set(value) = prefs.edit { putBoolean(KEY_ONLY_SMALLER, value) }

    var skipAlreadyConverted: Boolean
        get() = prefs.getBoolean(KEY_SKIP_EXISTING, true)
        set(value) = prefs.edit { putBoolean(KEY_SKIP_EXISTING, value) }

    /** Defaults on: converting a motion photo throws away its video, which cannot be undone. */
    var skipLossyMetadata: Boolean
        get() = prefs.getBoolean(KEY_SKIP_LOSSY, true)
        set(value) = prefs.edit { putBoolean(KEY_SKIP_LOSSY, value) }

    /** Last picked range, so reopening the app does not lose it. Null until a range is chosen. */
    var range: DateRange?
        get() {
            val start = prefs.getLong(KEY_RANGE_START, -1L)
            val end = prefs.getLong(KEY_RANGE_END, -1L)
            return if (start < 0 || end < 0) null else DateRange(start, end)
        }
        set(value) {
            prefs.edit {
                putLong(KEY_RANGE_START, value?.startMs ?: -1L)
                putLong(KEY_RANGE_END, value?.endMs ?: -1L)
            }
        }

    companion object {
        /** Must match the slider bounds in activity_main.xml. */
        const val MIN_QUALITY = 50
        const val MAX_QUALITY = 100
        const val QUALITY_STEP = 5
        const val DEFAULT_QUALITY = 90

        private const val KEY_QUALITY = "quality"
        const val KEY_DELETE = "deleteOriginals"
        const val KEY_ONLY_SMALLER = "onlyIfSmaller"
        const val KEY_SKIP_EXISTING = "skipAlreadyConverted"
        const val KEY_SKIP_LOSSY = "skipLossyMetadata"
        const val KEY_RANGE_START = "rangeStart"
        const val KEY_RANGE_END = "rangeEnd"
    }
}
