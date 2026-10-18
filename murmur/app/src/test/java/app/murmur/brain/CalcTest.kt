package app.murmur.brain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CalcTest {
    private fun c(s: String) = Calc.eval(s)?.let(Calc::format)

    @Test fun arithmetic() {
        assertEquals("14", c("2 + 3 * 4"))
        assertEquals("20", c("(2 + 3) * 4"))
        assertEquals("1024", c("2^10"))
        assertEquals("-3", c("-5 + 2"))
        assertEquals("3.5", c("7 / 2"))
        assertEquals("1", c("10 % 3"))
    }

    @Test fun howPeopleWriteIt() {
        assertEquals("12", c("15% of 80"))
        assertEquals("92", c("80 + 15%"))
        assertEquals("68", c("80 - 15%"))
        assertEquals("255", c("85 x 3"))
        assertEquals("255", c("85 times 3"))
        assertEquals("1234.5", c("$1,234.50"))
        assertEquals("29.4", c("what is 147 divided by 5?"))
    }

    @Test fun rejectsNonsense() {
        assertNull(c("hello"))
        assertNull(c("2 +"))
        assertNull(c("1/0"))
    }
}
