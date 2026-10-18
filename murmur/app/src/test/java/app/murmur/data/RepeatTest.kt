package app.murmur.data

import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.Calendar

class RepeatTest {
    private fun at(y: Int, m: Int, d: Int, h: Int = 8) =
        Calendar.getInstance().apply { clear(); set(y, m - 1, d, h, 0, 0) }.timeInMillis

    @Test fun daily() = assertEquals(at(2026, 3, 1), Repeat.next("daily", at(2026, 2, 28)))

    @Test fun weekdaysSkipWeekend() {
        // Fri Oct 16 2026 → Mon Oct 19
        assertEquals(at(2026, 10, 19), Repeat.next("weekdays", at(2026, 10, 16)))
    }

    @Test fun monthlyKeepsTheThirtyFirst() {
        val rule = Repeat.encode(Repeat.Kind.MONTHLY, at(2026, 1, 31))
        val feb = Repeat.next(rule, at(2026, 1, 31))
        assertEquals(at(2026, 2, 28), feb)
        assertEquals(at(2026, 3, 31), Repeat.next(rule, feb))
    }

    @Test fun yearly() = assertEquals(at(2027, 5, 4), Repeat.next("yearly", at(2026, 5, 4)))

    @Test fun catchesUpAfterThePhoneWasOff() {
        // Set for Oct 1, phone off until Oct 10 at noon: next daily is Oct 11 8:00.
        assertEquals(at(2026, 10, 11), Repeat.next("daily", at(2026, 10, 1), after = at(2026, 10, 10, 12)))
    }

    @Test fun describes() {
        assertEquals("Every month on the 31st", Repeat.describe("monthly:31"))
        assertEquals("Every weekday", Repeat.describe("weekdays"))
        assertEquals(null, Repeat.describe(null))
    }
}
