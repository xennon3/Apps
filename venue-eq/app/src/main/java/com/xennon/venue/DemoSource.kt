package com.xennon.venue

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.sin
import kotlin.random.Random

/**
 * A 4-bar loop synthesised on the fly, so you can hear every room without needing
 * a music app. It ends each loop with a lone snare hit so the reverb tail is exposed.
 */
class DemoSource : AudioSource {
    override val sampleRate = RATE
    private val pcm: FloatArray = render()
    private var pos = 0

    override fun read(out: FloatArray, frames: Int): Int {
        var written = 0
        while (written < frames) {
            val n = minOf(frames - written, (pcm.size - pos) / 2)
            System.arraycopy(pcm, pos, out, written * 2, n * 2)
            written += n
            pos = (pos + n * 2) % pcm.size
        }
        return frames
    }

    override fun release() {}

    private fun render(): FloatArray {
        val beat = RATE * 60 / BPM
        val frames = beat * 16
        val l = FloatArray(frames)
        val r = FloatArray(frames)
        val rnd = Random(7)

        fun add(at: Int, len: Int, pan: Float, gen: (t: Float, i: Int) -> Float) {
            for (i in 0 until len) {
                val f = at + i
                if (f >= frames) break
                val s = gen(i.toFloat() / RATE, i)
                l[f] += s * (1f - pan)
                r[f] += s * (1f + pan)
            }
        }

        // Am - F - C - G, one bar each.
        val roots = floatArrayOf(55f, 43.65f, 65.41f, 49f)
        val chords = arrayOf(
            floatArrayOf(220f, 261.63f, 329.63f),
            floatArrayOf(174.61f, 220f, 261.63f),
            floatArrayOf(261.63f, 329.63f, 392f),
            floatArrayOf(196f, 246.94f, 293.66f),
        )

        for (bar in 0 until 4) {
            val b0 = bar * 4 * beat
            val lastBar = bar == 3
            for (q in 0 until 4) {
                val at = b0 + q * beat
                val breakdown = lastBar && q == 3
                // Kick: four on the floor, pitch sweeping down.
                if (!breakdown) add(at, (0.35f * RATE).toInt(), 0f) { t, _ ->
                    val f = 45f + 110f * exp(-t * 30f)
                    0.9f * sin(2f * PI.toFloat() * f * t) * exp(-t * 9f)
                }
                // Snare on 2 and 4.
                if (q == 1 || q == 3) add(at, (0.25f * RATE).toInt(), 0f) { t, _ ->
                    val noise = rnd.nextFloat() * 2f - 1f
                    (0.45f * noise * exp(-t * 18f) + 0.25f * sin(2f * PI.toFloat() * 185f * t) * exp(-t * 25f))
                }
                // Off-beat hats, alternating sides.
                if (!breakdown) {
                    var prev = 0f
                    add(at + beat / 2, (0.05f * RATE).toInt(), if (q % 2 == 0) -0.4f else 0.4f) { t, _ ->
                        val n = rnd.nextFloat() * 2f - 1f
                        val hp = n - prev
                        prev = n
                        0.18f * hp * exp(-t * 70f)
                    }
                }
                // Bass on the off-beats.
                if (!breakdown) add(at + beat / 2, beat / 2, 0f) { t, _ ->
                    val f = roots[bar]
                    val w = 2f * PI.toFloat() * f * t
                    0.35f * (sin(w) + 0.5f * sin(2 * w) + 0.25f * sin(3 * w)) * exp(-t * 6f)
                }
            }
            // Plucked chord stabs on beat 1 and the "and" of 2.
            for (offset in intArrayOf(0, beat + beat / 2)) {
                chords[bar].forEachIndexed { k, f ->
                    add(b0 + offset, (0.6f * RATE).toInt(), (k - 1) * 0.5f) { t, _ ->
                        val w = 2f * PI.toFloat() * f * t
                        0.12f * (sin(w) + 0.3f * sin(2 * w)) * exp(-t * 7f)
                    }
                }
            }
        }

        val peak = maxOf(l.maxOf { abs(it) }, r.maxOf { abs(it) }, 1e-6f)
        val gain = 0.7f / peak
        val out = FloatArray(frames * 2)
        for (i in 0 until frames) {
            out[2 * i] = l[i] * gain
            out[2 * i + 1] = r[i] * gain
        }
        return out
    }

    companion object {
        private const val RATE = 44100
        private const val BPM = 120
    }
}
