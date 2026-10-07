package com.xennon.venue

import java.util.concurrent.atomic.AtomicReference
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Venue's own stereo DSP: EQ -> bass shelf -> stereo width -> room reverb -> limiter.
 *
 * Unlike the phone's built-in effects this always works, on any phone and on the
 * speaker as well as headphones, so it's used for everything played inside the app.
 * Settings arrive from the UI thread through [update]; [process] runs on the audio thread.
 */
class RoomProcessor(private val sampleRate: Int) {
    private val next = AtomicReference<Any?>(UNSET)

    private val eq = Array(EQ_FREQS.size) { Biquad() }
    private val bassShelf = Biquad()
    private val reverb = ReverbEngine(sampleRate)
    private var active = false
    private var sideGain = 1f
    private var dryGain = 1f
    private var limiterEnv = 0f
    private val limiterRelease = exp(-1.0 / (0.15 * sampleRate)).toFloat()

    // Loudness matching: keeps every room about as loud as the original, so you compare
    // the room itself rather than "louder sounds better".
    private var inPower = 1e-4f
    private var outPower = 1e-4f
    private var matchGain = 1f
    private val powerCoef = (1 - exp(-1.0 / (0.8 * sampleRate))).toFloat()
    private val gainCoef = (1 - exp(-1.0 / (0.05 * sampleRate))).toFloat()

    /** New settings, or null to bypass. Safe to call from any thread. */
    fun update(s: Snapshot?) = next.set(s ?: BYPASS)

    /** Processes [frames] interleaved stereo frames in place. */
    fun process(buf: FloatArray, frames: Int) {
        val n = next.getAndSet(UNSET)
        if (n !== UNSET) configure(n as? Snapshot)
        if (!active) return

        for (i in 0 until frames) {
            var l = buf[2 * i]
            var r = buf[2 * i + 1]
            inPower += powerCoef * (0.5f * (l * l + r * r) - inPower)
            for (f in eq) {
                l = f.process(l, 0)
                r = f.process(r, 1)
            }
            l = bassShelf.process(l, 0)
            r = bassShelf.process(r, 1)

            val mid = 0.5f * (l + r)
            val side = 0.5f * (l - r) * sideGain
            l = mid + side
            r = mid - side

            reverb.process(l, r)
            l = l * dryGain + reverb.outL
            r = r * dryGain + reverb.outR

            outPower += powerCoef * (0.5f * (l * l + r * r) - outPower)
            val target = sqrt((inPower + 1e-7f) / (outPower + 1e-7f)).coerceIn(0.1f, 2f)
            matchGain += gainCoef * (target - matchGain)
            l *= matchGain
            r *= matchGain

            // Peak limiter so EQ boosts and big rooms never clip.
            val peak = max(abs(l), abs(r))
            limiterEnv = if (peak > limiterEnv) peak else limiterEnv * limiterRelease + peak * (1 - limiterRelease)
            if (limiterEnv > CEILING) {
                val g = CEILING / limiterEnv
                l *= g
                r *= g
            }
            buf[2 * i] = l.coerceIn(-1f, 1f)
            buf[2 * i + 1] = r.coerceIn(-1f, 1f)
        }
    }

    private fun configure(s: Snapshot?) {
        if (s == null) {
            active = false
            return
        }
        if (!active) {
            eq.forEach { it.reset() }
            bassShelf.reset()
            reverb.clear()
            limiterEnv = 0f
            inPower = 1e-4f
            outPower = 1e-4f
            matchGain = 1f
        }
        active = true
        val sr = sampleRate.toDouble()
        EQ_FREQS.forEachIndexed { i, hz ->
            val db = s.eqDb[i].toDouble()
            when (i) {
                // Shelves sit inside the outer points so their full gain lands on them.
                0 -> eq[i].lowShelf(sr, hz * 2.0, db)
                EQ_FREQS.lastIndex -> eq[i].highShelf(sr, hz / 2.0, db)
                else -> eq[i].peaking(sr, hz.toDouble(), 0.7, db)
            }
        }
        bassShelf.lowShelf(sr, 90.0, s.bass / 1000.0 * 10.0)
        sideGain = 1f + s.width / 1000f
        val rp = s.reverbParams()
        reverb.configure(rp)
        dryGain = if (rp == null) 1f else 1f - 0.25f * s.mix - 0.2f * s.position * s.mix
    }

    companion object {
        private val UNSET = Any()
        private val BYPASS = Any()
        private const val CEILING = 0.9f
    }
}

