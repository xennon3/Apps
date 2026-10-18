package app.murmur.brain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class BrainTest {
    @Test fun readsScamVerdicts() {
        assertEquals(BrainPrompts.ScamOpinion(true, "Fake toll notice with a payment link"), BrainPrompts.parseScamAnswer("SCAM: fake toll notice with a payment link."))
        assertEquals(BrainPrompts.ScamOpinion(false, "A friend making plans"), BrainPrompts.parseScamAnswer("<think>\n</think>\n\nSAFE - a friend making plans"))
        assertEquals(BrainPrompts.ScamOpinion(true, "It reads like a scam"), BrainPrompts.parseScamAnswer("**SCAM**"))
        assertNull(BrainPrompts.parseScamAnswer("I'm not sure what you mean"))
    }

    @Test fun readsLabelledAnswers() {
        assertEquals(BrainPrompts.ScamOpinion(true, "Fake delivery fee"), BrainPrompts.parseScamAnswer("Label: SCAM (fake delivery fee)"))
    }

    @Test fun readsRewrites() {
        assertEquals("Okay, sounds good.", BrainPrompts.parseCalm("Calm: \"Okay, sounds good.\"\nAngry: more"))
        assertNull(BrainPrompts.parseCalm("  \n "))
    }

    @Test fun cleansReplies() {
        assertEquals("Hey, can we talk later?", BrainPrompts.clean("<think>\nok\n</think>\n\"Hey, can we talk later?\""))
    }
}
