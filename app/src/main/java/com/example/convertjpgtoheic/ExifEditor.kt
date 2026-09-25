package com.example.convertjpgtoheic

import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToLong

/**
 * Reads and edits the few EXIF tags that decide how Android dates and turns a photo.
 *
 * Works on the block form the rest of the app passes around: `Exif\0\0` followed by a TIFF header.
 * Pure Kotlin, no Android types, so the byte surgery can be unit-tested directly.
 *
 * ### Why dates need a timezone
 *
 * `DateTimeOriginal` is local wall-clock time with no zone. MediaProvider only records a
 * `DATE_TAKEN` when it can work one out: from `OffsetTimeOriginal`, else from a GPS timestamp,
 * else from the file's modified time if that is within a day of the capture time. A freshly
 * converted file's modified time is *today*, so an old photo with neither of the first two ends up
 * with no date at all and sorts as though it were taken now. Writing the offset into the EXIF
 * settles it for good — it survives any later rescan, which a modified-time trick would not.
 *
 * ### How tags are added without breaking anything
 *
 * Adding an entry grows an IFD, and moving an IFD would invalidate every offset that points into
 * or past it. So nothing is ever moved: the IFD being extended is rebuilt at the *end* of the block
 * with its existing 12-byte entries copied verbatim (their offsets still point at data that has
 * not moved) plus the new ones, and the single pointer to it is repointed. The old copy becomes
 * unreferenced padding. MakerNotes and thumbnails are left exactly where they were.
 */
object ExifEditor {

    /** Where photos with no recorded timezone are assumed to have been taken. */
    val HOME_ZONE: ZoneId = ZoneId.of("Pacific/Auckland")

    private const val TIFF_START = 6 // past "Exif\0\0"
    private const val ENTRY_SIZE = 12

    private const val TYPE_ASCII = 2
    private const val TYPE_SHORT = 3
    private const val TYPE_LONG = 4

    private const val TAG_ORIENTATION = 0x0112
    private const val TAG_DATE_TIME = 0x0132
    private const val TAG_EXIF_IFD = 0x8769
    private const val TAG_GPS_IFD = 0x8825
    private const val TAG_DATE_TIME_ORIGINAL = 0x9003
    private const val TAG_DATE_TIME_DIGITIZED = 0x9004
    private const val TAG_OFFSET_TIME = 0x9010
    private const val TAG_OFFSET_TIME_ORIGINAL = 0x9011
    private const val TAG_OFFSET_TIME_DIGITIZED = 0x9012
    private const val TAG_GPS_TIME_STAMP = 0x0007
    private const val TAG_GPS_DATE_STAMP = 0x001D

    /** No real zone is further than this from UTC; anything beyond means the "known" time is not
     *  actually the capture instant (a modified-time fallback, say). */
    private const val MAX_OFFSET_SECONDS = 14 * 3600L

    /** Real offsets are whole quarter hours; rounding absorbs clock skew between the two sources. */
    private const val OFFSET_STEP_SECONDS = 15 * 60L

    private val EXIF_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy:MM:dd HH:mm:ss")

    /** What the block says about capture time and orientation. */
    data class Summary(
        /** The Orientation value, or null when the tag is absent. */
        val orientation: Int?,
        val dateTimeOriginal: String?,
        /** `DateTimeDigitized`, else `DateTime` — what PhotoScan writes in place of the original. */
        val fallbackDateTime: String?,
        val hasOffsetTimeOriginal: Boolean,
        /** Both GPS date and time stamps are present, which MediaProvider can derive a zone from. */
        val hasGpsTimestamp: Boolean,
        val hasExifIfd: Boolean,
    )

