package com.xennon.venue

import android.media.audiofx.AudioEffect
import android.media.audiofx.BassBoost
import android.media.audiofx.EnvironmentalReverb
import android.media.audiofx.Equalizer
import android.media.audiofx.Virtualizer
import android.util.Log
import kotlin.math.log10
import kotlin.math.roundToInt

/**
 * The stack of platform effects attached to one audio session:
 * Equalizer -> BassBoost -> Virtualizer -> EnvironmentalReverb.
 *
 * Every effect is optional: phones are free to not implement any of them, and
 * individual reverb parameters are sometimes unsupported, so all calls are guarded.
 */
class EffectChain(val sessionId: Int) {
    private val eq = create("Equalizer") { Equalizer(PRIORITY, sessionId) }
    private val bassBoost = create("BassBoost") { BassBoost(PRIORITY, sessionId) }
    private val virtualizer = create("Virtualizer") { Virtualizer(PRIORITY, sessionId) }

    // On the global output mix (session 0) reverb is an auxiliary effect that players
    // must opt into, so it only works when attached to a real app session.
    private val reverb = if (sessionId != 0) {
        create("EnvironmentalReverb") { EnvironmentalReverb(PRIORITY, sessionId) }
    } else null

    val hasReverb get() = reverb != null

    fun apply(s: Snapshot) {
        eq?.let { e ->
            val (min, max) = e.bandLevelRange.let { it[0].toInt() to it[1].toInt() }
            for (b in 0 until e.numberOfBands) {
                val hz = e.getCenterFreq(b.toShort()) / 1000f
                val mb = (eqGainAt(s.eqDb, hz) * 100).roundToInt().coerceIn(min, max)
                guard { e.setBandLevel(b.toShort(), mb.toShort()) }
            }
            guard { e.enabled = true }
        }
        bassBoost?.let { b ->
            guard { if (b.strengthSupported) b.setStrength(s.bass.toShort()) }
            guard { b.enabled = s.bass > 0 }
        }
        virtualizer?.let { v ->
            guard { if (v.strengthSupported) v.setStrength(s.width.toShort()) }
            guard { v.enabled = s.width > 0 }
        }
        reverb?.let { r ->
            val room = s.room.reverb
            if (room == null || s.mix < 0.01f) {
                guard { r.enabled = false }
                return@let
            }
            val p = s.position
            // Mix maps to the wet level on a dB scale: 1.0 -> 0 mB, 0.1 -> -20 dB.
            val wet = (2000 * log10(s.mix)).roundToInt()
            guard { r.roomLevel = wet.clamp(-9000, 0).toShort() }
            guard { r.roomHFLevel = (room.roomHfLevel - 1200 * p).roundToInt().clamp(-9000, 0).toShort() }
            guard { r.decayTime = room.decayTime.clamp(100, 20000) }
            guard { r.decayHFRatio = room.decayHfRatio.clamp(100, 2000).toShort() }
            // Front row: loud early reflections, little tail. Back wall: the opposite.
            guard { r.reflectionsLevel = (room.reflectionsLevel - 600 * p).roundToInt().clamp(-9000, 1000).toShort() }
            guard { r.reflectionsDelay = (room.reflectionsDelay + 40 * p).roundToInt().clamp(0, 300) }
            guard { r.reverbLevel = (room.reverbLevel + 1200 * (p - 0.5f)).roundToInt().clamp(-9000, 2000).toShort() }
            guard { r.reverbDelay = (room.reverbDelay + 30 * p).roundToInt().clamp(0, 100) }
            guard { r.diffusion = room.diffusion.clamp(0, 1000).toShort() }
            guard { r.density = room.density.clamp(0, 1000).toShort() }
            guard { r.enabled = true }
        }
    }

    fun disable() {
        effects().forEach { guard { it.enabled = false } }
    }

    fun release() {
        effects().forEach { guard { it.release() } }
    }

    private fun effects(): List<AudioEffect> = listOfNotNull(eq, bassBoost, virtualizer, reverb)

    private fun <T> create(name: String, block: () -> T): T? = try {
        block()
    } catch (t: Throwable) {
        Log.w(TAG, "$name unavailable on session $sessionId: $t")
        null
    }

    private inline fun guard(block: () -> Unit) {
        try {
            block()
        } catch (t: Throwable) {
            Log.w(TAG, "effect call failed on session $sessionId: $t")
        }
    }

    private fun Int.clamp(lo: Int, hi: Int) = coerceIn(lo, hi)

    companion object {
        private const val TAG = "VenueEffects"
        /** High priority so we win control of the session from other EQ apps. */
        private const val PRIORITY = 1000
    }
}
