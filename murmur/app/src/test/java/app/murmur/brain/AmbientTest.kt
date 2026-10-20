package app.murmur.brain

import app.murmur.brain.AmbientMind.Act
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar

class AmbientTest {

    @Test fun readsTheModelsLines() {
        val t = AmbientMind.parse(
            "OBSERVE: Battery 18% at work.\nTHOUGHT: It won't last.\nPLAN: 5:30 PM | check again\nPLAN: N/A | Maintain current state\n" +
                "DO: notify | Charge before you leave; journal | Battery low at work\nDO: remind | 1 | plug in now\nDO: ask_send | Mom | running late",
        )
        assertEquals("Battery 18% at work.", t.observe)
        assertEquals(listOf("5:30 PM" to "check again"), t.plans)
        assertEquals(Act.Notify("Charge before you leave"), t.acts[0])
        assertEquals(Act.Journal("Battery low at work"), t.acts[1])
        assertEquals(Act.Remind("1", "plug in now"), t.acts[2])
        assertEquals(3, t.acts.size) // capped; the text to Mom would need a later thought
        val ask = AmbientMind.parse("DO: ask_send | Mom | running late, sorry")
        assertEquals(listOf(Act.AskSend("Mom", "running late, sorry")), ask.acts)
        assertTrue(AmbientMind.parse("DO: nothing").acts.isEmpty())
    }

    @Test fun codeVetoesAndKeepsTheClock() {
        val t = AmbientMind.parse("DO: flashlight | off; ringer | silent")
        assertEquals(listOf(Act.Ringer("silent")), AmbientMind.allowed(t, "Battery 70%, at home").acts)
        assertEquals(2, AmbientMind.allowed(t, "Light: dark").acts.size)
        assertEquals(21 to 0, AmbientMind.goalTime("Remind me to take my pills at 9 PM every day"))
        assertEquals(7 to 30, AmbientMind.goalTime("wake me at 7:30"))
        assertNull(AmbientMind.goalTime("Keep my nights quiet"))
        // The 1:06 AM bug: the model's own "wake up" reminder repeats a goal the clock keeps, so code drops it.
        val goals = listOf("Wake me up at 8:00 AM")
        val dup = AmbientMind.parse("DO: notify | Time to wake up!; remind | 8:00 AM | Wake up, it's 8; journal | quiet night")
        assertEquals(listOf(Act.Journal("quiet night")), AmbientMind.allowed(dup, "", goals).acts)
        assertTrue(AmbientMind.isAlarmGoal("wake me up at 8:00 AM"))
        assertTrue(AmbientMind.isAlarmGoal("set an alarm for 6:45 every weekday"))
        assertTrue(!AmbientMind.isAlarmGoal("Remind me to take my pills at 9 PM"))
        assertTrue(AmbientMind.situation(AmbientMind.Situation("Tue 1:06 AM", emptyList(), emptyList(), goals, emptyList(), emptyList()))
            .contains("Murmur's clock handles this at 8:00 AM"))
    }

    @Test fun codeWorksOutReminderTimes() {
        val now = Calendar.getInstance().apply { set(2026, Calendar.OCTOBER, 20, 1, 6, 0) }
        fun at(s: String) = AmbientMind.remindAt(s, now)?.let { Calendar.getInstance().apply { timeInMillis = it } }
        assertEquals(8, at("8:00 AM")!!.get(Calendar.HOUR_OF_DAY))
        assertEquals(20, at("8:00 AM")!!.get(Calendar.DAY_OF_MONTH)) // later today, not tomorrow
        assertEquals(19, at("7:30 PM")!!.get(Calendar.HOUR_OF_DAY))
        assertEquals(21, at("1:00 AM")!!.get(Calendar.DAY_OF_MONTH)) // already past today: tomorrow
        assertEquals(now.timeInMillis + 20 * 60_000L, AmbientMind.remindAt("in 20 minutes", now))
        assertEquals(now.timeInMillis + 2 * 3_600_000L, AmbientMind.remindAt("in 2 hours", now))
        assertNull(AmbientMind.remindAt("later", now))
    }

    @Test fun learnsPlacesFromStays() {
        val p = Places()
        val min = 60_000L
        val cal = { t: Long -> Calendar.getInstance().apply { timeInMillis = t } }
        val home = 41.8800 to -87.6300
        var t = Calendar.getInstance().apply { set(2026, Calendar.OCTOBER, 19, 21, 0, 0) }.timeInMillis
        assertNull(p.fix(home.first, home.second, t, cal))
        t += 10 * min; assertNull(p.fix(home.first + 0.0005, home.second, t, cal)) // 55 m away: same spot, not yet a stay
        t += 6 * min
        val arrived = p.fix(home.first, home.second, t, cal)
        assertTrue(arrived is Places.Change.Arrived)
        // One jumpy reading 600 m away doesn't count as leaving; two do.
        t += 3 * min; assertNull(p.fix(home.first + 0.0055, home.second, t, cal))
        t += 3 * min; assertNull(p.fix(home.first, home.second, t, cal))
        t += 3 * min; assertNull(p.fix(home.first + 0.0055, home.second, t, cal))
        t += 3 * min; assertTrue(p.fix(home.first + 0.0056, home.second, t, cal) is Places.Change.Left)
        assertEquals(111.0, Places.meters(0.0, 0.0, 0.001, 0.0), 1.0)
    }