    /** Reads the block, or returns null when it is not a well-formed EXIF block. */
    fun summarise(block: ByteArray): Summary? {
        val tiff = Tiff.of(block) ?: return null
        val ifd0 = tiff.readIfd(tiff.ifd0Offset()) ?: return null
        val exifIfd = ifd0.find(TAG_EXIF_IFD)?.let { tiff.readIfd(tiff.valueLong(it)) }
        val gpsIfd = ifd0.find(TAG_GPS_IFD)?.let { tiff.readIfd(tiff.valueLong(it)) }

        val orientation = ifd0.find(TAG_ORIENTATION)
            ?.takeIf { it.type == TYPE_SHORT && it.count == 1L }
            ?.let { tiff.u16(it.valueFieldOffset) }

        return Summary(
            orientation = orientation,
            dateTimeOriginal = exifIfd?.find(TAG_DATE_TIME_ORIGINAL)?.let { tiff.ascii(it) }
                ?.takeIf { parseExifDate(it) != null },
            fallbackDateTime = (
                exifIfd?.find(TAG_DATE_TIME_DIGITIZED)?.let { tiff.ascii(it) }?.takeIf { parseExifDate(it) != null }
                    ?: ifd0.find(TAG_DATE_TIME)?.let { tiff.ascii(it) }?.takeIf { parseExifDate(it) != null }
                ),
            hasOffsetTimeOriginal = exifIfd?.find(TAG_OFFSET_TIME_ORIGINAL)
                ?.let { tiff.ascii(it) }?.isNotBlank() == true,
            hasGpsTimestamp = gpsIfd != null &&
                gpsIfd.find(TAG_GPS_TIME_STAMP) != null &&
                gpsIfd.find(TAG_GPS_DATE_STAMP)?.let { tiff.ascii(it) }?.isNotBlank() == true,
            hasExifIfd = exifIfd != null,
        )
    }

    /**
     * Returns a copy of [block] with the requested changes, or null if it cannot be edited safely.
     *
     * @param orientation value to store in IFD0, or null to leave Orientation alone.
     * @param dateTimeOriginal "yyyy:MM:dd HH:mm:ss" to add as `DateTimeOriginal` when the block has
     *   none. An existing value is never overwritten.
     * @param offset timezone to add as `OffsetTimeOriginal` (plus `OffsetTime` and
     *   `OffsetTimeDigitized`) where each is missing. Existing offsets are never overwritten.
     */
    fun edit(
        block: ByteArray,
        orientation: Int? = null,
        dateTimeOriginal: String? = null,
        offset: ZoneOffset? = null,
    ): ByteArray? {
        val tiff = Tiff.of(block) ?: return null
        val ifd0Offset = tiff.ifd0Offset()
        val ifd0 = tiff.readIfd(ifd0Offset) ?: return null
        val out = Growable(block)

        val ifd0Additions = mutableListOf<NewEntry>()
        if (orientation != null) {
            val existing = ifd0.find(TAG_ORIENTATION)
            when {
                existing == null -> ifd0Additions += NewEntry.short(TAG_ORIENTATION, orientation)
                // Only the spec form — SHORT, count 1, value inline — can be rewritten in place.
                existing.type == TYPE_SHORT && existing.count == 1L ->
                    out.putU16(TIFF_START + existing.valueFieldOffset, orientation, tiff.littleEndian)
                else -> return null
            }
        }

        // Tags that belong in the Exif sub-IFD, added only where missing.
        val exifIfdEntry = ifd0.find(TAG_EXIF_IFD)
        val exifIfd = exifIfdEntry?.let { tiff.readIfd(tiff.valueLong(it)) ?: return null }
        val exifAdditions = mutableListOf<NewEntry>()
        if (dateTimeOriginal != null && exifIfd?.find(TAG_DATE_TIME_ORIGINAL) == null) {
            if (parseExifDate(dateTimeOriginal) == null) return null
            exifAdditions += NewEntry.ascii(TAG_DATE_TIME_ORIGINAL, dateTimeOriginal)
        }
        if (offset != null) {
            val text = formatOffset(offset)
            for (tag in listOf(TAG_OFFSET_TIME, TAG_OFFSET_TIME_ORIGINAL, TAG_OFFSET_TIME_DIGITIZED)) {
                if (exifIfd?.find(tag) == null) exifAdditions += NewEntry.ascii(tag, text)
            }
        }

        if (exifAdditions.isNotEmpty()) {
            val newExifIfd = if (exifIfd != null) {
                appendIfd(out, tiff.littleEndian, exifIfd.entries, exifIfd.next, exifAdditions)
            } else {
                appendIfd(out, tiff.littleEndian, emptyList(), 0L, exifAdditions)
            }
            if (exifIfdEntry != null) {
                out.putU32(TIFF_START + exifIfdEntry.valueFieldOffset, newExifIfd, tiff.littleEndian)
            } else {
                ifd0Additions += NewEntry.long(TAG_EXIF_IFD, newExifIfd)
            }
        }

        if (ifd0Additions.isNotEmpty()) {
            // Re-read IFD0 from the bytes as they stand now: the Exif pointer and Orientation may
            // have been patched in place above, and the rebuilt copy must carry those values, not
            // the original ones.
            val current = Tiff.of(out.toByteArray())?.readIfd(ifd0Offset) ?: return null
            val newIfd0 = appendIfd(out, tiff.littleEndian, current.entries, current.next, ifd0Additions)
            out.putU32(TIFF_START + 4, newIfd0, tiff.littleEndian)
        }
        return out.toByteArray()
    }