/** RBJ-cookbook biquad with independent state for two channels. */
class Biquad {
    private var b0 = 1f
    private var b1 = 0f
    private var b2 = 0f
    private var a1 = 0f
    private var a2 = 0f
    private val z1 = FloatArray(2)
    private val z2 = FloatArray(2)

    fun process(x: Float, ch: Int): Float {
        val y = b0 * x + z1[ch]
        z1[ch] = b1 * x - a1 * y + z2[ch]
        z2[ch] = b2 * x - a2 * y
        return y
    }

    fun reset() {
        z1.fill(0f)
        z2.fill(0f)
    }

    fun peaking(sr: Double, f: Double, q: Double, db: Double) {
        val a = 10.0.pow(db / 40)
        val w = 2 * PI * f.coerceAtMost(sr * 0.45) / sr
        val alpha = sin(w) / (2 * q)
        set(1 + alpha * a, -2 * cos(w), 1 - alpha * a, 1 + alpha / a, -2 * cos(w), 1 - alpha / a)
    }

    fun lowShelf(sr: Double, f: Double, db: Double) {
        val a = 10.0.pow(db / 40)
        val w = 2 * PI * f.coerceAtMost(sr * 0.45) / sr
        val c = cos(w)
        val k = 2 * sqrt(a) * sin(w) / 2 * sqrt(2.0)
        set(
            a * ((a + 1) - (a - 1) * c + k),
            2 * a * ((a - 1) - (a + 1) * c),
            a * ((a + 1) - (a - 1) * c - k),
            (a + 1) + (a - 1) * c + k,
            -2 * ((a - 1) + (a + 1) * c),
            (a + 1) + (a - 1) * c - k,
        )
    }

    fun highShelf(sr: Double, f: Double, db: Double) {
        val a = 10.0.pow(db / 40)
        val w = 2 * PI * f.coerceAtMost(sr * 0.45) / sr
        val c = cos(w)
        val k = 2 * sqrt(a) * sin(w) / 2 * sqrt(2.0)
        set(
            a * ((a + 1) + (a - 1) * c + k),
            -2 * a * ((a - 1) + (a + 1) * c),
            a * ((a + 1) + (a - 1) * c - k),
            (a + 1) - (a - 1) * c + k,
            2 * ((a - 1) - (a + 1) * c),
            (a + 1) - (a - 1) * c - k,
        )
    }

    private fun set(nb0: Double, nb1: Double, nb2: Double, a0: Double, na1: Double, na2: Double) {
        b0 = (nb0 / a0).toFloat()
        b1 = (nb1 / a0).toFloat()
        b2 = (nb2 / a0).toFloat()
        a1 = (na1 / a0).toFloat()
        a2 = (na2 / a0).toFloat()
    }
}

/**
 * Freeverb-style late reverb (8 damped combs + 4 allpasses per side) with a pre-delay
 * and a multi-tap early-reflection stage, driven by I3DL2 parameters.
 */
class ReverbEngine(private val sr: Int) {
    private val scale = sr / 44100f
    private val combsL = COMBS.map { Comb((it * MAX_SIZE * scale).toInt() + 1) }
    private val combsR = COMBS.map { Comb(((it + SPREAD) * MAX_SIZE * scale).toInt() + 1) }
    private val allL = ALLPASSES.map { Allpass((it * scale).toInt()) }
    private val allR = ALLPASSES.map { Allpass(((it + SPREAD) * scale).toInt()) }
    private val pre = FloatArray((sr * 0.6).toInt())
    private var w = 0

    private var on = false
    private var lpCoef = 1f
    private var lpState = 0f
    // Keeps bass out of the tail: low end piling up in the combs turns rooms to mud.
    private val hpCoef = (1 - exp(-2 * PI * 150.0 / sr)).toFloat()
    private var hpLow = 0f
    private var preDelay = 0
    private var lateGain = 0f
    private val tapDelay = IntArray(TAPS.size)
    private val tapGainL = FloatArray(TAPS.size)
    private val tapGainR = FloatArray(TAPS.size)

    var outL = 0f
        private set
    var outR = 0f
        private set

