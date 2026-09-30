package com.example.convertjpgtoheic

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneOffset

class HeifExifTest {

    private val exif = MetadataTestData.samsungLikeExif(1, "2017:12:08 14:26:56")

    @Test
    fun `finds the Exif item and the container rotation`() {
        val heif = MetadataTestData.heif(exif, rotationCcw = 90)
        val layout = HeifExif.locate(ByteArraySource(heif.bytes))!!
        assertEquals(90, layout.rotationCcw)
        assertFalse(layout.mirrored)
        assertEquals(heif.bytes.size.toLong(), layout.heifEnd)
        val (_, block) = HeifExif.splitItem(heif.bytes.copyOfRange(layout.exifItemOffset.toInt(), (layout.exifItemOffset + layout.exifItemLength).toInt()))!!
        assertArrayEquals(exif, block)
    }

    @Test
    fun `reports a mirror`() {
        val layout = HeifExif.locate(ByteArraySource(MetadataTestData.heif(exif, mirrored = true).bytes))!!
        assertTrue(layout.mirrored)
    }

    @Test
    fun `a same-length edit is written in place`() {
        val heif = MetadataTestData.heif(exif)
        val src = ByteArraySource(heif.bytes)
        val layout = HeifExif.locate(src)!!
        val edited = edit(heif.bytes, layout) { ExifEditor.edit(it, orientation = 6)!! }
        val plan = HeifExif.plan(src, layout, edited)!!
        assertTrue(plan.pointerWrites.isEmpty())
        val after = MetadataTestData.applied(heif.bytes, plan.dataWrites)
        assertEquals(heif.bytes.size, after.size)
        assertTrue(HeifExif.verify(ByteArraySource(after), layout, plan, null))
    }

    @Test
    fun `a longer item goes in a new box and only the Exif pointers move`() {
        val heif = MetadataTestData.heif(exif)
        val src = ByteArraySource(heif.bytes)
        val layout = HeifExif.locate(src)!!
        val edited = edit(heif.bytes, layout) { ExifEditor.edit(it, orientation = 8, offset = ZoneOffset.ofHours(13))!! }
        val plan = HeifExif.plan(src, layout, edited)!!

        val after = MetadataTestData.applied(heif.bytes, plan.dataWrites + plan.pointerWrites)
        assertTrue(HeifExif.verify(ByteArraySource(after), layout, plan, null))

        // The image data is untouched, byte for byte.
        assertArrayEquals(
            heif.bytes.copyOfRange(heif.imageOffset, heif.imageOffset + heif.imageLength),
            after.copyOfRange(heif.imageOffset, heif.imageOffset + heif.imageLength),
        )
        // Re-parsed from scratch, the file offers the new EXIF.
        val relocated = HeifExif.locate(ByteArraySource(after))!!
        val block = HeifExif.splitItem(
            after.copyOfRange(relocated.exifItemOffset.toInt(), (relocated.exifItemOffset + relocated.exifItemLength).toInt())
        )!!.second
        val s = ExifEditor.summarise(block)!!
        assertEquals(8, s.orientation)
        assertTrue(s.hasOffsetTimeOriginal)
        assertEquals(90 * 3, relocated.rotationCcw)
        assertEquals("mdat", String(after, layout.heifEnd.toInt() + 4, 4, Charsets.ISO_8859_1))
    }

    @Test
    fun `a trailer stays at the very end, unchanged`() {
        val trailer = ByteArray(500) { (it * 13).toByte() } + "SEFT".toByteArray(Charsets.ISO_8859_1)
        val heif = MetadataTestData.heif(exif, trailer = trailer)
        val src = ByteArraySource(heif.bytes)
        val layout = HeifExif.locate(src)!!
        assertEquals((heif.bytes.size - trailer.size).toLong(), layout.heifEnd)

        val edited = edit(heif.bytes, layout) { ExifEditor.edit(it, offset = ZoneOffset.ofHours(13))!! }
        val plan = HeifExif.plan(src, layout, edited)!!
        val after = MetadataTestData.applied(heif.bytes, plan.dataWrites + plan.pointerWrites)

        assertArrayEquals(trailer, after.copyOfRange(after.size - trailer.size, after.size))
        assertTrue(HeifExif.verify(ByteArraySource(after), layout, plan, (after.size - trailer.size).toLong()))
    }

