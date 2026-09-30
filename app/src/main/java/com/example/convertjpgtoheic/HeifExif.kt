package com.example.convertjpgtoheic

/** Random-access reads, so the same parser runs over a file descriptor on-device and bytes in tests. */
interface ByteSource {
    val size: Long
    fun read(position: Long, length: Int): ByteArray
}

class ByteArraySource(private val bytes: ByteArray) : ByteSource {
    override val size: Long get() = bytes.size.toLong()
    override fun read(position: Long, length: Int): ByteArray {
        val from = position.toInt()
        return bytes.copyOfRange(from, from + length)
    }
}

/** [bytes] written at absolute [position]. */
class FileWrite(val position: Long, val bytes: ByteArray)

/** Where a HEIF keeps its Exif item, and what else the edit must not disturb. */
class HeifExifLayout internal constructor(
    /** End of the HEIF boxes. Anything after is a trailer (a Samsung SEF video) that stays at EOF. */
    val heifEnd: Long,
    val exifItemOffset: Long,
    val exifItemLength: Int,
    /** The primary image's container rotation in anti-clockwise degrees, 0 when there is none. */
    val rotationCcw: Int,
    /** The primary image carries a mirror, which an EXIF rotation alone cannot express. */
    val mirrored: Boolean,
    internal val offsetField: Long,
    internal val offsetFieldSize: Int,
    internal val lengthField: Long,
    internal val lengthFieldSize: Int,
    internal val baseOffset: Long,
    /** The furthest byte any *other* item's data reaches. */
    internal val otherDataEnd: Long,
)

/** A HEIF with no Exif item, laid out so that one can be added. */
class HeifBareLayout internal constructor(
    internal val parsed: HeifExif.Parsed,
    /** The furthest byte any item's data reaches. */
    internal val otherDataEnd: Long,
) {
    val rotationCcw: Int get() = parsed.rotationCcw
    val mirrored: Boolean get() = parsed.mirrored
}

/**
 * The writes that replace a HEIF's Exif item, and the writes that undo them.
 *
 * Apply [dataWrites], sync, then [pointerWrites], sync. Until the pointers move the file still
 * reads exactly as before, so a crash part-way leaves the photo intact — at worst with some
 * unreferenced bytes after its last box.
 */
class HeifExifPlan internal constructor(
    val dataWrites: List<FileWrite>,
    val pointerWrites: List<FileWrite>,
    val undoWrites: List<FileWrite>,
    val oldLength: Long,
    val newLength: Long,
    val newItemOffset: Long,
    val newItem: ByteArray,
    /** Bytes that were after the HEIF boxes and must still end the file. */
    val trailer: ByteArray,
)

/**
 * Finds and replaces the Exif item inside a HEIF without touching the image.
 *
 * ### The layout this relies on
 *
 * A HEIF is a sequence of boxes. `meta` holds `iinf` (what each item is), `iloc` (where each
 * item's bytes are, as absolute file offsets), `pitm` (which item is the picture) and `iprp` (its
 * properties, including the `irot` container rotation). The Exif item is one of those items, a
 * 4-byte `exif_tiff_header_offset` followed by the EXIF payload.
 *
 * ### How the Exif item is replaced
 *
 * If the new item is the same length it is overwritten in place. Otherwise it is appended in a new
 * `mdat` box straight after the last HEIF box, and the item's `iloc` offset and length — fixed
 * width fields — are repointed. No box changes size and no other item's data moves, so every other
 * offset in the file stays valid. Multiple `mdat` boxes are allowed by the format.
 *
 * Anything after the last HEIF box — the app's re-attached motion-photo video — is moved along to
 * stay at the very end, where the SEF format requires it; its offsets are self-relative, so the move
 * is harmless. Before appending, the plan checks that no item points past the insertion point, so
 * nothing referenced by absolute offset is ever shifted.
 */
object HeifExif {