    /**
     * The timezone a capture time was taken in.
     *
     * With [knownUtcMs] — the true instant, from a surviving original's MediaStore date or a
     * timestamped filename — the offset is exact: wall time minus instant. Without one, or when the
     * two disagree by more than any real zone could, it falls back to [HOME_ZONE] for that date, so
     * daylight saving is applied correctly.
     */
    fun offsetFor(exifDateTime: String, knownUtcMs: Long?): ZoneOffset? {
        val local = parseExifDate(exifDateTime) ?: return null
        exactOffset(local, knownUtcMs)?.let { return it }
        return HOME_ZONE.rules.getOffset(local)
    }

    /** The offset implied by an instant we know to be the capture time, or null if it cannot be. */
    fun exactOffset(local: LocalDateTime, knownUtcMs: Long?): ZoneOffset? {
        if (knownUtcMs == null || knownUtcMs <= 0) return null
        val raw = local.toEpochSecond(ZoneOffset.UTC) - Math.floorDiv(knownUtcMs, 1000L)
        val rounded = (raw.toDouble() / OFFSET_STEP_SECONDS).roundToLong() * OFFSET_STEP_SECONDS
        if (abs(rounded) > MAX_OFFSET_SECONDS) return null
        return ZoneOffset.ofTotalSeconds(rounded.toInt())
    }

    /** The instant rendered as an EXIF wall-clock string in [zone]. */
    fun formatExifDate(utcMs: Long, zone: ZoneId = HOME_ZONE): String =
        EXIF_FORMAT.format(Instant.ofEpochMilli(utcMs).atZone(zone).toLocalDateTime())

    fun parseExifDate(value: String): LocalDateTime? {
        val text = value.trim().trimEnd('\u0000')
        if (text.length < 19) return null
        return try {
            LocalDateTime.parse(text.substring(0, 19), EXIF_FORMAT)
        } catch (_: DateTimeParseException) {
            null // includes the "0000:00:00 00:00:00" some cameras write for "unknown"
        }
    }

    /** "+13:00" / "-05:30". Never "Z", which EXIF readers do not accept. */
    fun formatOffset(offset: ZoneOffset): String {
        val total = offset.totalSeconds
        val sign = if (total < 0) '-' else '+'
        val minutes = abs(total) / 60
        return String.format(Locale.US, "%c%02d:%02d", sign, minutes / 60, minutes % 60)
    }