    @Test
    fun `box scanning stops at anything that is not a HEIF box`() {
        // A trailer that happens to start with a plausible box size must not be split.
        val trailer = byteArrayOf(0, 0, 0, 16) + "\u0010\u0000\u0000\u0000".toByteArray(Charsets.ISO_8859_1) + ByteArray(40)
        val heif = MetadataTestData.heif(exif, trailer = trailer)
        val layout = HeifExif.locate(ByteArraySource(heif.bytes))!!
        assertEquals((heif.bytes.size - trailer.size).toLong(), layout.heifEnd)
    }

    @Test
    fun `refuses to shift data that another item points at`() {
        val heif = MetadataTestData.heif(exif, trailer = ByteArray(400), imageExtentPastEnd = true)
        val src = ByteArraySource(heif.bytes)
        val layout = HeifExif.locate(src)!!
        val edited = edit(heif.bytes, layout) { ExifEditor.edit(it, offset = ZoneOffset.ofHours(13))!! }
        assertNull(HeifExif.plan(src, layout, edited))
    }

    @Test
    fun `undo puts the file back exactly`() {
        val trailer = ByteArray(200) { it.toByte() }
        val heif = MetadataTestData.heif(exif, trailer = trailer)
        val src = ByteArraySource(heif.bytes)
        val layout = HeifExif.locate(src)!!
        val edited = edit(heif.bytes, layout) { ExifEditor.edit(it, orientation = 6, offset = ZoneOffset.ofHours(12))!! }
        val plan = HeifExif.plan(src, layout, edited)!!

        val broken = MetadataTestData.applied(heif.bytes, plan.dataWrites + plan.pointerWrites)
        val restored = MetadataTestData.applied(broken, plan.undoWrites, truncateTo = plan.oldLength)
        assertArrayEquals(heif.bytes, restored)
    }

    @Test
    fun `verify rejects a file that did not read back as planned`() {
        val heif = MetadataTestData.heif(exif)
        val src = ByteArraySource(heif.bytes)
        val layout = HeifExif.locate(src)!!
        val edited = edit(heif.bytes, layout) { ExifEditor.edit(it, offset = ZoneOffset.ofHours(13))!! }
        val plan = HeifExif.plan(src, layout, edited)!!
        // Data written, pointers not: the file still reads the old item.
        val halfDone = MetadataTestData.applied(heif.bytes, plan.dataWrites)
        assertFalse(HeifExif.verify(ByteArraySource(halfDone), layout, plan, null))
    }

    @Test
    fun `rejects files that are not HEIF`() {
        assertNull(HeifExif.locate(ByteArraySource(ByteArray(100))))
        assertNull(HeifExif.locate(ByteArraySource(MetadataTestData.box("ftyp", ByteArray(8)))))
    }

    @Test
    fun `split and join are inverses`() {
        val heif = MetadataTestData.heif(exif)
        val layout = HeifExif.locate(ByteArraySource(heif.bytes))!!
        val item = heif.bytes.copyOfRange(layout.exifItemOffset.toInt(), (layout.exifItemOffset + layout.exifItemLength).toInt())
        val (head, block) = HeifExif.splitItem(item)!!
        assertNotNull(ExifEditor.summarise(block))
        assertArrayEquals(item, HeifExif.joinItem(head, block))
    }

