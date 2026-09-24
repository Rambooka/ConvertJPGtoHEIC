package com.example.convertjpgtoheic

import java.io.EOFException
import java.io.InputStream

/**
 * What a JPEG's header segments tell us before we re-encode it.
 *
 * [exif] is the only part that can be carried into the HEIC — [HeifWriter.addExifData]
 * [androidx.heifwriter.HeifWriter.addExifData] takes exactly this block. The rest are flags for
 * things that would be *lost*, which matters because the originals get deleted afterwards.
 */
class JpegMetadata(
    /** Raw `Exif\0\0` + TIFF block, ready to hand to HeifWriter, or null if the file has none. */
    val exif: ByteArray?,
    /** XMP is present and cannot be carried across — HeifWriter has no XMP channel. */
    val hasXmp: Boolean,
    /** An embedded ICC profile, i.e. probably a wide-gamut image that will be flattened to sRGB. */
    val hasIccProfile: Boolean,
    /** The XMP advertises an embedded video, so this is a motion / live photo. */
    val motionPhotoInHeader: Boolean,
)

/**
 * Walks a JPEG's marker segments. Only reads as far as the first scan, so it stops well short of
 * the pixel data and costs a few kilobytes per photo.
 */
object JpegSegments {

    private const val MARKER = 0xFF
    private const val SOI = 0xD8
    private const val APP1 = 0xE1
    private const val APP2 = 0xE2
    private const val SOS = 0xDA
    private const val EOI = 0xD9

    /** `Exif\0\0` */
    private val EXIF_SIGNATURE = byteArrayOf(0x45, 0x78, 0x69, 0x66, 0x00, 0x00)
    private val XMP_SIGNATURE = "http://ns.adobe.com/xap/1.0/\u0000".toLatin1()
    private val XMP_EXTENSION_SIGNATURE = "http://ns.adobe.com/xmp/extension/\u0000".toLatin1()
    private val ICC_SIGNATURE = "ICC_PROFILE\u0000".toLatin1()

    /**
     * Markers that identify a motion / live photo from inside the XMP packet. Google writes
     * `GCamera:MotionPhoto` (or `MicroVideo` on older builds); the Container form declares the
     * appended clip as a second item with a video MIME type.
     */
    private val MOTION_PHOTO_XMP_HINTS = listOf("MotionPhoto", "MicroVideo", "video/mp4")

    fun read(input: InputStream): JpegMetadata {
        var exif: ByteArray? = null
        var hasXmp = false
        var hasIcc = false
        var motion = false

        try {
            if (input.readU8() != MARKER || input.readU8() != SOI) {
                return JpegMetadata(null, false, false, false)
            }

            while (true) {
                var b = input.readU8()
                // Segments may be preceded by any number of 0xFF fill bytes.
                while (b == MARKER) b = input.readU8()
                if (b == 0x00) break // 0xFF00 is a stuffed byte, not a marker

                val marker = b
                if (marker == SOS || marker == EOI) break
                if (marker == 0x01 || marker in 0xD0..0xD7) continue // no length field

                val length = (input.readU8() shl 8) or input.readU8()
                if (length < 2) break
                val payloadLength = length - 2

                // Only APP1 and APP2 can hold anything we care about; skip the rest unread.
                if (marker != APP1 && marker != APP2) {
                    input.skipFully(payloadLength.toLong())
                    continue
                }

                val payload = input.readFully(payloadLength)
                when {
                    marker == APP1 && payload.startsWith(EXIF_SIGNATURE) ->
                        if (exif == null) exif = payload

                    marker == APP1 &&
                        (payload.startsWith(XMP_SIGNATURE) || payload.startsWith(XMP_EXTENSION_SIGNATURE)) -> {
                        hasXmp = true
                        if (!motion) motion = payload.containsAnyText(MOTION_PHOTO_XMP_HINTS)
                    }

                    marker == APP2 && payload.startsWith(ICC_SIGNATURE) -> hasIcc = true
                }
            }
        } catch (_: EOFException) {
            // A truncated header still tells us whatever we managed to read.
        }

        return JpegMetadata(exif, hasXmp, hasIcc, motion)
    }

    // region byte helpers

    private fun String.toLatin1() = toByteArray(Charsets.ISO_8859_1)

    private fun ByteArray.startsWith(prefix: ByteArray): Boolean {
        if (size < prefix.size) return false
        for (i in prefix.indices) if (this[i] != prefix[i]) return false
        return true
    }

    /** Naive substring search over the payload read as Latin-1 — these blocks are small. */
    private fun ByteArray.containsAnyText(needles: List<String>): Boolean {
        val text = String(this, Charsets.ISO_8859_1)
        return needles.any { text.contains(it) }
    }

    private fun InputStream.readU8(): Int {
        val v = read()
        if (v < 0) throw EOFException()
        return v
    }

    private fun InputStream.readFully(n: Int): ByteArray {
        val out = ByteArray(n)
        var read = 0
        while (read < n) {
            val r = read(out, read, n - read)
            if (r < 0) throw EOFException()
            read += r
        }
        return out
    }

    private fun InputStream.skipFully(n: Long) {
        var remaining = n
        while (remaining > 0) {
            val skipped = skip(remaining)
            if (skipped <= 0) {
                // skip() may return 0 before EOF, so fall back to reading a byte.
                if (read() < 0) throw EOFException()
                remaining--
            } else {
                remaining -= skipped
            }
        }
    }

    // endregion
}
