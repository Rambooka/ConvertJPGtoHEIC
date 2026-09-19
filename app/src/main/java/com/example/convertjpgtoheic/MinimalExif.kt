package com.example.convertjpgtoheic

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * Builds a minimal EXIF block carrying nothing but the capture time.
 *
 * Some JPGs — screenshots, downloads, anything re-saved by an editor — have no EXIF at all. For
 * those, the capture date would survive only as a MediaStore column, and columns get recomputed
 * from the file whenever the media scanner runs again. Giving the HEIC a real `DateTimeOriginal`
 * means a rescan re-derives the *original* date instead of the moment we wrote the file, which is
 * what keeps gallery date sorting stable for good.
 *
 * The layout below is a fixed 128-byte little-endian TIFF, so every offset is known up front.
 */
object MinimalExif {

    /** `Exif\0\0` — the prefix HeifWriter.addExifData expects before the TIFF header. */
    private val EXIF_PREFIX = byteArrayOf(0x45, 0x78, 0x69, 0x66, 0x00, 0x00)

    private const val TYPE_ASCII: Short = 2
    private const val TYPE_LONG: Short = 4

    private const val TAG_DATE_TIME = 0x0132
    private const val TAG_EXIF_IFD_POINTER = 0x8769
    private const val TAG_DATE_TIME_ORIGINAL = 0x9003
    private const val TAG_DATE_TIME_DIGITIZED = 0x9004

    /** "yyyy:MM:dd HH:mm:ss" plus the NUL terminator. */
    private const val DATE_FIELD_LENGTH = 20

    // Fixed offsets from the start of the TIFF header.
    private const val IFD0_OFFSET = 8
    private const val IFD0_DATE_OFFSET = 38
    private const val EXIF_IFD_OFFSET = 58
    private const val ORIGINAL_DATE_OFFSET = 88
    private const val DIGITIZED_DATE_OFFSET = 108
    private const val TIFF_LENGTH = 128

    /**
     * @param dateTakenMs capture time in epoch milliseconds.
     * @param zone the zone the timestamp should be rendered in — EXIF dates are local wall-clock
     *   times with no offset, which is exactly how the gallery will read them back.
     */
    fun forDate(dateTakenMs: Long, zone: TimeZone = TimeZone.getDefault()): ByteArray {
        val stamp = formatter(zone).format(Date(dateTakenMs)).toByteArray(Charsets.US_ASCII)

        val tiff = ByteBuffer.allocate(TIFF_LENGTH).order(ByteOrder.LITTLE_ENDIAN)
        tiff.put(0x49).put(0x49)          // "II" — little endian
        tiff.putShort(42)                 // TIFF magic
        tiff.putInt(IFD0_OFFSET)

        // IFD0: the file-level date, plus a pointer to the Exif sub-IFD.
        tiff.putShort(2)
        putEntry(tiff, TAG_DATE_TIME, TYPE_ASCII, DATE_FIELD_LENGTH, IFD0_DATE_OFFSET)
        putEntry(tiff, TAG_EXIF_IFD_POINTER, TYPE_LONG, 1, EXIF_IFD_OFFSET)
        tiff.putInt(0)                    // no IFD1 (no thumbnail)
        putDate(tiff, IFD0_DATE_OFFSET, stamp)

        // Exif sub-IFD: the tags galleries actually read for capture time.
        tiff.position(EXIF_IFD_OFFSET)
        tiff.putShort(2)
        putEntry(tiff, TAG_DATE_TIME_ORIGINAL, TYPE_ASCII, DATE_FIELD_LENGTH, ORIGINAL_DATE_OFFSET)
        putEntry(tiff, TAG_DATE_TIME_DIGITIZED, TYPE_ASCII, DATE_FIELD_LENGTH, DIGITIZED_DATE_OFFSET)
        tiff.putInt(0)
        putDate(tiff, ORIGINAL_DATE_OFFSET, stamp)
        putDate(tiff, DIGITIZED_DATE_OFFSET, stamp)

        return EXIF_PREFIX + tiff.array()
    }

    private fun putEntry(buffer: ByteBuffer, tag: Int, type: Short, count: Int, value: Int) {
        buffer.putShort(tag.toShort())
        buffer.putShort(type)
        buffer.putInt(count)
        buffer.putInt(value)
    }

    /** Writes a 20-byte NUL-terminated date string at an absolute offset. */
    private fun putDate(buffer: ByteBuffer, offset: Int, stamp: ByteArray) {
        val saved = buffer.position()
        buffer.position(offset)
        buffer.put(stamp, 0, minOf(stamp.size, DATE_FIELD_LENGTH - 1))
        buffer.put(0)
        buffer.position(saved)
    }

    private fun formatter(zone: TimeZone) =
        SimpleDateFormat("yyyy:MM:dd HH:mm:ss", Locale.US).apply { timeZone = zone }
}