    private fun edit(file: ByteArray, layout: HeifExifLayout, change: (ByteArray) -> ByteArray): ByteArray {
        val item = file.copyOfRange(layout.exifItemOffset.toInt(), (layout.exifItemOffset + layout.exifItemLength).toInt())
        val (head, block) = HeifExif.splitItem(item)!!
        return HeifExif.joinItem(head, change(block))
    }

    // region adding an Exif item

    private fun dated() = ExifEditor.edit(
        ExifEditor.empty(), orientation = 6, dateTimeOriginal = "2024:11:26 12:00:00", offset = ZoneOffset.ofHours(13),
    )!!

    @Test
    fun `adds an Exif item to a HEIF that has none, leaving the image alone`() {
        val heif = MetadataTestData.heif(ByteArray(0), rotationCcw = 270, withExif = false)
        val src = ByteArraySource(heif.bytes)
        assertNull(HeifExif.locate(src))
        val bare = HeifExif.locateBare(src)!!
        assertEquals(270, bare.rotationCcw)

        val plan = HeifExif.planInsert(src, bare, HeifExif.newItem(dated()))!!
        // Staged but not yet pointed at: the file still reads as it did.
        val staged = MetadataTestData.applied(heif.bytes, plan.dataWrites)
        assertArrayEquals(heif.bytes, staged.copyOf(heif.bytes.size))
        assertNull(HeifExif.locate(ByteArraySource(staged))) // the staged copy is still typed free

        val done = MetadataTestData.applied(staged, plan.pointerWrites)
        assertTrue(HeifExif.verifyInsert(ByteArraySource(done), bare, plan, null))
        val layout = HeifExif.locate(ByteArraySource(done))!!
        assertEquals(270, layout.rotationCcw)
        val block = HeifExif.splitItem(done.copyOfRange(layout.exifItemOffset.toInt(), (layout.exifItemOffset + layout.exifItemLength).toInt()))!!.second
        val s = ExifEditor.summarise(block)!!
        assertEquals("2024:11:26 12:00:00", s.dateTimeOriginal)
        assertTrue(s.hasOffsetTimeOriginal)
        assertEquals(6, s.orientation)
        assertArrayEquals(
            heif.bytes.copyOfRange(heif.imageOffset, heif.imageOffset + heif.imageLength),
            done.copyOfRange(heif.imageOffset, heif.imageOffset + heif.imageLength),
        )
    }

    @Test
    fun `adding an Exif item keeps a trailer at the end and can be undone`() {
        val trailer = ByteArray(64) { (it + 1).toByte() }
        val heif = MetadataTestData.heif(ByteArray(0), withExif = false, trailer = trailer)
        val metaEnd = (heif.bytes.size - trailer.size).toLong()
        val src = ByteArraySource(heif.bytes)
        val bare = HeifExif.locateBare(src, metaEnd)!!
        val plan = HeifExif.planInsert(src, bare, HeifExif.newItem(dated()))!!

        val done = MetadataTestData.applied(heif.bytes, plan.dataWrites + plan.pointerWrites)
        assertArrayEquals(trailer, done.copyOfRange(done.size - trailer.size, done.size))
        val trailerStart = (done.size - trailer.size).toLong()
        assertTrue(HeifExif.verifyInsert(ByteArraySource(done), bare, plan, trailerStart))

        val undone = MetadataTestData.applied(done, plan.undoWrites, truncateTo = plan.oldLength)
        assertArrayEquals(heif.bytes, undone)
    }

    @Test
    fun `will not add a second Exif item`() {
        val heif = MetadataTestData.heif(MetadataTestData.samsungLikeExif(1, "2017:07:28 08:20:28"))
        assertNull(HeifExif.locateBare(ByteArraySource(heif.bytes)))
    }

    @Test
    fun `will not add an Exif item when image data sits after meta`() {
        val heif = MetadataTestData.heif(ByteArray(0), withExif = false, imageExtentPastEnd = true)
        assertNull(HeifExif.locateBare(ByteArraySource(heif.bytes)))
    }

    // endregion
}
