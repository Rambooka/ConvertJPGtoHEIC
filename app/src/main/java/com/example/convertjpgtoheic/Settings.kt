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

    /** Pass over JPGs under 1 MB — too little to gain to be worth re-encoding. Defaults on. */
    var skipSmall: Boolean
        get() = prefs.getBoolean(KEY_SKIP_SMALL, true)
        set(value) = prefs.edit { putBoolean(KEY_SKIP_SMALL, value) }

    /**
     * Shrink an image too large for the device's encoder (a huge panorama) so it fits, rather than
     * failing it. Defaults on: it only ever affects images that could not be converted at all
     * otherwise, and the result is reported as downscaled.
     */
    var shrinkOversized: Boolean
        get() = prefs.getBoolean(KEY_SHRINK_OVERSIZED, true)
        set(value) = prefs.edit { putBoolean(KEY_SHRINK_OVERSIZED, value) }

    var skipAlreadyConverted: Boolean
        get() = prefs.getBoolean(KEY_SKIP_EXISTING, true)
        set(value) = prefs.edit { putBoolean(KEY_SKIP_EXISTING, value) }

    /**
     * How motion photos are handled. Defaults to keeping the video: a Samsung motion photo becomes
     * a HEIC motion photo with its video re-attached, and one whose video cannot be carried across
     * is skipped rather than silently flattened. Read defensively — a value stored by a newer build
     * must not crash an older one.
     */
    var motionPolicy: MotionPhotoPolicy
        get() = runCatching {
            MotionPhotoPolicy.valueOf(prefs.getString(KEY_MOTION_POLICY, null) ?: "")
        }.getOrDefault(MotionPhotoPolicy.KEEP_VIDEO)
        set(value) = prefs.edit { putString(KEY_MOTION_POLICY, value.name) }

    /**
     * Folders every converter and the clean-up leave alone, as path prefixes under the storage root.
     * Defaults to `Android/`: the media other apps keep there (WhatsApp's pictures, for one) cannot
     * be written back where it came from, and deleting the original breaks that app's own records.
     */
    var excludedFolders: List<String>
        get() = (prefs.getString(KEY_EXCLUDED, null) ?: DEFAULT_EXCLUDED).lines()
            .map { it.trim().trimStart('/') }
            .filter { it.isNotEmpty() }
            .map { if (it.endsWith("/")) it else "$it/" }
        set(value) = prefs.edit { putString(KEY_EXCLUDED, value.joinToString("\n")) }

    /** Move originals to the trash (recoverable for 30 days) instead of deleting them. Defaults off. */
    var trashInstead: Boolean
        get() = prefs.getBoolean(KEY_TRASH, false)
        set(value) = prefs.edit { putBoolean(KEY_TRASH, value) }

    /** Keep the screen on while a run is going: with it off, Samsung confines the app to the slow cores. */
    var keepScreenOn: Boolean
        get() = prefs.getBoolean(KEY_KEEP_SCREEN_ON, true)
        set(value) = prefs.edit { putBoolean(KEY_KEEP_SCREEN_ON, value) }

    /** Pause a run while the phone is not charging. Defaults off. */
    var onlyWhileCharging: Boolean
        get() = prefs.getBoolean(KEY_ONLY_CHARGING, false)
        set(value) = prefs.edit { putBoolean(KEY_ONLY_CHARGING, value) }

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
        const val DEFAULT_QUALITY = 80

        private const val KEY_QUALITY = "quality"
        const val KEY_DELETE = "deleteOriginals"
        const val KEY_ONLY_SMALLER = "onlyIfSmaller"
        const val KEY_SKIP_SMALL = "skipSmall"
        const val KEY_SHRINK_OVERSIZED = "shrinkOversized"
        const val KEY_SKIP_EXISTING = "skipAlreadyConverted"
        const val KEY_MOTION_POLICY = "motionPolicy"
        const val KEY_RANGE_START = "rangeStart"
        const val KEY_RANGE_END = "rangeEnd"
        const val KEY_EXCLUDED = "excludedFolders"
        const val KEY_TRASH = "trashInstead"
        const val KEY_KEEP_SCREEN_ON = "keepScreenOn"
        const val KEY_ONLY_CHARGING = "onlyWhileCharging"
        const val DEFAULT_EXCLUDED = "Android/"
    }
}
