package com.example.convertjpgtoheic

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.log10
import kotlin.math.sin
import kotlin.math.sqrt

class ResamplerTest {

    private fun sine(rate: Int, hz: Double, frames: Int, amplitude: Float = 0.5f, channels: Int = 1) =
        FloatArray(frames * channels) { i -> amplitude * sin(2 * PI * hz * (i / channels) / rate).toFloat() }

    private fun resample(input: FloatArray, inRate: Int, outRate: Int, channels: Int = 1, chunk: Int = Int.MAX_VALUE): FloatArray {
        val r = Resampler(inRate, outRate, channels)
        val parts = ArrayList<FloatArray>()
        var pos = 0
        val frames = input.size / channels
        while (pos < frames) {
            val n = minOf(chunk, frames - pos)
            parts += r.process(input.copyOfRange(pos * channels, (pos + n) * channels), n)
            pos += n
        }
        parts += r.flush()
        val out = FloatArray(parts.sumOf { it.size })
        var at = 0
        for (p in parts) { System.arraycopy(p, 0, out, at, p.size); at += p.size }
        return out
    }

    /** Amplitude of the [hz] component of [x], by Goertzel over the middle of the signal. */
    private fun amplitudeAt(x: FloatArray, rate: Int, hz: Double, channel: Int = 0, channels: Int = 1): Double {
        val frames = x.size / channels
        val from = frames / 4
        val n = frames / 2
        val w = 2 * PI * hz / rate
        var re = 0.0
        var im = 0.0
        for (i in 0 until n) {
            val v = x[(from + i) * channels + channel]
            re += v * cos(w * i)
            im -= v * sin(w * i)
        }
        return 2 * sqrt(re * re + im * im) / n
    }

    private fun db(ratio: Double) = 20 * log10(ratio)

    @Test
    fun `output length is the input length scaled by the rate ratio`() {
        for (frames in listOf(0, 1, 147, 1000, 44_100, 44_101)) {
            val out = resample(FloatArray(frames), 44_100, 48_000)
            assertEquals("frames=$frames", (frames.toLong() * 160 + 146) / 147, out.size.toLong())
        }
        assertEquals(48_000, resample(FloatArray(32_000), 32_000, 48_000).size)
        assertEquals(48_000, resample(FloatArray(11_025), 11_025, 48_000).size)
    }

    @Test
    fun `a tone keeps its pitch and level`() {
        val out = resample(sine(44_100, 1000.0, 44_100), 44_100, 48_000)
        assertEquals(0.5, amplitudeAt(out, 48_000, 1000.0), 0.005)
        // And nothing at a neighbouring frequency, which a wrong ratio would show as.
        assertTrue(amplitudeAt(out, 48_000, 1088.0) < 0.005)
    }

    @Test
    fun `the passband is flat up to 19 kHz`() {
        for (hz in listOf(50.0, 5_000.0, 15_000.0, 19_000.0)) {
            val out = resample(sine(44_100, hz, 44_100), 44_100, 48_000)
            val level = db(amplitudeAt(out, 48_000, hz) / 0.5)
            assertTrue("$hz Hz came out at $level dB", level > -0.1 && level < 0.1)
        }
    }

    @Test
    fun `images of the music stay out of the audible band`() {
        // Upsampling 44.1 → 48 kHz mirrors content at f to 44.1k - f, which folds back to
        // 48k - (44.1k - f). For a 10 kHz tone that would be an audible 13.9 kHz.
        val out = resample(sine(44_100, 10_000.0, 44_100), 44_100, 48_000)
        val image = db(amplitudeAt(out, 48_000, 13_900.0) / 0.5)
        assertTrue("image at $image dB", image < -80)
    }

    @Test
    fun `streaming in odd chunks gives the same result as all at once`() {
        val input = sine(44_100, 3_000.0, 20_000, channels = 2)
        val whole = resample(input, 44_100, 48_000, channels = 2)
        val chunked = resample(input, 44_100, 48_000, channels = 2, chunk = 37)
        assertArrayEquals(whole, chunked, 1e-6f)
    }

    @Test
    fun `stereo channels stay separate`() {
        val frames = 44_100
        val input = FloatArray(frames * 2)
        for (i in 0 until frames) {
            input[i * 2] = 0.5f * sin(2 * PI * 440.0 * i / 44_100).toFloat()
            input[i * 2 + 1] = 0.25f * sin(2 * PI * 2_000.0 * i / 44_100).toFloat()
        }
        val out = resample(input, 44_100, 48_000, channels = 2)
        assertEquals(0.5, amplitudeAt(out, 48_000, 440.0, 0, 2), 0.005)
        assertEquals(0.25, amplitudeAt(out, 48_000, 2_000.0, 1, 2), 0.005)
        assertTrue(amplitudeAt(out, 48_000, 2_000.0, 0, 2) < 1e-3)
        assertTrue(amplitudeAt(out, 48_000, 440.0, 1, 2) < 1e-3)
    }

    @Test
    fun `the first output sample lines up with the first input sample`() {
        // An impulse at frame 0 must peak at output frame 0, or the copy would lag its original.
        val input = FloatArray(1000).also { it[0] = 1f }
        val out = resample(input, 44_100, 48_000)
        val peak = out.indices.maxByOrNull { out[it] }!!
        assertEquals(0, peak)
    }
}