    fun configure(p: ReverbParams?) {
        if (p == null) {
            on = false
            outL = 0f
            outR = 0f
            return
        }
        if (!on) clear()
        on = true

        val decay = p.decayTime / 1000f
        // Bigger rooms (longer decays) get longer, sparser comb delays.
        val size = (0.5f + decay / 5f).coerceIn(0.5f, MAX_SIZE)
        val ratio = p.decayHfRatio / 1000f
        val damp = (0.75f - 0.55f * ratio).coerceIn(0f, 0.8f)
        for ((i, c) in (combsL + combsR).withIndex()) {
            val base = if (i < COMBS.size) COMBS[i] else COMBS[i - COMBS.size] + SPREAD
            c.setLength((base * size * scale).toInt().coerceIn(1, c.capacity))
            c.feedback = 10f.pow(-3f * c.length / (sr * decay)).coerceAtMost(0.985f)
            c.damp = damp
        }
        val diffusion = 0.3f + 0.3f * p.diffusion / 1000f
        (allL + allR).forEach { it.feedback = diffusion }

        // Room HF level -> how muffled the sound reaching the walls is.
        val hfDb = p.roomHfLevel / 100f
        val cutoff = (16000f * 10f.pow(hfDb / 40f)).coerceIn(300f, 16000f)
        lpCoef = (1 - exp(-2 * PI * cutoff / sr)).toFloat()

        val room = mb(p.roomLevel)
        lateGain = room * mb(p.reverbLevel).coerceAtMost(2f) * 0.5f / sqrt(COMBS.size.toFloat())
        val early = room * mb(p.reflectionsLevel) * 0.6f
        preDelay = ((p.reflectionsDelay + p.reverbDelay) * sr / 1000).coerceIn(1, pre.size - 1)
        val refl = max(p.reflectionsDelay, 3)
        TAPS.forEachIndexed { i, k ->
            tapDelay[i] = ((refl * k) * sr / 1000).toInt().coerceIn(1, pre.size - 1)
            val g = early * (1f - i * 0.12f)
            // Alternate reflections between sides for a sense of space.
            tapGainL[i] = if (i % 2 == 0) g else g * 0.4f
            tapGainR[i] = if (i % 2 == 0) g * 0.4f else g
        }
    }

    fun clear() {
        (combsL + combsR).forEach { it.clear() }
        (allL + allR).forEach { it.clear() }
        pre.fill(0f)
        lpState = 0f
        hpLow = 0f
    }

    fun process(l: Float, r: Float) {
        if (!on) return
        val mono = 0.5f * (l + r)
        hpLow += hpCoef * (mono - hpLow)
        lpState += lpCoef * ((mono - hpLow) - lpState)
        pre[w] = lpState

        var el = 0f
        var er = 0f
        for (i in TAPS.indices) {
            val s = pre[idx(tapDelay[i])]
            el += s * tapGainL[i]
            er += s * tapGainR[i]
        }

        val x = pre[idx(preDelay)]
        var ll = 0f
        var rr = 0f
        for (c in combsL) ll += c.process(x * c.norm)
        for (c in combsR) rr += c.process(x * c.norm)
        for (a in allL) ll = a.process(ll)
        for (a in allR) rr = a.process(rr)

        outL = el + ll * lateGain
        outR = er + rr * lateGain
        if (++w >= pre.size) w = 0
    }

    private fun idx(delay: Int): Int {
        val i = w - delay
        return if (i < 0) i + pre.size else i
    }

    private fun mb(millibels: Int) = 10f.pow(millibels / 2000f)

    private class Comb(val capacity: Int) {
        private val buf = FloatArray(capacity)
        var length = capacity
            private set
        private var i = 0
        private var store = 0f
        var damp = 0.2f
        var feedback = 0.8f
            set(v) {
                field = v
                // Normalise so every comb has roughly unit power gain whatever its decay.
                norm = sqrt(1f - v * v)
            }
        var norm = 0.6f
            private set

        fun setLength(n: Int) {
            length = n
            if (i >= n) i = 0
        }

        fun process(x: Float): Float {
            val out = buf[i]
            store = out * (1 - damp) + store * damp
            buf[i] = x + store * feedback
            if (++i >= length) i = 0
            return out
        }

        fun clear() {
            buf.fill(0f)
            store = 0f
        }
    }

    private class Allpass(len: Int) {
        private val buf = FloatArray(max(len, 1))
        private var i = 0
        var feedback = 0.5f

        fun process(x: Float): Float {
            val b = buf[i]
            buf[i] = x + b * feedback
            if (++i >= buf.size) i = 0
            return b - x
        }

        fun clear() = buf.fill(0f)
    }

    companion object {
        private const val MAX_SIZE = 1.6f
        private const val SPREAD = 23
        private val COMBS = intArrayOf(1116, 1188, 1277, 1356, 1422, 1491, 1557, 1617)
        private val ALLPASSES = intArrayOf(556, 441, 341, 225)
        private val TAPS = floatArrayOf(1f, 1.37f, 1.81f, 2.26f, 2.93f, 3.5f)
    }
}
