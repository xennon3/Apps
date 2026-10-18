package app.murmur.brain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar

class PlanTimeTest {
    // Sent Sunday Oct 18 2026, 2:00 PM
    private val sent = at(2026, 10, 18, 14, 0)

    private fun at(y: Int, m: Int, d: Int, h: Int = 0, min: Int = 0) =
        Calendar.getInstance().apply { clear(); set(y, m - 1, d, h, min, 0) }.timeInMillis

    private fun r(p: String, ctx: String = "") = PlanTime.resolve(p, sent, ctx)

    @Test fun weekdays() {
        assertEquals(at(2026, 10, 23, 19), r("friday at 7", "dinner")?.start)
        assertEquals(at(2026, 10, 23, 19), r("friday at 7")?.start) // 7 with no am/pm → evening
        assertEquals(at(2026, 10, 20, 9), r("next tuesday morning")?.start)
        assertEquals(at(2026, 10, 18, 11), r("sunday 11am")?.start)
        assertEquals(at(2026, 10, 25, 11), r("next sunday 11am")?.start)
    }

    @Test fun relativeDays() {
        assertEquals(at(2026, 10, 19, 9, 30), r("tomorrow at 9:30")?.start)
        assertEquals(at(2026, 10, 18, 20), r("tonight at 8")?.start)
        assertEquals(at(2026, 10, 18, 19), r("tonight")?.start)
    }

    @Test fun dates() {
        assertEquals(at(2026, 10, 24, 14, 15), r("Oct 24 at 2:15 PM")?.start)
        assertEquals(at(2026, 11, 3, 0), r("november 3rd")?.start)
        assertTrue(r("november 3rd")!!.allDay)
        assertEquals(at(2027, 1, 5, 10), r("jan 5 at 10am")?.start) // already past this year → next year
        assertEquals(at(2026, 12, 1, 18, 30), r("12/1 at 6:30pm")?.start)
    }

    @Test fun timeOnly() {
        assertEquals(at(2026, 10, 18, 19), r("at 7", "movie")?.start)
        assertEquals(at(2026, 10, 19, 10), r("10am")?.start) // already past today → tomorrow
    }

    @Test fun rejects() {
        assertNull(r("yesterday at 7"))
        assertNull(r("sometime"))
        assertNull(r("last night"))
    }

    @Test fun preCheck() {
        assertTrue(PlanTime.mightHavePlan("brunch sunday 11am?"))
        assertTrue(PlanTime.mightHavePlan("dinner at 7"))
        assertTrue(PlanTime.mightHavePlan("Reminder: Oct 24 cleaning"))
        assertFalse(PlanTime.mightHavePlan("lol that's hilarious"))
        assertFalse(PlanTime.mightHavePlan("can't wait to see you"))
    }
}
