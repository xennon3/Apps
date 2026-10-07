package com.xennon.venue

/**
 * I3DL2-style reverb description, in the units [android.media.audiofx.EnvironmentalReverb]
 * expects: levels in millibels, times in milliseconds, ratios/diffusion/density in per-mille.
 */
data class Reverb(
    val roomHfLevel: Int,
    val decayTime: Int,
    val decayHfRatio: Int,
    val reflectionsLevel: Int,
    val reflectionsDelay: Int,
    val reverbLevel: Int,
    val reverbDelay: Int,
    val diffusion: Int = 1000,
    val density: Int = 1000,
)

/**
 * A place your music can be "moved" into: a reverb character plus the tonal colour
 * (EQ, bass, stereo width) that space tends to have.
 */
class Room(
    val id: String,
    val emoji: String,
    val name: String,
    val tagline: String,
    val reverb: Reverb?,
    /** Default wet amount, 0..1. */
    val mix: Float,
    /** Gains in dB at [EQ_FREQS]. */
    val eqDb: FloatArray,
    /** BassBoost strength, 0..1000. */
    val bass: Int,
    /** Virtualizer strength, 0..1000. */
    val width: Int,
)

/** Device-independent EQ points; mapped onto whatever bands the phone's Equalizer exposes. */
val EQ_FREQS = intArrayOf(60, 230, 910, 3600, 14000)

val ROOMS = listOf(
    Room(
        "dry", "🎧", "Studio Dry", "No room at all, just the mix",
        reverb = null, mix = 0f,
        eqDb = floatArrayOf(0f, 0f, 0f, 0f, 0f), bass = 0, width = 0,
    ),
    Room(
        "bedroom", "🛏️", "Bedroom", "Small, soft and close",
        Reverb(-1200, 450, 500, -800, 3, -400, 5, 900, 900), mix = 0.55f,
        eqDb = floatArrayOf(1f, 0.5f, 0f, 0f, -1f), bass = 150, width = 200,
    ),
    Room(
        "bathroom", "🚿", "Bathroom", "Bright tiles, shower-singer echo",
        Reverb(-300, 1500, 900, -370, 7, 600, 11, 1000, 600), mix = 0.6f,
        eqDb = floatArrayOf(0f, -1f, 1f, 2f, 2f), bass = 0, width = 200,
    ),
    Room(
        "club", "🪩", "Club", "Subs in your chest, a sweaty packed room",
        Reverb(-1500, 1100, 500, -1000, 10, -500, 15, 700, 900), mix = 0.5f,
        eqDb = floatArrayOf(6f, 2f, -2f, 2f, 3f), bass = 700, width = 600,
    ),
    Room(
        "nextdoor", "🚪", "Next Door", "The party is in the other room",
        Reverb(-6000, 700, 200, -1500, 5, 0, 10), mix = 0.7f,
        eqDb = floatArrayOf(4f, 1f, -9f, -12f, -12f), bass = 500, width = 0,
    ),
    Room(
        "hall", "🎻", "Concert Hall", "Velvet seats and a warm 2-second tail",
        Reverb(-500, 2400, 700, -1230, 20, -200, 29), mix = 0.55f,
        eqDb = floatArrayOf(1f, 1f, 0f, 0f, 1f), bass = 100, width = 500,
    ),
    Room(
        "stadium", "🏟️", "Stadium", "Huge, distant, 50,000 people",
        Reverb(-700, 5000, 330, -1600, 120, 0, 90), mix = 0.6f,
        eqDb = floatArrayOf(3f, 1f, -1f, 1f, 0f), bass = 400, width = 1000,
    ),
    Room(
        "cathedral", "⛪", "Cathedral", "Stone, stained glass, endless tail",
        Reverb(-800, 7000, 600, -1500, 40, 300, 60), mix = 0.6f,
        eqDb = floatArrayOf(0f, 1f, 0f, -1f, -2f), bass = 0, width = 700,
    ),
    Room(
        "garage", "🅿️", "Parking Garage", "Concrete slapback and echo",
        Reverb(0, 1650, 1500, -400, 8, -300, 12, 600, 400), mix = 0.55f,
        eqDb = floatArrayOf(-1f, 0f, 1f, 2f, 1f), bass = 0, width = 400,
    ),
    Room(
        "cave", "🕳️", "Cave", "Dark, dripping and deep",
        Reverb(-500, 3500, 800, -600, 15, 200, 22), mix = 0.6f,
        eqDb = floatArrayOf(2f, 1f, 0f, -2f, -4f), bass = 200, width = 500,
    ),
    Room(
        "underwater", "🌊", "Underwater", "Pool party, heard from below",
        Reverb(-4000, 1500, 100, -449, 7, 1700, 11), mix = 0.55f,
        eqDb = floatArrayOf(5f, 2f, -8f, -12f, -12f), bass = 600, width = 300,
    ),
)

fun roomById(id: String?): Room = ROOMS.firstOrNull { it.id == id } ?: ROOMS.first()