    /** Boxes that may appear at the top level of a HEIF. Scanning stops at anything else, which
     *  is treated as a trailer: a raw SEF block must never be mistaken for a box and split. */
    private val TOP_LEVEL_TYPES = setOf(
        "ftyp", "meta", "mdat", "free", "skip", "uuid", "moov", "sefd", "mpvd", "pdin", "styp",
    )

    private const val MAX_META_BYTES = 4 * 1024 * 1024
    private const val MAX_ITEM_BYTES = 1024 * 1024
    private const val MAX_TRAILER_BYTES = 128L * 1024 * 1024

    private val EXIF_SIGNATURE = byteArrayOf(0x45, 0x78, 0x69, 0x66, 0x00, 0x00)

    /**
     * Reads the layout, or null when the file is not a HEIF whose Exif item can be edited this way.
     *
     * @param trailerStart when known, where a trailer begins; box scanning never goes past it.
     */
    fun locate(src: ByteSource, trailerStart: Long? = null): HeifExifLayout? {
        val p = parse(src, trailerStart) ?: return null
        val m = p.meta

        val exifIds = p.types.filterValues { it == "Exif" }.keys
        if (exifIds.size != 1) return null
        val exifId = exifIds.first()

        val iloc = p.iloc
        val exif = iloc.items[exifId] ?: return null
        if (exif.constructionMethod != 0 || exif.dataReference != 0 || exif.extents.size != 1) return null
        if (iloc.offsetSize == 0 || iloc.lengthSize == 0) return null
        val extent = exif.extents.single()
        val itemOffset = exif.baseOffset + extent.offset
        if (extent.length <= 0 || extent.length > MAX_ITEM_BYTES || itemOffset + extent.length > src.size) return null

        return HeifExifLayout(
            heifEnd = p.heifEnd,
            exifItemOffset = itemOffset,
            exifItemLength = extent.length.toInt(),
            rotationCcw = p.rotationCcw,
            mirrored = p.mirrored,
            offsetField = p.metaBox.start + extent.offsetField,
            offsetFieldSize = iloc.offsetSize,
            lengthField = p.metaBox.start + extent.lengthField,
            lengthFieldSize = iloc.lengthSize,
            baseOffset = exif.baseOffset,
            otherDataEnd = otherDataEnd(iloc, except = exifId),
        )
    }

    /**
     * Reads a HEIF that has **no** Exif item, or returns null when it has one or one cannot be
     * added safely — see [planInsert] for what "safely" requires.
     */
    fun locateBare(src: ByteSource, trailerStart: Long? = null): HeifBareLayout? {
        val p = parse(src, trailerStart) ?: return null
        if (p.types.values.any { it == "Exif" }) return null
        // The meta box is rewritten larger, so it must be the last box: nothing may move.
        if (p.metaBox.start + p.metaBox.size != p.heifEnd) return null
        // Every byte of the body must be accounted for by child boxes, or the rebuild could drop some.
        val c = p.children
        if ((c.lastOrNull()?.end ?: -1) != p.meta.size) return null
        if (c.count { it.type == "iinf" } != 1 || c.count { it.type == "iloc" } != 1 || c.count { it.type == "iref" } > 1) return null
        val otherEnd = otherDataEnd(p.iloc, except = null)
        if (otherEnd > p.metaBox.start) return null
        if (p.iloc.offsetSize == 0 || p.iloc.lengthSize == 0) return null
        return HeifBareLayout(p, otherEnd)
    }

