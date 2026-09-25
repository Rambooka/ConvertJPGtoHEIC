package com.example.convertjpgtoheic

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Builds synthetic EXIF blocks and HEIF files, laid out the way the real ones are, so the
 * metadata tests need no personal photos checked in.
 */
object MetadataTestData {

    /** One TIFF entry. ASCII values are NUL-terminated here; SHORTs and LONGs are inline. */
    sealed class Tag(val tag: Int) {
        class Ascii(tag: Int, val text: String) : Tag(tag)
        class Short(tag: Int, val value: Int) : Tag(tag)
        class Long(tag: Int, val value: kotlin.Long) : Tag(tag)
    }

    /**
     * An `Exif\0\0` + TIFF block with an IFD0 and, when [exif] is non-empty, an Exif sub-IFD.
     *
     * @param sorted false writes IFD0 entries in the given order, as some cameras do, to prove the
     *   reader does not depend on sort order.
     */
    fun exifBlock(
        ifd0: List<Tag>,
        exif: List<Tag> = emptyList(),
        littleEndian: Boolean = true,
        sorted: Boolean = true,
    ): ByteArray {
        val order = if (littleEndian) ByteOrder.LITTLE_ENDIAN else ByteOrder.BIG_ENDIAN
        val ifd0Tags = (if (exif.isNotEmpty()) ifd0 + Tag.Long(0x8769, 0) else ifd0)
            .let { if (sorted) it.sortedBy { t -> t.tag } else it }
        val exifTags = exif.sortedBy { it.tag }

        val ifd0Start = 8
        val ifd0Size = 2 + ifd0Tags.size * 12 + 4
        val exifStart = ifd0Start + ifd0Size
        val exifSize = if (exifTags.isEmpty()) 0 else 2 + exifTags.size * 12 + 4
        var dataCursor = exifStart + exifSize

        val data = ByteArrayOutputStream()
        fun place(bytes: ByteArray): Int {
            val at = dataCursor
            data.write(bytes)
            dataCursor += bytes.size
            if (bytes.size % 2 != 0) { data.write(0); dataCursor++ }
            return at
        }

        fun writeIfd(buf: ByteBuffer, tags: List<Tag>) {
            buf.putShort(tags.size.toShort())
            for (t in tags) {
                buf.putShort(t.tag.toShort())
                when (t) {
                    is Tag.Ascii -> {
                        val bytes = t.text.toByteArray(Charsets.US_ASCII) + 0.toByte()
                        buf.putShort(2); buf.putInt(bytes.size)
                        if (bytes.size <= 4) {
                            buf.put(bytes.copyOf(4))
                        } else {
                            buf.putInt(place(bytes))
                        }
                    }
                    is Tag.Short -> {
                        buf.putShort(3); buf.putInt(1)
                        buf.putShort(t.value.toShort()); buf.putShort(0)
                    }
                    is Tag.Long -> {
                        buf.putShort(4); buf.putInt(1)
                        buf.putInt(if (t.tag == 0x8769) exifStart else t.value.toInt())
                    }
                }
            }
            buf.putInt(0)
        }

        val head = ByteBuffer.allocate(exifStart + exifSize).order(order)
        head.put(if (littleEndian) byteArrayOf(0x49, 0x49) else byteArrayOf(0x4D, 0x4D))
        head.putShort(42)
        head.putInt(ifd0Start)
        writeIfd(head, ifd0Tags)
        if (exifTags.isNotEmpty()) writeIfd(head, exifTags)

        return byteArrayOf(0x45, 0x78, 0x69, 0x66, 0, 0) + head.array() + data.toByteArray()
    }

    /** What the camera writes for a portrait shot on an older Samsung: lean, and zone-less. */
    fun samsungLikeExif(orientation: Int, dateTimeOriginal: String?) = exifBlock(
        ifd0 = listOf(
            Tag.Ascii(0x010F, "samsung"),
            Tag.Ascii(0x0110, "SM-G930F"),
            Tag.Short(0x0112, orientation),
            Tag.Ascii(0x0132, dateTimeOriginal ?: "2017:12:08 14:26:56"),
        ),
        exif = listOfNotNull(
            dateTimeOriginal?.let { Tag.Ascii(0x9003, it) },
            dateTimeOriginal?.let { Tag.Ascii(0x9004, it) },
        ),
    )

    /** Google PhotoScan: `DateTime` and `DateTimeDigitized`, but no `DateTimeOriginal`. */
    fun photoScanExif(dateTime: String) = exifBlock(
        ifd0 = listOf(Tag.Ascii(0x0131, "GooglePhotoScan"), Tag.Ascii(0x0132, dateTime)),
        exif = listOf(Tag.Ascii(0x9004, dateTime)),
    )

