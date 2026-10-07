package com.xennon.venue

import android.content.Context
import android.content.SharedPreferences
import kotlin.math.ln
import kotlin.math.log10
import kotlin.math.roundToInt

/** Everything the effect chain needs to configure itself, already resolved. */
class Snapshot(
    val room: Room,
    val mix: Float,
    val position: Float,
    val eqDb: FloatArray,
    val bass: Int,
    val width: Int,
)

/** Final reverb values for a [Snapshot], in [android.media.audiofx.EnvironmentalReverb] units. */
class ReverbParams(
    val roomLevel: Int,
    val roomHfLevel: Int,
    val decayTime: Int,
    val decayHfRatio: Int,
    val reflectionsLevel: Int,
    val reflectionsDelay: Int,
    val reverbLevel: Int,
    val reverbDelay: Int,
    val diffusion: Int,
    val density: Int,
)

/**
 * Resolves the room plus mix and position into concrete reverb values, or null when
 * there should be no reverb. Shared by the platform effects and the built-in engine.
 */
fun Snapshot.reverbParams(): ReverbParams? {
    val r = room.reverb ?: return null
    if (mix < 0.01f) return null
    val p = position
    return ReverbParams(
        // Mix maps to the wet level on a dB scale: 1.0 -> 0 mB, 0.1 -> -20 dB.
        roomLevel = (2000 * log10(mix)).roundToInt().coerceIn(-9000, 0),
        roomHfLevel = (r.roomHfLevel - 1200 * p).roundToInt().coerceIn(-9000, 0),
        decayTime = r.decayTime.coerceIn(100, 20000),
        decayHfRatio = r.decayHfRatio.coerceIn(100, 2000),
        // Front row: loud early reflections, little tail. Back wall: the opposite.
        reflectionsLevel = (r.reflectionsLevel - 600 * p).roundToInt().coerceIn(-9000, 1000),
        reflectionsDelay = (r.reflectionsDelay + 40 * p).roundToInt().coerceIn(0, 300),
        reverbLevel = (r.reverbLevel + 1200 * (p - 0.5f)).roundToInt().coerceIn(-9000, 2000),
        reverbDelay = (r.reverbDelay + 30 * p).roundToInt().coerceIn(0, 100),
        diffusion = r.diffusion.coerceIn(0, 1000),
        density = r.density.coerceIn(0, 1000),
    )
}

/** User settings, persisted in SharedPreferences and shared by the UI and the service. */
class SoundSettings(context: Context) {
    val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences("venue", Context.MODE_PRIVATE)

    var enabled: Boolean
        get() = prefs.getBoolean("enabled", false)
        set(v) = prefs.edit().putBoolean("enabled", v).apply()

    var globalMode: Boolean
        get() = prefs.getBoolean("global", false)
        set(v) = prefs.edit().putBoolean("global", v).apply()

    val room: Room get() = roomById(prefs.getString("room", "club"))

    var mix: Float
        get() = prefs.getFloat("mix", room.mix)
        set(v) = prefs.edit().putFloat("mix", v).apply()

    /** 0 = front row, 1 = back wall. */
    var position: Float
        get() = prefs.getFloat("position", 0.4f)
        set(v) = prefs.edit().putFloat("position", v).apply()

    var bass: Int
        get() = prefs.getInt("bass", room.bass)
        set(v) = prefs.edit().putInt("bass", v).apply()

    var width: Int
        get() = prefs.getInt("width", room.width)
        set(v) = prefs.edit().putInt("width", v).apply()

    fun eq(band: Int): Float = prefs.getFloat("eq$band", room.eqDb[band])
    fun setEq(band: Int, db: Float) = prefs.edit().putFloat("eq$band", db).apply()

    /** Switch rooms: loads that room's character, but keeps where you're standing. */
    fun selectRoom(r: Room) {
        prefs.edit().apply {
            putString("room", r.id)
            putFloat("mix", r.mix)
            putInt("bass", r.bass)
            putInt("width", r.width)
            r.eqDb.forEachIndexed { i, db -> putFloat("eq$i", db) }
        }.apply()
    }

    fun snapshot(): Snapshot {
        val p = position
        // Standing further back, the air and the crowd soak up the highs.
        val eq = FloatArray(EQ_FREQS.size) { eq(it) }
        eq[3] -= 2f * p
        eq[4] -= 4f * p
        return Snapshot(room, mix, p, eq, bass, width)
    }
}

/** Gain in dB at [hz], interpolated on a log-frequency axis between the EQ points. */
fun eqGainAt(eqDb: FloatArray, hz: Float): Float {
    if (hz <= EQ_FREQS.first()) return eqDb.first()
    if (hz >= EQ_FREQS.last()) return eqDb.last()
    for (i in 0 until EQ_FREQS.size - 1) {
        val lo = EQ_FREQS[i].toFloat()
        val hi = EQ_FREQS[i + 1].toFloat()
        if (hz <= hi) {
            val t = (ln(hz) - ln(lo)) / (ln(hi) - ln(lo))
            return eqDb[i] + (eqDb[i + 1] - eqDb[i]) * t
        }
    }
    return eqDb.last()
}
