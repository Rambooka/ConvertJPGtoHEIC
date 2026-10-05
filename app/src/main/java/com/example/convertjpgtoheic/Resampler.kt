package com.example.convertjpgtoheic

import kotlin.math.PI
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Converts interleaved float PCM from one sample rate to another, streaming.
 *
 * Needed because Android's Opus encoder only takes 8, 12, 16, 24 or 48 kHz, and nearly every MP3
 * is 44.1 kHz. Media3's Sonic resampler interpolates linearly, which leaves images of the music
 * folded back into the audible band (about -23 dB for a 10 kHz tone at 44.1 → 48 kHz); this is
 * a windowed-sinc polyphase filter instead, flat to ~20 kHz and better than -80 dB outside it.
 *
 * Kept free of Android types so it can be tested on the JVM.
 */
class Resampler(private val inRate: Int, private val outRate: Int, private val channels: Int) {

    /** Output advances [down] input samples for every [up] output samples: the rate ratio in lowest terms. */
    private val up: Int
    private val down: Int

    /** Input samples either side of each output sample the filter reaches. */
    private val half: Int
    private val taps: Int

    /** One row of [taps] coefficients per output phase. */
    private val table: FloatArray

    /** Unconsumed input, interleaved; frame 0 is input frame [bufStart]. */
    private var buf = FloatArray(0)
    private var bufFrames = 0
    private var bufStart: Long

    private var inFrames = 0L
    private var outFrames = 0L

    init {
        require(inRate > 0 && outRate > 0 && channels > 0)
        val g = gcd(inRate, outRate)
        up = outRate / g
        down = inRate / g
        require(up <= MAX_PHASES) { "unsupported rate pair $inRate → $outRate" }

        // Cutoff as a fraction of the input Nyquist: just under it when upsampling, just under the
        // output's Nyquist when downsampling, which also widens the filter to keep its sharpness.
        val scale = min(1.0, outRate.toDouble() / inRate)
        val cutoff = CUTOFF * scale
        half = ceil(HALF_TAPS / scale).toInt()
        taps = 2 * half
        table = FloatArray(up * taps)
        for (p in 0 until up) {
            var sum = 0.0
            val row = DoubleArray(taps)
            for (j in 0 until taps) {
                // Distance from the output instant to input sample (n - half + 1 + j).
                val t = (j - half + 1) - p.toDouble() / up
                row[j] = cutoff * sinc(cutoff * t) * kaiser(t / half)
                sum += row[j]
            }
            // Unity gain at DC for every phase, or the phases would ripple against each other.
            for (j in 0 until taps) table[p * taps + j] = (row[j] / sum).toFloat()
        }
        // Prime with silence so the first output sits exactly on input frame 0.
        bufStart = -(half - 1).toLong()
        append(FloatArray((half - 1) * channels), half - 1)
    }

    /** Feeds [frames] frames of interleaved [input]; returns whatever output they complete. */
    fun process(input: FloatArray, frames: Int = input.size / channels): FloatArray {
        append(input, frames)
        inFrames += frames
        return drain(Long.MAX_VALUE)
    }

    /** Ends the stream: returns the remaining output, so the total is exactly in × out / in, rounded up. */
    fun flush(): FloatArray {
        append(FloatArray(taps * channels), taps)
        return drain((inFrames * up + down - 1) / down)
    }

    private fun drain(limit: Long): FloatArray {
        val out = FloatArrayBuilder(((bufFrames.toLong() * up / down + 2) * channels).toInt())
        val end = bufStart + bufFrames
        while (outFrames < limit) {
            val pos = outFrames * down
            val n = pos / up
            if (n + half >= end) break
            val row = (pos % up).toInt() * taps
            val first = ((n - half + 1 - bufStart) * channels).toInt()
            for (c in 0 until channels) {
                var acc = 0f
                var i = first + c
                for (j in 0 until taps) {
                    acc += buf[i] * table[row + j]
                    i += channels
                }
                out.add(acc)
            }
            outFrames++
        }
        // Drop input no later output can reach.
        val keepFrom = (outFrames * down) / up - half + 1
        val drop = (keepFrom - bufStart).coerceIn(0L, bufFrames.toLong()).toInt()
        if (drop > 0) {
            System.arraycopy(buf, drop * channels, buf, 0, (bufFrames - drop) * channels)
            bufFrames -= drop
            bufStart += drop
        }
        return out.build()
    }

    private fun append(input: FloatArray, frames: Int) {
        val needed = (bufFrames + frames) * channels
        if (needed > buf.size) buf = buf.copyOf(max(needed, buf.size * 2))
        System.arraycopy(input, 0, buf, bufFrames * channels, frames * channels)
        bufFrames += frames
    }

    private class FloatArrayBuilder(capacity: Int) {
        private var data = FloatArray(max(capacity, 16))
        private var size = 0
        fun add(v: Float) {
            if (size == data.size) data = data.copyOf(size * 2)
            data[size++] = v
        }
        fun build(): FloatArray = if (size == data.size) data else data.copyOf(size)
    }

    companion object {
        /** Input samples each side at 1:1; 128 taps reach -80 dB inside a ~2 kHz transition at 44.1 kHz. */
        private const val HALF_TAPS = 64

        /** Passband edge as a fraction of Nyquist: ~21 kHz centre at 44.1 kHz, flat to ~20 kHz. */
        private const val CUTOFF = 0.955

        /** Kaiser beta: ~90 dB sidelobes. */
        private const val BETA = 9.0

        /** Phases in the coefficient table. Every MP3 rate to 48 kHz needs at most 640. */
        private const val MAX_PHASES = 4096

        private fun gcd(a: Int, b: Int): Int = if (b == 0) a else gcd(b, a % b)

        private fun sinc(x: Double): Double = if (x == 0.0) 1.0 else sin(PI * x) / (PI * x)

        private fun kaiser(x: Double): Double {
            if (x <= -1.0 || x >= 1.0) return 0.0
            return besselI0(BETA * sqrt(1 - x * x)) / besselI0(BETA)
        }

        private fun besselI0(x: Double): Double {
            var sum = 1.0
            var term = 1.0
            val q = x * x / 4
            var k = 1
            while (term > sum * 1e-12) {
                term *= q / (k.toDouble() * k)
                sum += term
                k++
            }
            return sum
        }
    }
}