    // region TIFF plumbing

    private class Entry(
        val tag: Int,
        val type: Int,
        val count: Long,
        /** Offset of the 4-byte value field, relative to the TIFF header. */
        val valueFieldOffset: Int,
        /** The raw 12 bytes, copied verbatim when the IFD is rebuilt. */
        val raw: ByteArray,
    )

    private class Ifd(val entries: List<Entry>, val next: Long) {
        fun find(tag: Int): Entry? = entries.firstOrNull { it.tag == tag }
    }

    /** A new entry: its value, and whether that fits inline in the 4-byte field. */
    private class NewEntry(val tag: Int, val type: Int, val count: Long, val data: ByteArray?, val inline: Long) {
        companion object {
            fun short(tag: Int, value: Int) = NewEntry(tag, TYPE_SHORT, 1, null, value.toLong())
            fun long(tag: Int, value: Long) = NewEntry(tag, TYPE_LONG, 1, null, value)
            fun ascii(tag: Int, text: String): NewEntry {
                val bytes = text.toByteArray(Charsets.US_ASCII) + 0.toByte()
                return NewEntry(tag, TYPE_ASCII, bytes.size.toLong(), bytes, 0)
            }
        }
    }

    /**
     * Writes a new IFD at the end of [out] — [existing] entries verbatim plus [additions], sorted by
     * tag as TIFF requires — and returns its offset relative to the TIFF header.
     */
    private fun appendIfd(
        out: Growable,
        littleEndian: Boolean,
        existing: List<Entry>,
        next: Long,
        additions: List<NewEntry>,
    ): Long {
        out.alignEven()
        val ifdStart = out.size - TIFF_START
        val count = existing.size + additions.size
        var dataCursor = ifdStart + 2 + count * ENTRY_SIZE + 4

        val entries = ArrayList<Pair<Int, ByteArray>>(count)
        existing.forEach { entries += it.tag to it.raw }
        val outOfLine = ArrayList<ByteArray>()
        for (add in additions) {
            val raw = ByteArray(ENTRY_SIZE)
            writeU16(raw, 0, add.tag, littleEndian)
            writeU16(raw, 2, add.type, littleEndian)
            writeU32(raw, 4, add.count, littleEndian)
            val data = add.data
            when {
                data == null -> {
                    if (add.type == TYPE_SHORT) writeU16(raw, 8, add.inline.toInt(), littleEndian)
                    else writeU32(raw, 8, add.inline, littleEndian)
                }
                data.size <= 4 -> data.copyInto(raw, 8)
                else -> {
                    writeU32(raw, 8, dataCursor.toLong(), littleEndian)
                    val padded = if (data.size % 2 == 0) data else data + 0.toByte()
                    outOfLine += padded
                    dataCursor += padded.size
                }
            }
            entries += add.tag to raw
        }
        entries.sortBy { it.first }

        val header = ByteArray(2)
        writeU16(header, 0, count, littleEndian)
        out.append(header)
        entries.forEach { out.append(it.second) }
        val nextField = ByteArray(4)
        writeU32(nextField, 0, next, littleEndian)
        out.append(nextField)
        outOfLine.forEach { out.append(it) }
        return ifdStart.toLong()
    }

    private class Tiff(val block: ByteArray, val littleEndian: Boolean) {
        private val length = block.size - TIFF_START

        fun ifd0Offset(): Long = u32(4)

        fun u16(offset: Int): Int? {
            if (offset < 0 || offset + 2 > length) return null
            val a = block[TIFF_START + offset].toInt() and 0xFF
            val b = block[TIFF_START + offset + 1].toInt() and 0xFF
            return if (littleEndian) (b shl 8) or a else (a shl 8) or b
        }