    /**
     * Builds the writes that give a bare HEIF [newItem] as its Exif item, or null if that is not
     * safe here.
     *
     * The old `meta` stays exactly where it is. A rebuilt copy — the same boxes plus an `infe`,
     * an `iloc` entry and a `cdsc` reference for the new item — is written after it, followed by an
     * `mdat` holding the item and then any trailer. The copy is written typed `free`, so until the
     * pointer writes the file still reads exactly as before. Those are two 4-byte type changes: the
     * copy becomes `meta`, then the original becomes `free`. No item's data moves.
     */
    fun planInsert(src: ByteSource, layout: HeifBareLayout, newItem: ByteArray): HeifExifPlan? {
        val p = layout.parsed
        val trailerLength = src.size - p.heifEnd
        if (trailerLength < 0 || trailerLength > MAX_TRAILER_BYTES) return null
        if (newItem.isEmpty() || newItem.size > MAX_ITEM_BYTES) return null
        val trailer = if (trailerLength > 0) src.read(p.heifEnd, trailerLength.toInt()) else ByteArray(0)

        val newStart = p.heifEnd
        val probe = rebuildMeta(p, 0L, newItem.size.toLong()) ?: return null
        val itemOffset = newStart + probe.size + 8
        val meta = rebuildMeta(p, itemOffset, newItem.size.toLong()) ?: return null
        if (meta.size != probe.size || meta.size > MAX_META_BYTES) return null

        val stagedMeta = meta.copyOf().also { "free".toByteArray(Charsets.ISO_8859_1).copyInto(it, 4) }
        val mdat = toBe(8L + newItem.size, 4) + "mdat".toByteArray(Charsets.ISO_8859_1) + newItem
        val oldTypeAt = p.metaBox.start + 4
        return HeifExifPlan(
            dataWrites = listOf(FileWrite(newStart, stagedMeta + mdat + trailer)),
            pointerWrites = listOf(
                FileWrite(newStart + 4, "meta".toByteArray(Charsets.ISO_8859_1)),
                FileWrite(oldTypeAt, "free".toByteArray(Charsets.ISO_8859_1)),
            ),
            undoWrites = listOf(
                FileWrite(oldTypeAt, "meta".toByteArray(Charsets.ISO_8859_1)),
                FileWrite(newStart, trailer),
            ),
            oldLength = src.size,
            newLength = src.size + meta.size + mdat.size,
            newItemOffset = itemOffset,
            newItem = newItem,
            trailer = trailer,
        )
    }

    /** [verify] for a [planInsert] plan. */
    fun verifyInsert(src: ByteSource, before: HeifBareLayout, plan: HeifExifPlan, trailerStart: Long?): Boolean {
        if (src.size != plan.newLength) return false
        val after = locate(src, trailerStart) ?: return false
        if (after.exifItemOffset != plan.newItemOffset || after.exifItemLength != plan.newItem.size) return false
        if (after.rotationCcw != before.rotationCcw || after.mirrored != before.mirrored) return false
        if (after.otherDataEnd != before.otherDataEnd) return false
        if (!src.read(after.exifItemOffset, after.exifItemLength).contentEquals(plan.newItem)) return false
        if (plan.trailer.isNotEmpty() &&
            !src.read(src.size - plan.trailer.size, plan.trailer.size).contentEquals(plan.trailer)
        ) return false
        return true
    }

    /** The Exif item for a block in [ExifEditor]'s form, laid out as HeifWriter does it. */
    fun newItem(block: ByteArray): ByteArray = byteArrayOf(0, 0, 0, EXIF_SIGNATURE.size.toByte()) + block

