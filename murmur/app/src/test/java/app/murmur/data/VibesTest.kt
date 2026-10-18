package app.murmur.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class VibesTest {

    @Test fun findsCodes() {
        assertEquals("482913", Vibes.code("Your verification code is 482913. Don't share it."))
        assertEquals("1234", Vibes.code("G-1234 is your Google verification code."))
        assertEquals("381042", Vibes.code("Use 381-042 to sign in to Acme"))
        assertEquals("907311", Vibes.code("Your Uber code: 907311"))
        assertEquals("55120", Vibes.code("Chase: your one-time passcode is 55120"))
    }

    @Test fun ignoresNonCodes() {
        assertNull(Vibes.code("See you at 7, I'll bring the 2 pizzas"))
        assertNull(Vibes.code("Your package 1Z999 will arrive Tuesday"))
        assertNull(Vibes.code("Your code review is ready, PR merged in 2024"))
        assertNull(Vibes.code("Dinner was $45.50, can you send me half?"))
    }

    @Test fun heat() {
        assertEquals(Vibes.Heat.CALM, Vibes.heat("Sounds good, see you at 8!"))
        assertEquals(Vibes.Heat.HOT, Vibes.heat("ARE YOU KIDDING ME?? You ALWAYS DO THIS"))
        assertEquals(Vibes.Heat.HOT, Vibes.heat("I'm done. Seriously? Whatever."))
        assertEquals(Vibes.Heat.WARM, Vibes.heat("k."))
    }

    @Test fun segments() {
        assertEquals(1 to 155, Vibes.segments("hello"))
        assertEquals(1 to 0, Vibes.segments("a".repeat(160)))
        assertEquals(2 to 145, Vibes.segments("a".repeat(161)))
        assertEquals(1 to 69, Vibes.segments("😀".substring(0, 1)))
        assertEquals(1 to 68, Vibes.segments("😀"))
        assertEquals(1 to 158, Vibes.segments("{}".substring(0, 1))) // brace costs two units
    }

    @Test fun initials() {
        assertEquals("AL", Vibes.initials("Ada Lovelace"))
        assertEquals("#", Vibes.initials("(555) 123-4567"))
    }
}
