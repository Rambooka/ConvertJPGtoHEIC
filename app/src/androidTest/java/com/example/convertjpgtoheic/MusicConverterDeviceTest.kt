package com.example.convertjpgtoheic

import android.net.Uri
import android.provider.MediaStore
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * The music pipeline on the phone's real codecs and extractors, on generated songs. What this
 * guards against is the failure found by hand on 19 Oct: a copy that verifies against what was
 * decoded while the decode itself fell short of the source (Samsung's FLAC extractor).
 */
@RunWith(AndroidJUnit4::class)
class MusicConverterDeviceTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var dir: File

    @Before
    fun setUp() {
        dir = File(context.cacheDir, "device-test-music").apply { deleteRecursively(); mkdirs() }
    }

    @After
    fun tearDown() {
        dir.deleteRecursively()
    }

    private fun convert(source: File, seconds: Double): File {
        val track = SourceTrack(
            uri = Uri.fromFile(source),
            displayName = source.name,
            relativePath = "Music/",
            volumeName = MediaStore.VOLUME_EXTERNAL_PRIMARY,
            sizeBytes = source.length(),
            durationMs = (seconds * 1000).toLong(),
            bitrate = null,
        )
        val out = File(dir, "out.opus")
        val outcome = MusicConverter(context).convert(track, out, File(dir, "raw.opus")) {}
        assertTrue("conversion failed: $outcome", outcome is MusicConverter.Outcome.Converted)
        return out
    }

    /** Opus adds encoder padding of a few tens of milliseconds; anything more is lost audio. */
    private fun assertWhole(expectedMs: Long, opus: File) {
        val got = TestMedia.durationMs(opus)
        assertTrue("copy runs $got ms, source $expectedMs ms", kotlin.math.abs(got - expectedMs) <= 60)
    }

    @Test
    fun aacInMp4BecomesAWholeOpus() {
        assumeTrue(TestMedia.hasEncoder("audio/mp4a-latm"))
        val m4a = File(dir, "song.m4a").also { TestMedia.aacM4a(it, 8.0) }
        assertWhole(8000, convert(m4a, 8.0))
    }

    @Test
    fun flacBecomesAWholeOpus() {
        assumeTrue(TestMedia.hasEncoder("audio/flac"))
        val flac = File(dir, "song.flac").also { TestMedia.flac(it, 8.0) }
        // The file must be one FlacFrames reads end to end, as a real FLAC is.
        val samples = java.io.RandomAccessFile(flac, "r").use { raf ->
            val src = FdSource(raf.fd, flac.length())
            val (info, start) = FlacFrames.open(src)!!
            val reader = FlacFrames.Reader(src, info, start)
            generateSequence { reader.next() }.sumOf { it.blockSize.toLong() }
        }
        assertEquals(8L * TestMedia.RATE, samples)

        assertWhole(8000, convert(flac, 8.0))
    }
}