    /**
     * The meta box with an Exif item added whose data is at absolute [itemOffset]. Null when a
     * field the item needs cannot hold its value.
     */
    private fun rebuildMeta(p: Parsed, itemOffset: Long, itemLength: Long): ByteArray? {
        val m = p.meta
        val iloc = p.iloc
        if (!fits(itemOffset, iloc.offsetSize) || !fits(itemLength, iloc.lengthSize)) return null

        val newId = (p.types.keys + iloc.items.keys + p.primary).max() + 1
        val iinf = p.children.first { it.type == "iinf" }
        val ilocBox = p.children.first { it.type == "iloc" }
        val iref = p.children.firstOrNull { it.type == "iref" }

        // Item IDs are 16-bit wherever these versions say so.
        if (iloc.version < 2 && newId > 0xFFFF) return null
        if (newId > 0xFFFF) return null // infe v2 carries a 16-bit ID
        val irefVersion = iref?.let { m[it.bodyStart].toInt() } ?: 0
        if (irefVersion > 1) return null
        if (irefVersion == 0 && (newId > 0xFFFF || p.primary > 0xFFFF)) return null

        // iinf: one more entry, an infe v2 of type Exif with an empty name.
        val iinfBody = m.copyOfRange(iinf.bodyStart, iinf.end)
        val iinfCountSize = if (iinfBody[0].toInt() == 0) 2 else 4
        val iinfCount = be(iinfBody, 4, iinfCountSize) + 1
        if (!fits(iinfCount, iinfCountSize)) return null
        toBe(iinfCount, iinfCountSize).copyInto(iinfBody, 4)
        val infe = box("infe", byteArrayOf(2, 0, 0, 0) + toBe(newId, 2) + toBe(0, 2) +
            "Exif".toByteArray(Charsets.ISO_8859_1) + byteArrayOf(0))
        val newIinf = box("iinf", iinfBody + infe)

        // iloc: one more item, a single extent in this file at itemOffset.
        val ilocBody = m.copyOfRange(ilocBox.bodyStart, ilocBox.end)
        val idSize = if (iloc.version < 2) 2 else 4
        val ilocCount = be(ilocBody, 6, idSize) + 1
        if (!fits(ilocCount, idSize)) return null
        toBe(ilocCount, idSize).copyInto(ilocBody, 6)
        var entry = toBe(newId, idSize)
        if (iloc.version >= 1) entry += toBe(0, 2) // construction_method 0: this file
        entry += toBe(0, 2) + ByteArray(iloc.baseSize) + toBe(1, 2) + ByteArray(iloc.indexSize) +
            toBe(itemOffset, iloc.offsetSize) + toBe(itemLength, iloc.lengthSize)
        val newIloc = box("iloc", ilocBody + entry)

        // iref: the Exif item describes the primary image.
        val idWidth = if (irefVersion == 0) 2 else 4
        val cdsc = box("cdsc", toBe(newId, idWidth) + toBe(1, 2) + toBe(p.primary, idWidth))
        val newIref = if (iref != null) box("iref", m.copyOfRange(iref.bodyStart, iref.end) + cdsc)
        else box("iref", byteArrayOf(0, 0, 0, 0) + cdsc)

        var body = m.copyOfRange(p.metaBox.headerSize, p.metaBox.headerSize + 4) // version + flags
        for (child in p.children) {
            body += when (child.type) {
                "iinf" -> newIinf
                "iloc" -> newIloc
                "iref" -> newIref
                else -> m.copyOfRange(child.start.toInt(), child.end)
            }
        }
        if (iref == null) body += newIref
        return box("meta", body)
    }

    private fun box(type: String, body: ByteArray): ByteArray =
        toBe(8L + body.size, 4) + type.toByteArray(Charsets.ISO_8859_1) + body

    /** The furthest byte any in-file item other than [except] reaches. */
    private fun otherDataEnd(iloc: Iloc, except: Long?): Long {
        var otherEnd = 0L
        for ((id, item) in iloc.items) {
            if (id == except || item.constructionMethod != 0) continue
            for (e in item.extents) {
                // A zero length means "to the end of the file": nothing may be inserted before it.
                otherEnd = maxOf(otherEnd, if (e.length == 0L) Long.MAX_VALUE else item.baseOffset + e.offset + e.length)
            }
        }
        return otherEnd
    }

    /** The parts of a HEIF both [locate] and [locateBare] need. */
    internal class Parsed(
        internal val heifEnd: Long,
        internal val metaBox: Box,
        /** The whole meta box, header included; child box positions are relative to it. */
        internal val meta: ByteArray,
        internal val children: List<Box>,
        internal val primary: Long,
        internal val types: Map<Long, String>,
        internal val iloc: Iloc,
        internal val rotationCcw: Int,
        internal val mirrored: Boolean,
    )

