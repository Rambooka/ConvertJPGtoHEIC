package com.example.convertjpgtoheic

import android.content.ContentResolver
import android.net.Uri
import android.system.Os
import android.util.Log
import java.io.FileDescriptor
import java.time.ZoneOffset

/**
 * Fixes the rotation and capture-date metadata of existing HEICs, in place.
 *
 * Two faults are repaired, both in the EXIF only — the image itself is never re-encoded:
 *
 * - **Rotation.** Earlier conversions put the rotation in the HEIF container (`irot`) and set the
 *   EXIF Orientation to 1. Android's decoder and Windows honour `irot`, so thumbnails look right,
 *   but anything that goes by MediaStore's orientation column — which is read from EXIF alone —
 *   shows the photo sideways. Phone Link is one. The fix writes the EXIF value that matches
 *   `irot`, which is exactly how Samsung's own camera writes HEIC.
 * - **Capture date.** See [ExifEditor]: without a timezone MediaProvider records no date at all.
 *   The fix adds `OffsetTimeOriginal`, and `DateTimeOriginal` too when the file has only
 *   `DateTime` (Google PhotoScan) or nothing but a dated filename.
 */
class HeicRepair(private val resolver: ContentResolver) {

    /** What to change. Null fields are left alone. */
    data class Fix(val orientation: Int?, val dateTimeOriginal: String?, val offset: ZoneOffset?) {
        val fixesRotation: Boolean get() = orientation != null
        val fixesDate: Boolean get() = dateTimeOriginal != null || offset != null
    }

    sealed interface Diagnosis {
        data object Fine : Diagnosis
        data class Needs(val fix: Fix) : Diagnosis
        data class Cannot(val reason: String) : Diagnosis
    }

    sealed interface Outcome {
        data object Repaired : Outcome
        data class Failed(val reason: String) : Outcome
    }

    /**
     * Works out what a HEIC needs, reading only.
     *
     * @param undated MediaStore has no capture date for it.
     * @param knownUtcMs the true capture instant when something else records it — a surviving
     *   original JPG's MediaStore date — so the timezone can be exact rather than assumed.
     */
    fun diagnose(uri: Uri, displayName: String, undated: Boolean, knownUtcMs: Long?): Diagnosis =
        try {
            resolver.openFileDescriptor(uri, "r")?.use { pfd ->
                diagnose(FdSource(pfd.fileDescriptor, pfd.statSize), displayName, undated, knownUtcMs)
            } ?: Diagnosis.Cannot("could not be opened")
        } catch (e: Exception) {
            Log.w(TAG, "Could not read $displayName", e)
            Diagnosis.Cannot("could not be read")
        }

    /**
     * Applies [fix], then re-reads the file and undoes everything unless it reads back exactly as
     * intended.
     *
     * @throws SecurityException when the app may not write this file — a HEIC it no longer owns
     *   needs the user's consent first.
     */
    fun apply(uri: Uri, displayName: String, fix: Fix): Outcome {
        val pfd = resolver.openFileDescriptor(uri, "rw") ?: return Outcome.Failed("could not be opened for writing")
        return pfd.use {
            try {
                apply(pfd.fileDescriptor, fix)
            } catch (e: Exception) {
                Log.w(TAG, "Repair failed for $displayName", e)
                Outcome.Failed("could not be rewritten — ${e.message ?: e.javaClass.simpleName}")
            }
        }
    }

    private fun apply(fd: FileDescriptor, fix: Fix): Outcome {
        val src = FdSource(fd, Os.fstat(fd).st_size)
        val layout = HeifExif.locate(src, sefStart(src)) ?: return Outcome.Failed("not a HEIF this can edit")
        val item = src.read(layout.exifItemOffset, layout.exifItemLength)
        val (head, block) = HeifExif.splitItem(item) ?: return Outcome.Failed("its EXIF could not be read")
        val edited = ExifEditor.edit(block, fix.orientation, fix.dateTimeOriginal, fix.offset)
            ?: return Outcome.Failed("its EXIF could not be edited safely")
        val plan = HeifExif.plan(src, layout, HeifExif.joinItem(head, edited))
            ?: return Outcome.Failed("its layout is not one this can extend safely")

        try {
            writeAll(fd, plan.dataWrites)
            Os.fsync(fd)
            writeAll(fd, plan.pointerWrites)
            Os.fsync(fd)
            val after = FdSource(fd, Os.fstat(fd).st_size)
            if (!HeifExif.verify(after, layout, plan, sefStart(after))) {
                throw IllegalStateException("did not read back as written")
            }
        } catch (e: Exception) {
            rollBack(fd, plan)
            throw e
        }
        return Outcome.Repaired
    }

    /** Puts the file back exactly as it was. Best effort: it runs because something already failed. */
    private fun rollBack(fd: FileDescriptor, plan: HeifExifPlan) {
        try {
            writeAll(fd, plan.undoWrites)
            Os.ftruncate(fd, plan.oldLength)
            Os.fsync(fd)
        } catch (e: Exception) {
            Log.e(TAG, "Could not roll a failed repair back", e)
        }
    }

    private fun writeAll(fd: FileDescriptor, writes: List<FileWrite>) {
        for (w in writes) {
            var done = 0
            while (done < w.bytes.size) {
                val n = Os.pwrite(fd, w.bytes, done, w.bytes.size - done, w.position + done)
                if (n <= 0) throw IllegalStateException("short write")
                done += n
            }
        }
    }