    // region HEIF

    class Heif(
        val bytes: ByteArray,
        /** Where the fake image tile data sits, so a test can check it never changes. */
        val imageOffset: Int,
        val imageLength: Int,
    )

    /**
     * A minimal HEIF in MediaMuxer's layout: `ftyp`, one `mdat` holding the Exif item then an image
     * item, and a `meta` describing both — `iloc` v1 with 4-byte fields, `iinf` v0, `pitm` v0, and
     * an `irot` associated with the image through `ipma`.
     *
     * @param trailer bytes appended after `meta`, standing in for a Samsung SEF video.
     * @param imageExtentPastEnd makes the image's extent point past the last box, which a safe
     *   planner must refuse to shift.
     */
    fun heif(
        exifBlock: ByteArray,
        rotationCcw: Int = 270,
        mirrored: Boolean = false,
        trailer: ByteArray = ByteArray(0),
        imageExtentPastEnd: Boolean = false,
    ): Heif {
        val exifItem = ByteBuffer.allocate(4).putInt(6).array() + exifBlock
        val image = ByteArray(300) { (it * 7 + 3).toByte() }

        val ftyp = box("ftyp", "heic".ascii() + ByteArray(4) + "mif1heic".ascii())
        val mdatBody = exifItem + image
        val mdat = box("mdat", mdatBody)
        val exifOffset = ftyp.size + 8
        val imageOffset = exifOffset + exifItem.size

        fun metaBox(imageOffsetInIloc: Int): ByteArray {
            val hdlr = fullBox("hdlr", 0, ByteArray(4) + "pict".ascii() + ByteArray(12) + byteArrayOf(0))
            val pitm = fullBox("pitm", 0, u16(1))
            val infeImage = fullBox("infe", 2, u16(1) + u16(0) + "hvc1".ascii() + byteArrayOf(0))
            val infeExif = fullBox("infe", 2, u16(2) + u16(0) + "Exif".ascii() + byteArrayOf(0))
            val iinf = fullBox("iinf", 0, u16(2) + infeImage + infeExif)
            val iloc = fullBox(
                "iloc", 1,
                byteArrayOf(0x44, 0x00) + u16(2) +
                    // item 1: image
                    u16(1) + u16(0) + u16(0) + u16(1) + u32(imageOffsetInIloc) + u32(image.size) +
                    // item 2: Exif
                    u16(2) + u16(0) + u16(0) + u16(1) + u32(exifOffset) + u32(exifItem.size),
            )
            val props = ArrayList<ByteArray>()
            props += box("ispe", ByteArray(4) + u32(640) + u32(480))
            props += box("irot", byteArrayOf((rotationCcw / 90).toByte()))
            if (mirrored) props += box("imir", byteArrayOf(0))
            val ipco = box("ipco", props.reduce { a, b -> a + b })
            val associations = (1..props.size).map { it.toByte() }.toByteArray()
            val ipma = fullBox("ipma", 0, u32(1) + u16(1) + byteArrayOf(props.size.toByte()) + associations)
            val iprp = box("iprp", ipco + ipma)
            return fullBox("meta", 0, hdlr + pitm + iloc + iinf + iprp)
        }

        val placeholder = metaBox(imageOffset)
        val metaStart = ftyp.size + mdat.size
        val pointsAt = if (imageExtentPastEnd) metaStart + placeholder.size + 4 else imageOffset
        val meta = metaBox(pointsAt)
        return Heif(ftyp + mdat + meta + trailer, imageOffset, image.size)
    }

    fun box(type: String, body: ByteArray): ByteArray = u32(8 + body.size) + type.ascii() + body
    private fun fullBox(type: String, version: Int, body: ByteArray): ByteArray =
        box(type, byteArrayOf(version.toByte(), 0, 0, 0) + body)

    private fun String.ascii() = toByteArray(Charsets.ISO_8859_1)
    private fun u16(v: Int) = byteArrayOf((v shr 8).toByte(), v.toByte())
    private fun u32(v: Int) = ByteBuffer.allocate(4).putInt(v).array()

    // endregion

    /** Applies writes (and an optional truncation) to a copy of [bytes], as the device would. */
    fun applied(bytes: ByteArray, writes: List<FileWrite>, truncateTo: Long? = null): ByteArray {
        var out = bytes.copyOf()
        for (w in writes) {
            val end = (w.position + w.bytes.size).toInt()
            if (end > out.size) out = out.copyOf(end)
            w.bytes.copyInto(out, w.position.toInt())
        }
        if (truncateTo != null) out = out.copyOf(truncateTo.toInt())
        return out
    }
}