    private fun parse(src: ByteSource, trailerStart: Long?): Parsed? {
        val limit = (trailerStart ?: src.size).coerceIn(0, src.size)
        var position = 0L
        var heifEnd = 0L
        var sawFtyp = false
        var meta: Box? = null

        while (position + 8 <= limit) {
            val header = src.read(position, minOf(16L, limit - position).toInt())
            var size = be(header, 0, 4)
            val type = String(header, 4, 4, Charsets.ISO_8859_1)
            var headerSize = 8
            if (size == 1L) {
                if (header.size < 16) break
                size = be(header, 8, 8)
                headerSize = 16
            } else if (size == 0L) {
                size = limit - position
            }
            if (type !in TOP_LEVEL_TYPES || size < headerSize || position + size > limit) break
            if (type == "ftyp") sawFtyp = true
            if (type == "meta") {
                if (meta != null) return null // two meta boxes: not something to guess about
                meta = Box(type, position, size, headerSize)
            }
            position += size
            heifEnd = position
        }
        val metaBox = meta ?: return null
        if (!sawFtyp || metaBox.size > MAX_META_BYTES) return null

        val m = src.read(metaBox.start, metaBox.size.toInt())
        val children = boxes(m, metaBox.headerSize + 4, m.size) // meta is a FullBox

        val primary = children.firstOrNull { it.type == "pitm" }?.let { pitm ->
            val body = pitm.bodyStart
            if (m[body].toInt() == 0) be(m, body + 4, 2) else be(m, body + 4, 4)
        } ?: return null

        val types = children.firstOrNull { it.type == "iinf" }?.let { itemTypes(m, it) } ?: return null
        val iloc = children.firstOrNull { it.type == "iloc" }?.let { parseIloc(m, it) } ?: return null

        var rotation = 0
        var mirrored = false
        children.firstOrNull { it.type == "iprp" }?.let { iprp ->
            val parts = boxes(m, iprp.bodyStart, iprp.end)
            val properties = parts.firstOrNull { it.type == "ipco" }?.let { boxes(m, it.bodyStart, it.end) }.orEmpty()
            val ipma = parts.firstOrNull { it.type == "ipma" } ?: return@let
            for (index in propertyIndices(m, ipma, primary)) {
                val property = properties.getOrNull(index - 1) ?: continue
                when (property.type) {
                    "irot" -> rotation = (m[property.bodyStart].toInt() and 0x03) * 90
                    "imir" -> mirrored = true
                }
            }
        }

        return Parsed(heifEnd, metaBox, m, children, primary, types, iloc, rotation, mirrored)
    }

    /**
     * Splits an Exif item into the bytes to keep (its 4-byte header and anything before the TIFF
     * header) and an EXIF block in the form [ExifEditor] takes, `Exif\0\0` plus TIFF.
     */
    fun splitItem(item: ByteArray): Pair<ByteArray, ByteArray>? {
        if (item.size < 4 + 8) return null
        val tiffAt = 4 + be(item, 0, 4)
        if (tiffAt < 4 || tiffAt + 8 > item.size) return null
        return item.copyOfRange(0, tiffAt.toInt()) to (EXIF_SIGNATURE + item.copyOfRange(tiffAt.toInt(), item.size))
    }

    /** The inverse of [splitItem], with an edited block. */
    fun joinItem(head: ByteArray, block: ByteArray): ByteArray = head + block.copyOfRange(EXIF_SIGNATURE.size, block.size)

