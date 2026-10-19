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

    @Test fun dropsCharactersThatCrashTheEngine() {
        assertEquals("dinner at 7", BrainPrompts.engineSafe("dinner at 7 🍝"))
        assertEquals("happy birthday !", BrainPrompts.engineSafe("happy birthday 🎉🎂!"))
        assertEquals("café ☀ ❤", BrainPrompts.engineSafe("café ☀️ ❤️"))
        assertEquals("plain", BrainPrompts.engineSafe("plain"))
    }

    @Test fun recognisesSendRequests() {
        for (q in listOf("text mom I'm late", "tell jake happy birthday", "let my landlord know the sink is fixed", "ask priya about lunch", "send sam a message saying thanks", "message jen: gate code?"))
            assertTrue(q, BrainPrompts.isSendRequest(q))
        for (q in listOf("let me think", "what did mom text me", "send a photo how?", "tell me a joke", "how do I text a photo"))
            assertFalse(q, BrainPrompts.isSendRequest(q))
    }

    // Real answers Gemma 4 E2B gave in testing.
    @Test fun readsSendDrafts() {
        assertEquals(BrainPrompts.SendDraft("landlord", "Just letting you know the sink is fixed."), BrainPrompts.parseSend("SEND: landlord | Just letting you know the sink is fixed."))
        assertEquals(BrainPrompts.SendDraft("jen", "Can you send me the gate code again?"), BrainPrompts.parseSend("SEND: jen | Can you send me the gate code again?"))
        assertNull(BrainPrompts.parseSend("NONE"))
    }

    @Test fun splitsSendRequestsWithoutTheModel() {
        assertEquals(BrainPrompts.SendDraft("mom", "I'm running late"), BrainPrompts.fallbackSend("text mom that I'm running late"))
        assertEquals(BrainPrompts.SendDraft("my sister", "I'll be there at 6"), BrainPrompts.fallbackSend("text my sister I'll be there at 6"))
        assertEquals(BrainPrompts.SendDraft("jen", "Can you send the code?"), BrainPrompts.fallbackSend("message jen: can you send the code?"))
        assertEquals(BrainPrompts.SendDraft("landlord", "The sink is fixed"), BrainPrompts.fallbackSend("let landlord know the sink is fixed"))
    }

    @Test fun memoryCommands() {
        assertEquals("My sister is Priya", BrainPrompts.rememberCommand("remember that my sister is Priya"))
        assertEquals("I'm vegetarian", BrainPrompts.rememberCommand("please remember I'm vegetarian."))
        assertNull(BrainPrompts.rememberCommand("do you remember what jake said"))
        assertEquals("my sister", BrainPrompts.forgetCommand("forget about my sister"))
        assertTrue(BrainPrompts.wantsLocation("text jake where I am"))
    }

    @Test fun picksPhoneData() {
        assertTrue(BrainPrompts.asksCalendar("am I free friday night?"))
        assertTrue(BrainPrompts.asksCalls("did mom call me today?"))
        assertTrue(BrainPrompts.asksContact("what's priya's birthday"))
        assertFalse(BrainPrompts.asksCalendar("tell me a joke"))
    }

    @Test fun cleansReplies() {
        assertEquals("Hey, can we talk later?", BrainPrompts.clean("<think>\nok\n</think>\n\"Hey, can we talk later?\""))
    }

    @Test fun parsesReplySuggestions() {
        val raw = "1. Sure, what time?\n- \"can't this weekend\"\nYou: Sure, what time\nhow far is it?\nfourth one"
        assertEquals(listOf("Sure, what time?", "can't this weekend", "how far is it?"), BrainPrompts.parseReplies(raw, emptyList()))
    }

    @Test fun repliesFollowTheUsersHabits() {
        val casual = listOf("lol ok", "on my way", "yeah sounds good", "see u there")
        assertEquals("sure, what time?", BrainPrompts.styleLike("Sure, what time?", casual))
        assertEquals("ok I'll be there", BrainPrompts.styleLike("Ok I'll be there.", casual))
        val formal = listOf("Hello, is this still available?", "Thank you.", "See you at 3.")
        assertEquals("Yes, that works.", BrainPrompts.styleLike("yes, that works.", formal))
        assertEquals("I'm in", BrainPrompts.styleLike("I'm in", casual)) // never lowercase a lone "I"
        assertEquals("yes", BrainPrompts.styleLike("yes", listOf("Hi"))) // too few texts to judge
    }
}
