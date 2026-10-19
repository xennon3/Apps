package app.murmur.brain

import app.murmur.brain.tools.PhoneAction
import app.murmur.brain.tools.PhoneActionTool
import app.murmur.brain.tools.PhoneMath
import app.murmur.brain.tools.PhoneMath.Sample
import app.murmur.brain.tools.PhoneStatusTool
import app.murmur.brain.tools.PhoneTopic
import com.google.gson.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar

class PhoneTest {
    private val h = 3_600_000L

    @Test fun topicsAndActionsAreReadLeniently() {
        assertEquals(PhoneTopic.BATTERY, PhoneTopic.from("battery level"))
        assertEquals(PhoneTopic.SPEED, PhoneTopic.from("how fast"))
        assertEquals(PhoneTopic.SCREEN_TIME, PhoneTopic.from("screen_time"))
        assertEquals(PhoneTopic.SURROUNDINGS, PhoneTopic.from("compass"))
        assertNull(PhoneTopic.from("banana"))
        assertEquals(PhoneAction.FLASHLIGHT_OFF, PhoneAction.from("turn off flashlight"))
        assertEquals(PhoneAction.FLASHLIGHT_ON, PhoneAction.from("torch"))
        assertEquals(PhoneAction.TIMER, PhoneAction.from("set_timer"))
        assertEquals(PhoneAction.SOUND_ON, PhoneAction.from("unmute"))
        assertEquals(PhoneAction.SILENT, PhoneAction.from("mute"))
    }

    @Test fun toolsPassTheChoiceToAndroid() {
        val status = PhoneStatusTool { t -> "read ${t.key}" }
        assertEquals("read steps", status.run(JsonObject().apply { addProperty("what", "steps") }).text)
        assertTrue(status.run(JsonObject().apply { addProperty("what", "vibes") }).text.startsWith("Unknown phone reading"))
        val action = PhoneActionTool { a, v -> "${a.key}:$v" }
        assertEquals("timer:10 minutes", action.run(JsonObject().apply { addProperty("action", "timer"); addProperty("value", "10 minutes") }).text)
    }

    @Test fun drainIsMeasuredFromTheLastUnpluggedStretch() {
        val now = 100 * h
        val samples = listOf(
            Sample(now - 5 * h, 60, true), // charging, ignored
            Sample(now - 3 * h, 100, false), Sample(now - 2 * h, 92, false), Sample(now - h, 84, false), Sample(now, 76, false),
        )
        assertEquals(8.0, PhoneMath.drainPerHour(samples, now)!!, 0.01)
        assertEquals(9.5, PhoneMath.hoursLeft(76, 8.0)!!, 0.01)
        assertNull(PhoneMath.drainPerHour(listOf(Sample(now - 10 * 60_000L, 80, false), Sample(now, 80, false)), now)) // too short to tell
    }

    @Test fun typicalDrainAndUsualChargeTimeComeFromTheWeek() {
        val day = 24 * h
        val now = 10 * day
        val samples = ArrayList<Sample>()
        for (d in 3..9) {
            val start = d * day + 8 * h // unplugged at 8 AM, drains 5%/h, plugged in at 10 PM
            for (k in 0..14) samples += Sample(start + k * h, 100 - 5 * k, false)
            samples += Sample(start + 14 * h + 30 * 60_000L, 30, true)
        }
        assertEquals(5.0, PhoneMath.typicalDrain(samples, now)!!, 0.01)
        val hourOf = { t: Long -> ((t % day) / h).toInt() }
        assertEquals(22, PhoneMath.usualChargeHour(samples, now, hourOf))
    }

    @Test fun formatsReadings() {
        assertEquals("34 mph (vehicle speed)", PhoneMath.describeSpeed(15.2f, metric = false))
        assertEquals("4.3 km/h (about walking pace)", PhoneMath.describeSpeed(1.2f, metric = true))
        assertEquals("north", PhoneMath.compass(355f))
        assertEquals("southwest", PhoneMath.compass(-135f))
        assertEquals(111f, PhoneMath.altitude(1000f), 2f)
        assertEquals("2 h 30 min", PhoneMath.duration(2.5))
        assertEquals("18.2 GB", PhoneMath.bytes(18_200_000_000L))
    }

    @Test fun parsesClockTimesAndTimers() {
        assertEquals(6 to 45, PhoneMath.parseClock("6:45 tomorrow"))
        assertEquals(19 to 30, PhoneMath.parseClock("7.30 pm"))
        assertEquals(0 to 0, PhoneMath.parseClock("12 a.m."))
        assertEquals(600, PhoneMath.parseTimer("10"))
        assertEquals(5400, PhoneMath.parseTimer("1 hour 30 min"))
        assertEquals(90, PhoneMath.parseTimer("90 sec"))
        assertNull(PhoneMath.parseTimer("soon"))
    }

    @Test fun lessonsMatchSimilarQuestions() {
        val lessons = listOf(
            Lessons.Lesson("what should I cook for dinner tonight", "Try the lentil curry you liked."),
            Lessons.Lesson("translate thank you to spanish", "Gracias."),
        )
        assertEquals(listOf("what should I cook for dinner tonight" to "Try the lentil curry you liked."), Lessons.pick(lessons, "what can I cook for dinner?"))
        assertTrue(Lessons.pick(lessons, "how's the weather").isEmpty())
    }

    @Test fun flyerDatesBeatWeekdays() {
        // Sent Wed Oct 15 2025; the flyer says "Saturday, November 8, 7:00 PM till late".
        val sent = Calendar.getInstance().apply { set(2025, Calendar.OCTOBER, 15, 12, 0, 0) }.timeInMillis
        val w = PlanTime.resolve("Saturday, November 8, 7:00 PM till late", sent)!!
        val c = Calendar.getInstance().apply { timeInMillis = w.start }
        assertEquals(Calendar.NOVEMBER, c.get(Calendar.MONTH))
        assertEquals(8, c.get(Calendar.DAY_OF_MONTH))
        assertEquals(19, c.get(Calendar.HOUR_OF_DAY))
    }

    @Test fun eyesEarsAndFacts() {
        assertEquals("Priya is vegan", BrainPrompts.parseFact("FACT: Priya is vegan."))
        assertNull(BrainPrompts.parseFact("NONE"))
        assertTrue(BrainPrompts.mightHaveFact("I hate cilantro so much"))
        assertTrue(BrainPrompts.mightHaveFact("I work at the hospital on nights now"))
        assertTrue(!BrainPrompts.mightHaveFact("I'm at the store, need anything?"))
        assertEquals("Hey, it's Sam.", BrainPrompts.cleanTranscript("Transcript: \"Hey, it's Sam.\""))
        assertEquals("Phishing", BrainPrompts.parseScamAnswer("**SCAM: reason**  **Reason:** phishing")!!.reason)
        assertEquals("It reads like a scam", BrainPrompts.parseScamAnswer("SCAM: reason")!!.reason)
    }
}