    /** Builds the writes that make [newItem] the file's Exif item, or null if that is not safe here. */
    fun plan(src: ByteSource, layout: HeifExifLayout, newItem: ByteArray): HeifExifPlan? {
        val old = src.read(layout.exifItemOffset, layout.exifItemLength)
        if (newItem.size == layout.exifItemLength) {
            return HeifExifPlan(
                dataWrites = listOf(FileWrite(layout.exifItemOffset, newItem)),
                pointerWrites = emptyList(),
                undoWrites = listOf(FileWrite(layout.exifItemOffset, old)),
                oldLength = src.size,
                newLength = src.size,
                newItemOffset = layout.exifItemOffset,
                newItem = newItem,
                trailer = ByteArray(0),
            )
        }

        // Shifting anything some item points at would break it. MediaMuxer output keeps all image
        // data in the first mdat, well before the insertion point; refuse anything else.
        if (layout.otherDataEnd > layout.heifEnd) return null
        val trailerLength = src.size - layout.heifEnd
        if (trailerLength < 0 || trailerLength > MAX_TRAILER_BYTES) return null
        val trailer = if (trailerLength > 0) src.read(layout.heifEnd, trailerLength.toInt()) else ByteArray(0)

        val boxSize = 8L + newItem.size
        val newItemOffset = layout.heifEnd + 8
        val relativeOffset = newItemOffset - layout.baseOffset
        if (!fits(relativeOffset, layout.offsetFieldSize) || !fits(newItem.size.toLong(), layout.lengthFieldSize)) return null

        val box = toBe(boxSize, 4) + "mdat".toByteArray(Charsets.ISO_8859_1) + newItem
        return HeifExifPlan(
            dataWrites = listOf(FileWrite(layout.heifEnd, box + trailer)),
            pointerWrites = listOf(
                FileWrite(layout.offsetField, toBe(relativeOffset, layout.offsetFieldSize)),
                FileWrite(layout.lengthField, toBe(newItem.size.toLong(), layout.lengthFieldSize)),
            ),
            undoWrites = listOf(
                FileWrite(layout.offsetField, src.read(layout.offsetField, layout.offsetFieldSize)),
                FileWrite(layout.lengthField, src.read(layout.lengthField, layout.lengthFieldSize)),
                FileWrite(layout.heifEnd, trailer),
            ),
            oldLength = src.size,
            newLength = src.size + boxSize,
            newItemOffset = newItemOffset,
            newItem = newItem,
            trailer = trailer,
        )
    }

    /**
     * Whether the file now reads back exactly as [plan] intended, judged from a fresh parse. Called
     * after the writes; anything but true means they are undone.
     */
    fun verify(src: ByteSource, before: HeifExifLayout, plan: HeifExifPlan, trailerStart: Long?): Boolean {
        if (src.size != plan.newLength) return false
        val after = locate(src, trailerStart) ?: return false
        if (after.exifItemOffset != plan.newItemOffset || after.exifItemLength != plan.newItem.size) return false
        if (after.rotationCcw != before.rotationCcw || after.mirrored != before.mirrored) return false
        if (after.otherDataEnd != before.otherDataEnd) return false
        if (!src.read(after.exifItemOffset, after.exifItemLength).contentEquals(plan.newItem)) return false
        if (plan.trailer.isNotEmpty() &&
            !src.read(src.size - plan.trailer.size, plan.trailer.size).contentEquals(plan.trailer)
        ) return false
        return true
    }

    // region box parsing

    internal class Box(val type: String, val start: Long, val size: Long, val headerSize: Int) {
        val bodyStart: Int get() = (start + headerSize).toInt()
        val end: Int get() = (start + size).toInt()
    }

    /** Child boxes within [from, until) of an in-memory buffer; stops at the first malformed one. */
    private fun boxes(b: ByteArray, from: Int, until: Int): List<Box> {
        val out = ArrayList<Box>()
        var at = from
        while (at + 8 <= until) {
            var size = be(b, at, 4)
            var headerSize = 8
            if (size == 1L) {
                if (at + 16 > until) break
                size = be(b, at + 8, 8)
                headerSize = 16
            } else if (size == 0L) {
                size = (until - at).toLong()
            }
            if (size < headerSize || at + size > until) break
            out += Box(String(b, at + 4, 4, Charsets.ISO_8859_1), at.toLong(), size, headerSize)
            at += size.toInt()
        }
        return out
    }

    /** item_ID → item_type from `iinf`. Only version 2+ entries carry a type. */
    private fun itemTypes(m: ByteArray, iinf: Box): Map<Long, String> {
        val version = m[iinf.bodyStart].toInt()
        val first = iinf.bodyStart + 4 + if (version == 0) 2 else 4
        val out = HashMap<Long, String>()
        for (infe in boxes(m, first, iinf.end)) {
            if (infe.type != "infe") continue
            val v = m[infe.bodyStart].toInt()
            val at = infe.bodyStart + 4
            when (v) {
                2 -> out[be(m, at, 2)] = String(m, at + 4, 4, Charsets.ISO_8859_1)
                3 -> out[be(m, at, 4)] = String(m, at + 6, 4, Charsets.ISO_8859_1)
            }
        }
        return out
    }