    @Test fun plansPersistAndDontRepeat() {
        val now = Calendar.getInstance().apply { set(Calendar.HOUR_OF_DAY, 2); set(Calendar.MINUTE, 7) }
        val existing = listOf("7:55 AM" to "set the alarm for 8:00 AM")
        // The same plan written again (as on the phone, every check-in) isn't added twice.
        val (kept, added) = AmbientMind.mergePlans(existing, listOf("now" to "set alarm for 8:00 AM", "8:30 AM" to "check the bank eNotice"), now)
        assertEquals(listOf("8:30 AM" to "check the bank eNotice"), added)
        assertEquals(2, kept.size)
        // Plans whose time has passed drop off.
        val later = Calendar.getInstance().apply { set(Calendar.HOUR_OF_DAY, 8); set(Calendar.MINUTE, 16) }
        assertEquals(listOf("8:30 AM" to "check the bank eNotice"), AmbientMind.mergePlans(kept, emptyList(), later).first)
    }

    @Test fun codeRemembersWhatWasAlreadyDone() {
        val acts = listOf(Act.Remind("8:00 AM", "Time to wake up, King!"), Act.Notify("Battery low, charge soon"), Act.Journal("quiet"))
        val kept = AmbientMind.notRepeated(acts, pendingReminders = listOf("Time to wake up, King!"), recentlySaid = listOf("Battery is low, charge it soon"))
        assertEquals(listOf(Act.Journal("quiet")), kept)
        // Echoing a received text back to its sender becomes a reminder to reply; a second proposal is dropped.
        val echo = AmbientMind.notRepeated(listOf(Act.AskSend("Jake", "are we still on for tonight?")), emptyList(), emptyList(), receivedTexts = listOf("are we still on for tonight?"))
        assertEquals(listOf(Act.Notify("Reply to Jake: \"are we still on for tonight?\"")), echo)
        val again = AmbientMind.notRepeated(listOf(Act.AskSend("Mom", "running 10 min late")), emptyList(), emptyList(), pendingAsks = listOf("Running 10 min late"))
        assertTrue(again.isEmpty())
    }

    @Test fun diaryBecomesATimelineWithRealTimes() {
        val start = Calendar.getInstance().apply { set(2026, Calendar.OCTOBER, 21, 9, 40, 0) }.timeInMillis
        val rows = ArrayList<SenseLog.Sample>()
        var lat = 41.88
        for (i in 0 until 12 * 60) { // an hour of readings every 5 seconds
            val t = start + i * 5_000L
            val minute = i / 12
            val motion = when {
                minute in 4 until 12 -> "vehicle"
                minute == 30 && i % 12 < 4 -> "walking" // 20 seconds of fidgeting: folded away
                else -> "still"
            }
            if (motion == "vehicle") lat += 0.00009 // about 10 m every 5 s
            rows += SenseLog.Sample(t, motion, if (motion == "vehicle") 10f else 0f, lat, -87.63, screen = minute in 20..21, charging = minute < 2, level = 84)
        }
        val now = start + 60 * 60_000L
        val lines = SenseLog.condense(rows, now)
        assertEquals("9:40 AM-9:44 AM still, 4 min", lines[0])
        assertTrue(lines[1], lines[1].startsWith("9:44 AM-9:52 AM driving or riding, 8 min, about 0.6 miles"))
        assertTrue(lines[2], lines[2].startsWith("9:52 AM-now still, 48 min"))
        assertTrue(lines.any { it == "9:42 AM unplugged at 84%" })
        assertTrue(lines.any { it == "Screen turned on 1 time" })
        // Nothing new in the last quiet stretch: no reason to wake the model.
        assertTrue(!SenseLog.changed(rows.filter { it.at > start + 40 * 60_000L }))
        assertTrue(SenseLog.changed(rows))
    }

    @Test fun namingAPlaceWorksAtOnce() {
        val p = Places()
        val placed = p.nameHere(41.88, -87.63, 0L, "work")
        assertEquals("work", p.label(placed))
        assertEquals(placed, p.current)
        // Naming somewhere else "work" moves the name.
        val other = p.nameHere(41.95, -87.70, 1L, "work")
        assertEquals("work", other.name)
        assertNull(placed.name)
    }
}
