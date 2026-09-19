package com.example.convertjpgtoheic

/**
 * Reads and neutralises the EXIF Orientation tag inside a raw `Exif\0\0` + TIFF block.
 *
 * Why this exists: a JPEG usually stores its pixels in sensor order and records "turn this 90°" in
 * EXIF. HEIF instead expresses rotation in the container, and that is what
 * [HeifWriter.setRotation][androidx.heifwriter.HeifWriter.Builder.setRotation] writes — it becomes
 * `MediaMuxer.setOrientationHint`, which Android's HEIF decoder honours. EXIF Orientation carried
 * inside a HEIC is not reliably applied by that path.
 *
 * So copying the EXIF across untouched would leave every portrait photo displayed sideways. The
 * rotation has to move to the container, and the EXIF tag has to be set to 1 on the way out, or a
 * viewer that *does* read EXIF would turn the image a second time.
 */
object ExifOrientation {

    const val NORMAL = 1

    private const val TIFF_OFFSET = 6 // past "Exif\0\0"
    private const val TAG_ORIENTATION = 0x0112
    private const val ENTRY_SIZE = 12
    private const val TYPE_SHORT = 3

    /** The orientation recorded in the block, or [NORMAL] if absent or unreadable. */
    fun read(exifBlock: ByteArray): Int {
        val location = locate(exifBlock) ?: return NORMAL
        val value = readShort(exifBlock, location.valueOffset, location.littleEndian)
        return if (value in 1..8) value else NORMAL
    }

    /**
     * A copy of the block with Orientation forced to 1, so it cannot be applied twice. Returns the
     * input unchanged when there is no orientation tag to rewrite.
     */
    fun normalised(exifBlock: ByteArray): ByteArray {
        val location = locate(exifBlock) ?: return exifBlock
        val copy = exifBlock.copyOf()
        writeShort(copy, location.valueOffset, NORMAL, location.littleEndian)
        return copy
    }

    /**
     * Clockwise degrees equivalent to [orientation], or null when it also involves mirroring —
     * values 2, 4, 5 and 7 cannot be expressed as a rotation, so those are left alone.
     */
    fun rotationDegrees(orientation: Int): Int? = when (orientation) {
        1 -> 0
        3 -> 180
        6 -> 90
        8 -> 270
        else -> null
    }

    private class Location(val valueOffset: Int, val littleEndian: Boolean)

    /** Walks the TIFF header and IFD0 to find where the orientation value is stored. */
    private fun locate(exifBlock: ByteArray): Location? {
        if (exifBlock.size < TIFF_OFFSET + 8) return null

        val littleEndian = when {
            exifBlock[TIFF_OFFSET] == 0x49.toByte() && exifBlock[TIFF_OFFSET + 1] == 0x49.toByte() -> true
            exifBlock[TIFF_OFFSET] == 0x4D.toByte() && exifBlock[TIFF_OFFSET + 1] == 0x4D.toByte() -> false
            else -> return null
        }
        if (readShort(exifBlock, TIFF_OFFSET + 2, littleEndian) != 42) return null

        val ifdStart = TIFF_OFFSET + readInt(exifBlock, TIFF_OFFSET + 4, littleEndian)
        if (ifdStart < TIFF_OFFSET + 8 || ifdStart + 2 > exifBlock.size) return null

        val entryCount = readShort(exifBlock, ifdStart, littleEndian)
        if (entryCount <= 0) return null

        for (i in 0 until entryCount) {
            val entry = ifdStart + 2 + i * ENTRY_SIZE
            if (entry + ENTRY_SIZE > exifBlock.size) return null
            if (readShort(exifBlock, entry, littleEndian) != TAG_ORIENTATION) continue
            // Only the spec-defined form is handled: SHORT, count 1, value inline in the first two
            // bytes of the value field. Rewriting anything else would corrupt the entry.
            if (readShort(exifBlock, entry + 2, littleEndian) != TYPE_SHORT) return null
            if (readInt(exifBlock, entry + 4, littleEndian) != 1) return null
            return Location(entry + 8, littleEndian)
        }
        return null
    }

    private fun readShort(bytes: ByteArray, offset: Int, littleEndian: Boolean): Int {
        if (offset + 2 > bytes.size) return -1
        val a = bytes[offset].toInt() and 0xFF
        val b = bytes[offset + 1].toInt() and 0xFF
        return if (littleEndian) (b shl 8) or a else (a shl 8) or b
    }

    private fun writeShort(bytes: ByteArray, offset: Int, value: Int, littleEndian: Boolean) {
        if (offset + 2 > bytes.size) return
        val high = ((value shr 8) and 0xFF).toByte()
        val low = (value and 0xFF).toByte()
        if (littleEndian) {
            bytes[offset] = low
            bytes[offset + 1] = high
        } else {
            bytes[offset] = high
            bytes[offset + 1] = low
        }
    }

    private fun readInt(bytes: ByteArray, offset: Int, littleEndian: Boolean): Int {
        if (offset + 4 > bytes.size) return -1
        val b = IntArray(4) { bytes[offset + it].toInt() and 0xFF }
        return if (littleEndian) {
            (b[3] shl 24) or (b[2] shl 16) or (b[1] shl 8) or b[0]
        } else {
            (b[0] shl 24) or (b[1] shl 16) or (b[2] shl 8) or b[3]
        }
    }
}
