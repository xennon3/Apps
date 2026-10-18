package app.murmur.brain

/**
 * What Murmur asks the model and how it reads the answers. Plain Kotlin with no Android
 * code, so the same prompts can be tried against the model on a computer.
 */
object BrainPrompts {

    const val CALM =
        "You rewrite angry text messages so they say the same thing calmly and kindly, in the sender's own voice. " +
            "Reply with only the rewritten message, nothing else."

    const val SCAM =
        "You are a careful fraud analyst. Decide if a text message is a scam (phishing, fake delivery, " +
            "fake toll or bank alert, prize, job or investment bait, someone asking for money or codes). " +
            "Answer on one line in exactly this form: SCAM: <short reason> or SAFE: <short reason>. " +
            "Most texts are SAFE; only say SCAM when it pushes a link, money, codes or personal details."

    /**
     * Worked examples, given to the model as earlier turns of the chat: small models copy a pattern
     * far better than they follow rules. Three is enough; each extra one is more for the phone to read.
     */
    val CALM_EXAMPLES = listOf(
        "you're late AGAIN, unbelievable" to "Hey, you're running late again. Can we talk about timing?",
        "stop ignoring me!!!" to "I haven't heard back and I'm feeling a bit ignored. Can you reply when you get a chance?",
        "whatever, do what you want" to "I'm frustrated, but I'll let you decide. Can we talk about it later?",
    )

    fun calmPrompt(draft: String) = "Rewrite calmly: " + draft.replace('\n', ' ').trim()

    /** The model's rewrite: the first line it gives, without labels or quotes. */
    fun parseCalm(raw: String): String? =
        clean(raw).lineSequence().map { it.trim() }.firstOrNull { it.isNotEmpty() }
            ?.removePrefix("Calm:")?.trim()?.trim('"', '“', '”')?.takeIf { it.isNotBlank() }

    val SCAM_EXAMPLES = listOf(
        "Your package is on hold. Pay the ${'$'}1.99 redelivery fee: usps-help.info" to "SCAM: fake delivery fee",
        "Running 10 min late, save me a seat!" to "SAFE: a friend making plans",
        "Congrats! You won a ${'$'}500 Walmart gift card, claim now: wmt-prize.top" to "SCAM: fake prize",
        "Your appointment with Dr. Lee is tomorrow at 9:30. Reply C to confirm" to "SAFE: appointment reminder",
        "Hi dad, this is my new number, I need money for rent urgently, can you send ${'$'}800?" to "SCAM: pretends to be family and asks for money",
        "Your verification code is 552891. Don't share it with anyone." to "SAFE: a login code",
        "Your Visa card ending 0921 was charged ${'$'}84.10 at Target. Not you? Call the number on your card" to "SAFE: a bank alert with no link",
    )

    fun scamQuestion(message: String) = "Text: " + message.replace('\n', ' ').trim()

    data class ScamOpinion(val scam: Boolean, val reason: String)

    /** Small models sometimes leave reasoning tags or wrapping quotes; strip them. */
    fun clean(raw: String): String =
        raw.replace(Regex("(?s)<think>.*?</think>"), "")
            .replace("<think>", "").replace("</think>", "")
            .trim().trim('"', '“', '”').trim()

    /** Reads "SCAM: fake toll notice" / "SAFE: a friend making plans" out of whatever the model said. */
    fun parseScamAnswer(answer: String): ScamOpinion? {
        val line = clean(answer).lineSequence().map { it.trim().replace("*", "").trimStart('-', ' ') }
            .map { it.removePrefix("Label:").trim() }
            .firstOrNull { it.startsWith("SCAM", true) || it.startsWith("SAFE", true) } ?: return null
        val scam = line.startsWith("SCAM", true)
        val reason = line.removePrefix("Label:").trim().drop(4).trimStart(':', ' ', '-', '—', '(').trim().trimEnd('.', ')')
            .ifBlank { if (scam) "It reads like a scam" else "" }
        return ScamOpinion(scam, reason.replaceFirstChar { it.uppercase() })
    }
}
