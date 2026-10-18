package app.murmur.brain

import app.murmur.brain.BrainPrompts.Plan
import app.murmur.brain.BrainPrompts.Triage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BrainTest {
    @Test fun readsScamVerdicts() {
        assertEquals(BrainPrompts.ScamOpinion(true, "Fake toll notice with a payment link"), BrainPrompts.parseScamAnswer("SCAM: fake toll notice with a payment link."))
        assertEquals(BrainPrompts.ScamOpinion(false, "A friend making plans"), BrainPrompts.parseScamAnswer("<think>\n</think>\n\nSAFE - a friend making plans"))
        assertEquals(BrainPrompts.ScamOpinion(true, "It reads like a scam"), BrainPrompts.parseScamAnswer("**SCAM**"))
        assertNull(BrainPrompts.parseScamAnswer("I'm not sure what you mean"))
    }

    @Test fun namesAndTextingWordsMeanMessages() {
        val names = listOf("Jen Park", "Mom", "(555) 123-4567")
        assertTrue(BrainPrompts.mentionsTexts("what's the code for jen's gate", names))
        assertTrue(BrainPrompts.mentionsTexts("what did the plumber say", names))
        assertFalse(BrainPrompts.mentionsTexts("give me 3 quick dinner ideas", names))
        assertFalse(BrainPrompts.mentionsTexts("write a haiku about rain", names))
    }

    @Test fun routes() {
        assertTrue(BrainPrompts.needsMessages("MESSAGES"))
        assertFalse(BrainPrompts.needsMessages("GENERAL"))
    }

    // Real answers Gemma 4 E2B gave in testing.
    @Test fun readsSearchTerms() {
        assertEquals(listOf("gate", "code", "jen", "password"), BrainPrompts.parseTerms("gate code, jen, password, code"))
        assertEquals(listOf("party", "start", "starts"), BrainPrompts.parseTerms("party time, start time, party details, when party starts"))
        assertEquals(listOf("jen", "gate", "code", "password", "access"), BrainPrompts.parseTerms("jen's, gate, code, password, access"))
    }

    @Test fun readsPlans() {
        assertEquals(Plan("Brunch", "sunday 11am", "the corner cafe"), BrainPrompts.parseEvent("EVENT: Brunch | sunday 11am | the corner cafe"))
        assertEquals(Plan("Dental cleaning", "Oct 24 at 2:15 PM", ""), BrainPrompts.parseEvent("EVENT: Dental cleaning | Oct 24 at 2:15 PM |"))
        assertEquals(Plan("Movie", "tonight at 8", ""), BrainPrompts.parseEvent("EVENT: movie | tonight at 8 |"))
        assertNull(BrainPrompts.parseEvent("NONE"))
        val p = Plan("Dinner", "friday at 7", "Luigi's")
        assertEquals(p, BrainPrompts.decodePlan(BrainPrompts.encodePlan(p)))
        assertNull(BrainPrompts.decodePlan(BrainPrompts.encodePlan(null)))
    }

    @Test fun readsTriage() {
        assertEquals(Triage.REPLY, BrainPrompts.parseTriage("REPLY"))
        assertEquals(Triage.INFO, BrainPrompts.parseTriage("INFO."))
        assertEquals(Triage.PROMO, BrainPrompts.parseTriage("**PROMO**"))
        assertNull(BrainPrompts.parseTriage("hmm"))
    }

    @Test fun cleansReplies() {
        assertEquals("Hey, can we talk later?", BrainPrompts.clean("<think>\nok\n</think>\n\"Hey, can we talk later?\""))
    }
}
