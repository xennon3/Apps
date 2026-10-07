package com.xennon.venue

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RoomsTest {
    @Test fun eqInterpolatesOnLogScale() {
        val eq = floatArrayOf(6f, 0f, 0f, 0f, -12f)
        assertEquals(6f, eqGainAt(eq, 30f), 0.001f)
        assertEquals(6f, eqGainAt(eq, 60f), 0.001f)
        assertEquals(0f, eqGainAt(eq, 910f), 0.001f)
        assertEquals(-12f, eqGainAt(eq, 20000f), 0.001f)
        // Geometric midpoint of 3.6k and 14k sits halfway in dB.
        assertEquals(-6f, eqGainAt(eq, kotlin.math.sqrt(3600f * 14000f)), 0.01f)
    }

    @Test fun roomsStayInPlatformRanges() {
        assertEquals(ROOMS.size, ROOMS.map { it.id }.toSet().size)
        for (r in ROOMS) {
            assertEquals(r.name, EQ_FREQS.size, r.eqDb.size)
            assertTrue(r.name, r.eqDb.all { it in -12f..12f })
            assertTrue(r.name, r.bass in 0..1000 && r.width in 0..1000 && r.mix in 0f..1f)
            val v = r.reverb ?: continue
            assertTrue(r.name, v.roomHfLevel in -9000..0)
            assertTrue(r.name, v.decayTime in 100..20000)
            assertTrue(r.name, v.decayHfRatio in 100..2000)
            assertTrue(r.name, v.reflectionsLevel in -9000..1000)
            assertTrue(r.name, v.reflectionsDelay in 0..300)
            assertTrue(r.name, v.reverbLevel in -9000..2000)
            assertTrue(r.name, v.reverbDelay in 0..100)
        }
    }

    @Test fun unknownRoomFallsBackToFirst() {
        assertEquals("dry", roomById("nope").id)
        assertEquals("club", roomById("club").id)
    }
}
