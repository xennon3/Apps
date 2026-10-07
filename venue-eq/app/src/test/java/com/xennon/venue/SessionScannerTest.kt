package com.xennon.venue

import org.junit.Assert.assertEquals
import org.junit.Test

class SessionScannerTest {
    @Test fun findsLivePlayersAndSkipsHistoryAndOwnApp() {
        val dump = """
            PlaybackActivityMonitor dump time: 3:28:01 PM
              players:
              AudioPlaybackConfiguration piid:79 deviceId:2 type:android.media.AudioTrack u/pid:10145/5321 state:started attr:AudioAttributes: usage=USAGE_MEDIA content=CONTENT_TYPE_MOVIE flags=0x800 tags= bundle=null sessionId:1937 mutedState: none
              AudioPlaybackConfiguration piid:80 deviceId:2 type:android.media.AudioTrack u/pid:10200/6000 state:paused attr:AudioAttributes: usage=USAGE_MEDIA sessionId:2001
              AudioPlaybackConfiguration piid:81 deviceId:2 type:android.media.AudioTrack u/pid:10999/7000 state:started attr:AudioAttributes: usage=USAGE_MEDIA sessionId:2500
              AudioPlaybackConfiguration piid:82 deviceId:0 type:android.media.SoundPool u/pid:1000/900 state:idle attr:AudioAttributes: usage=USAGE_ASSISTANCE_SONIFICATION sessionId:0
              playback event log:
              10-07 15:20:00.000 new player piid:12 u/pid:10145/5321 state:started sessionId:1500
        """.trimIndent()
        val players = SessionScanner.parse(dump, ownUid = 10999)
        assertEquals(listOf(1937, 2001), players.map { it.session })
        assertEquals(10145, players[0].uid)
        assertEquals("paused", players[1].state)
    }

    @Test fun fallsBackToOlderFormat() {
        val dump = "  ID:15 -- type:android.media.AudioTrack -- u/pid:10145/5321 -- state:started -- session:42"
        assertEquals(listOf(42), SessionScanner.parse(dump, 1).map { it.session })
    }
}