    internal class Extent(val offset: Long, val length: Long, val offsetField: Int, val lengthField: Int)

    internal class IlocItem(
        val constructionMethod: Int,
        val dataReference: Int,
        val baseOffset: Long,
        val extents: List<Extent>,
    )

    internal class Iloc(
        val version: Int,
        val offsetSize: Int,
        val lengthSize: Int,
        val baseSize: Int,
        val indexSize: Int,
        val items: Map<Long, IlocItem>,
    )

    private fun parseIloc(m: ByteArray, box: Box): Iloc? {
        val version = m[box.bodyStart].toInt()
        if (version > 2) return null
        var at = box.bodyStart + 4
        val offsetSize = (m[at].toInt() shr 4) and 0x0F
        val lengthSize = m[at].toInt() and 0x0F
        val baseSize = (m[at + 1].toInt() shr 4) and 0x0F
        val indexSize = if (version == 0) 0 else m[at + 1].toInt() and 0x0F
        if (listOf(offsetSize, lengthSize, baseSize, indexSize).any { it !in setOf(0, 4, 8) }) return null
        at += 2
        val idSize = if (version < 2) 2 else 4
        val count = be(m, at, idSize).toInt(); at += idSize
        val items = HashMap<Long, IlocItem>()
        repeat(count) {
            if (at + idSize > box.end) return null
            val id = be(m, at, idSize); at += idSize
            var method = 0
            if (version >= 1) { method = (be(m, at, 2) and 0x0F).toInt(); at += 2 }
            val dataRef = be(m, at, 2).toInt(); at += 2
            val base = be(m, at, baseSize); at += baseSize
            val extentCount = be(m, at, 2).toInt(); at += 2
            val extents = ArrayList<Extent>(extentCount)
            repeat(extentCount) {
                at += indexSize
                val offsetField = at
                val offset = be(m, at, offsetSize); at += offsetSize
                val lengthField = at
                val length = be(m, at, lengthSize); at += lengthSize
                if (at > box.end) return null
                extents += Extent(offset, length, offsetField, lengthField)
            }
            items[id] = IlocItem(method, dataRef, base, extents)
        }
        return Iloc(version, offsetSize, lengthSize, baseSize, indexSize, items)
    }

    /** 1-based property indices associated with [itemId] in `ipma`. */
    private fun propertyIndices(m: ByteArray, ipma: Box, itemId: Long): List<Int> {
        val version = m[ipma.bodyStart].toInt()
        val wide = (m[ipma.bodyStart + 3].toInt() and 0x01) != 0
        var at = ipma.bodyStart + 4
        val count = be(m, at, 4); at += 4
        for (i in 0 until count) {
            if (at >= ipma.end) break
            val id = if (version < 1) be(m, at, 2).also { at += 2 } else be(m, at, 4).also { at += 4 }
            val associations = m[at].toInt() and 0xFF; at += 1
            val indices = ArrayList<Int>(associations)
            repeat(associations) {
                indices += if (wide) (be(m, at, 2).toInt() and 0x7FFF).also { at += 2 }
                else (m[at].toInt() and 0x7F).also { at += 1 }
            }
            if (id == itemId) return indices
        }
        return emptyList()
    }

    // endregion

    private fun be(b: ByteArray, at: Int, n: Int): Long {
        var v = 0L
        for (i in 0 until n) v = (v shl 8) or (b[at + i].toLong() and 0xFF)
        return v
    }

    private fun toBe(value: Long, n: Int): ByteArray = ByteArray(n) { i -> (value shr (8 * (n - 1 - i))).toByte() }

    private fun fits(value: Long, n: Int): Boolean = value >= 0 && (n >= 8 || value < (1L shl (8 * n)))
}