        fun u32(offset: Int): Long {
            if (offset < 0 || offset + 4 > length) return -1
            var v = 0L
            for (i in 0 until 4) {
                val byte = (block[TIFF_START + offset + i].toLong() and 0xFF)
                v = if (littleEndian) v or (byte shl (8 * i)) else (v shl 8) or byte
            }
            return v
        }

        fun valueLong(entry: Entry): Long = u32(entry.valueFieldOffset)

        fun readIfd(offset: Long): Ifd? {
            if (offset < 8 || offset + 2 > length) return null
            val start = offset.toInt()
            val count = u16(start) ?: return null
            if (count <= 0 || count > 1000) return null
            if (start + 2 + count * ENTRY_SIZE + 4 > length) return null
            val entries = (0 until count).map { i ->
                val at = start + 2 + i * ENTRY_SIZE
                Entry(
                    tag = u16(at)!!,
                    type = u16(at + 2)!!,
                    count = u32(at + 4),
                    valueFieldOffset = at + 8,
                    raw = block.copyOfRange(TIFF_START + at, TIFF_START + at + ENTRY_SIZE),
                )
            }
            return Ifd(entries, u32(start + 2 + count * ENTRY_SIZE))
        }

        /** An ASCII value, inline or out of line, without its terminator. */
        fun ascii(entry: Entry): String? {
            if (entry.type != TYPE_ASCII || entry.count <= 0 || entry.count > 4096) return null
            val n = entry.count.toInt()
            val at = if (n <= 4) entry.valueFieldOffset else valueLong(entry).toInt()
            if (at < 0 || at + n > length) return null
            return String(block, TIFF_START + at, n, Charsets.ISO_8859_1).trimEnd('\u0000', ' ')
        }

        companion object {
            fun of(block: ByteArray): Tiff? {
                if (block.size < TIFF_START + 8) return null
                if (!(block[0] == 'E'.code.toByte() && block[1] == 'x'.code.toByte() &&
                        block[2] == 'i'.code.toByte() && block[3] == 'f'.code.toByte() &&
                        block[4] == 0.toByte() && block[5] == 0.toByte())
                ) return null
                val le = when {
                    block[6] == 0x49.toByte() && block[7] == 0x49.toByte() -> true
                    block[6] == 0x4D.toByte() && block[7] == 0x4D.toByte() -> false
                    else -> return null
                }
                val tiff = Tiff(block, le)
                if (tiff.u16(2) != 42) return null
                return tiff
            }
        }
    }

    /** A byte buffer that can be patched in place and grown at the end. */
    private class Growable(initial: ByteArray) {
        private var bytes = initial.copyOf(maxOf(initial.size * 2, 256))
        var size = initial.size
            private set

        fun append(data: ByteArray) {
            ensure(size + data.size)
            data.copyInto(bytes, size)
            size += data.size
        }

        fun alignEven() {
            if ((size - TIFF_START) % 2 != 0) append(byteArrayOf(0))
        }

        fun putU16(at: Int, value: Int, le: Boolean) = writeU16(bytes, at, value, le)
        fun putU32(at: Int, value: Long, le: Boolean) = writeU32(bytes, at, value, le)

        fun toByteArray(): ByteArray = bytes.copyOf(size)

        private fun ensure(capacity: Int) {
            if (capacity > bytes.size) bytes = bytes.copyOf(maxOf(capacity, bytes.size * 2))
        }
    }

    private fun writeU16(target: ByteArray, at: Int, value: Int, le: Boolean) {
        val hi = ((value shr 8) and 0xFF).toByte()
        val lo = (value and 0xFF).toByte()
        if (le) { target[at] = lo; target[at + 1] = hi } else { target[at] = hi; target[at + 1] = lo }
    }

    private fun writeU32(target: ByteArray, at: Int, value: Long, le: Boolean) {
        for (i in 0 until 4) {
            val byte = ((value shr (8 * i)) and 0xFF).toByte()
            if (le) target[at + i] = byte else target[at + 3 - i] = byte
        }
    }

    // endregion
}
