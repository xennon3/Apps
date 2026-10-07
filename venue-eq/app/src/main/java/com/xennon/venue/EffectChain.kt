package com.xennon.venue

import android.media.audiofx.AudioEffect
import android.media.audiofx.BassBoost
import android.media.audiofx.EnvironmentalReverb
import android.media.audiofx.Equalizer
import android.media.audiofx.Virtualizer
import android.util.Log
import kotlin.math.roundToInt

/**
 * The stack of platform effects attached to one audio session:
 * Equalizer -> BassBoost -> Virtualizer -> EnvironmentalReverb.
 *
 * Every effect is optional: phones are free to not implement any of them, and
 * individual reverb parameters are sometimes unsupported, so all calls are guarded.
 */
class EffectChain(val sessionId: Int, private val onControlChange: () -> Unit = {}) {
    private val eq = create("Equalizer") { Equalizer(PRIORITY, sessionId) }
    private val bassBoost = create("BassBoost") { BassBoost(PRIORITY, sessionId) }
    private val virtualizer = create("Virtualizer") { Virtualizer(PRIORITY, sessionId) }

    // On the global output mix (session 0) reverb is an auxiliary effect that players
    // must opt into, so it only works when attached to a real app session.
    private val reverb = if (sessionId != 0) {
        create("EnvironmentalReverb") { EnvironmentalReverb(PRIORITY, sessionId) }
    } else null

    init {
        // Dolby / Moto Audio and other EQ apps can grab a session from us at any time.
        effects().forEach { e ->
            guard { e.setControlStatusListener { _, _ -> onControlChange() } }
        }
    }

    fun apply(s: Snapshot) {
        // Even the read-only calls throw once another app (e.g. Dolby) owns the effect.
        eq?.let { e ->
            guard {
                val (min, max) = e.bandLevelRange.let { it[0].toInt() to it[1].toInt() }
                for (b in 0 until e.numberOfBands) {
                    val hz = e.getCenterFreq(b.toShort()) / 1000f
                    val mb = (eqGainAt(s.eqDb, hz) * 100).roundToInt().coerceIn(min, max)
                    guard { e.setBandLevel(b.toShort(), mb.toShort()) }
                }
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
            val p = s.reverbParams()
            if (p == null) {
                guard { r.enabled = false }
                return@let
            }
            guard { r.roomLevel = p.roomLevel.toShort() }
            guard { r.roomHFLevel = p.roomHfLevel.toShort() }
            guard { r.decayTime = p.decayTime }
            guard { r.decayHFRatio = p.decayHfRatio.toShort() }
            guard { r.reflectionsLevel = p.reflectionsLevel.toShort() }
            guard { r.reflectionsDelay = p.reflectionsDelay }
            guard { r.reverbLevel = p.reverbLevel.toShort() }
            guard { r.reverbDelay = p.reverbDelay }
            guard { r.diffusion = p.diffusion.toShort() }
            guard { r.density = p.density.toShort() }
            guard { r.enabled = true }
        }
    }

    /**
     * One-line health check, e.g. "EQ ✓ · Bass ✓ · Width ⚠ · Room ✗".
     * ✓ working, ⚠ another app (e.g. Dolby) has taken control, ✗ not available on this phone.
     */
    fun report(): String = listOf(
        "EQ" to eq, "Bass" to bassBoost, "Width" to virtualizer, "Room" to reverb,
    ).filter { (name, _) -> !(name == "Room" && sessionId == 0) }
        .joinToString(" · ") { (name, e) ->
            val mark = when {
                e == null -> "✗"
                !(runCatching { e.hasControl() }.getOrDefault(false)) -> "⚠"
                else -> "✓"
            }
            "$name $mark"
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

    companion object {
        private const val TAG = "VenueEffects"
        /** High priority so we win control of the session from other EQ apps. */
        private const val PRIORITY = 1000
    }
}
