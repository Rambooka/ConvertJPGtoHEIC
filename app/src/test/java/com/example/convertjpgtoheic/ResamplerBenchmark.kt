package com.example.convertjpgtoheic

import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * How fast the resampler runs, as a multiple of real time, on the JVM. Skipped unless
 * `RESAMPLER_BENCH=1`: it is a measurement, not a check. A phone's big core is roughly a third to
 * a fifth of a desktop core, so divide accordingly.
 */
class ResamplerBenchmark {

    @Test
    fun `stereo 44100 to 48000, three minutes`() {
        assumeTrue(System.getenv("RESAMPLER_BENCH") == "1")
        val seconds = 180
        val chunk = 1152 // one MP3 frame of stereo samples per call, as the converter feeds it
        val input = FloatArray(chunk * 2) { kotlin.math.sin(it * 0.01).toFloat() }
        val calls = seconds * 44_100 / chunk
        repeat(3) { round ->
            val r = Resampler(44_100, 48_000, 2)
            val t0 = System.nanoTime()
            var out = 0L
            repeat(calls) { out += r.process(input, chunk).size }
            out += r.flush().size
            val ms = (System.nanoTime() - t0) / 1_000_000
            println("round $round: $seconds s of audio in $ms ms = ${"%.0f".format(seconds * 1000.0 / ms)}x real time (${out / 2} frames)")
        }
    }
}