    /** Where a Samsung SEF trailer starts, so box scanning never wanders into it. */
    private fun sefStart(src: ByteSource): Long? {
        val take = minOf(SefTrailer.TAIL_BYTES.toLong(), src.size).toInt()
        if (take < 16) return null
        return SefTrailer.parse(src.read(src.size - take, take), src.size)?.sefStart
    }

    private class FdSource(private val fd: FileDescriptor, override val size: Long) : ByteSource {
        override fun read(position: Long, length: Int): ByteArray {
            val out = ByteArray(length)
            var done = 0
            while (done < length) {
                val n = Os.pread(fd, out, done, length - done, position + done)
                if (n <= 0) throw IllegalStateException("short read at ${position + done}")
                done += n
            }
            return out
        }
    }

    companion object {
        private const val TAG = "HeicRepair"

        /** The rules, free of Android I/O so they can be tested directly. */
        internal fun diagnose(src: ByteSource, displayName: String, undated: Boolean, knownUtcMs: Long?): Diagnosis {
            val take = minOf(SefTrailer.TAIL_BYTES.toLong(), src.size).toInt()
            val sef = if (take >= 16) SefTrailer.parse(src.read(src.size - take, take), src.size)?.sefStart else null
            val layout = HeifExif.locate(src, sef) ?: return Diagnosis.Cannot("not a HEIF this can edit")
            val (_, block) = HeifExif.splitItem(src.read(layout.exifItemOffset, layout.exifItemLength))
                ?: return Diagnosis.Cannot("its EXIF could not be read")
            val exif = ExifEditor.summarise(block) ?: return Diagnosis.Cannot("its EXIF could not be read")

            val orientation = rotationFix(layout, exif)

            var dateTimeOriginal: String? = null
            var offset: ZoneOffset? = null
            if (undated) {
                val named = FileNameTime.of(displayName)
                val instant = knownUtcMs ?: (named as? FileNameTime.Instant)?.utcMs
                val wallClock: String?
                when {
                    exif.dateTimeOriginal != null -> wallClock = exif.dateTimeOriginal
                    exif.fallbackDateTime != null -> {
                        wallClock = exif.fallbackDateTime
                        dateTimeOriginal = wallClock
                    }
                    instant != null -> {
                        wallClock = ExifEditor.formatExifDate(instant)
                        dateTimeOriginal = wallClock
                    }
                    named is FileNameTime.Local -> {
                        wallClock = named.exifDateTime
                        dateTimeOriginal = wallClock
                    }
                    else -> wallClock = null
                }
                if (wallClock == null) {
                    if (orientation == null) return Diagnosis.Cannot("no capture time recorded anywhere")
                } else if (!exif.hasOffsetTimeOriginal) {
                    offset = ExifEditor.offsetFor(wallClock, instant)
                } else if (dateTimeOriginal == null && orientation == null) {
                    return Diagnosis.Cannot("already has a timezone but is still undated")
                }
            }

            if (orientation == null && dateTimeOriginal == null && offset == null) return Diagnosis.Fine
            return Diagnosis.Needs(Fix(orientation, dateTimeOriginal, offset))
        }

        /**
         * The EXIF Orientation that agrees with the container rotation, or null when nothing should
         * change. Only the converter's own signature — a real rotation with EXIF left at "normal" —
         * is touched; any other disagreement is left for a person to judge.
         */
        internal fun rotationFix(layout: HeifExifLayout, exif: ExifEditor.Summary): Int? {
            if (layout.mirrored || layout.rotationCcw == 0) return null
            val target = when (layout.rotationCcw) {
                90 -> 8   // 90° anti-clockwise
                180 -> 3
                270 -> 6  // i.e. 90° clockwise
                else -> return null
            }
            return when (exif.orientation) {
                target -> null
                null, 1 -> target
                else -> null
            }
        }
    }
}

/**
 * A capture time recovered from a filename, for photos whose EXIF carries none.
 *
 * Google PhotoScan names files after the scan instant in epoch milliseconds
 * (`1545099221420-b851f44f-….jpg`), which pins the time exactly. Camera-style names
 * (`20171208_142656.jpg`) give wall-clock time only.
 */
sealed interface FileNameTime {
    data class Instant(val utcMs: Long) : FileNameTime
    data class Local(val exifDateTime: String) : FileNameTime

    companion object {
        private val EPOCH_MS = Regex("""^(\d{13})(?!\d)""")
        private val WALL_CLOCK = Regex("""(?<!\d)((?:19|20)\d{2})(\d{2})(\d{2})[_-](\d{2})(\d{2})(\d{2})(?!\d)""")

        /** 2000-01-01 to 2100-01-01: anything outside is not a capture time. */
        private const val MIN_MS = 946_684_800_000L
        private const val MAX_MS = 4_102_444_800_000L

        fun of(displayName: String): FileNameTime? {
            EPOCH_MS.find(displayName)?.let { match ->
                val ms = match.groupValues[1].toLong()
                if (ms in MIN_MS until MAX_MS) return Instant(ms)
            }
            WALL_CLOCK.find(displayName)?.let { m ->
                val (y, mo, d, h, mi, s) = m.destructured
                val text = "$y:$mo:$d $h:$mi:$s"
                if (ExifEditor.parseExifDate(text) != null) return Local(text)
            }
            return null
        }
    }
}
