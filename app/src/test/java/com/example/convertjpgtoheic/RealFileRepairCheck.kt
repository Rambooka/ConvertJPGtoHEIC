package com.example.convertjpgtoheic

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/**
 * Runs the repair, end to end but in memory, over real HEICs — the check to run before trusting a
 * change with a library whose originals are gone.
 *
 * Skipped unless `REPAIR_SAMPLES_DIR` names a folder of `.heic` files, so no photos ever need to be
 * checked in. Repaired copies are written to `<dir>/repaired/` for inspection in other tools.
 */
class RealFileRepairCheck {

    @Test
    fun `repairs real files and leaves the image untouched`() {
        val dir = System.getenv("REPAIR_SAMPLES_DIR")?.let(::File)
        assumeTrue("set REPAIR_SAMPLES_DIR to run", dir != null && dir.isDirectory)
        val out = File(dir, "repaired").apply { mkdirs() }

        val files = dir!!.listFiles { f -> f.isFile && f.name.endsWith(".heic", ignoreCase = true) }!!.sorted()
        assumeTrue("no .heic files in $dir", files.isNotEmpty())

        for (file in files) {
            val original = file.readBytes()
            val src = ByteArraySource(original)
            // Every sample is treated as undated, to exercise the date path as hard as possible.
            val diagnosis = HeicRepair.diagnose(src, file.name, undated = true, knownUtcMs = null)
            println("${file.name}: $diagnosis")
            val fix = (diagnosis as? HeicRepair.Diagnosis.Needs)?.fix ?: continue

            val layout = HeifExif.locate(src)!!
            val item = original.copyOfRange(layout.exifItemOffset.toInt(), (layout.exifItemOffset + layout.exifItemLength).toInt())
            val (head, block) = HeifExif.splitItem(item)!!
            val edited = ExifEditor.edit(block, fix.orientation, fix.dateTimeOriginal, fix.offset)!!
            val plan = HeifExif.plan(src, layout, HeifExif.joinItem(head, edited))!!
            val repaired = MetadataTestData.applied(original, plan.dataWrites + plan.pointerWrites)

            assertTrue("${file.name} did not verify", HeifExif.verify(ByteArraySource(repaired), layout, plan, null))
            // Everything before the Exif item's old home, and the old item itself, is unchanged apart
            // from the iloc pointer bytes: compare the whole original range with those masked.
            val masked = repaired.copyOf(original.size)
            for (w in plan.pointerWrites) {
                original.copyInto(masked, w.position.toInt(), w.position.toInt(), (w.position + w.bytes.size).toInt())
            }
            if (plan.trailer.isEmpty() && plan.pointerWrites.isNotEmpty()) {
                assertArrayEquals("${file.name}: bytes other than the pointers changed", original, masked)
            }
            File(out, file.name).writeBytes(repaired)
        }
    }
}
