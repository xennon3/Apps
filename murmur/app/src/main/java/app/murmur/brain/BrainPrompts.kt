package app.murmur.brain

/**
 * What Murmur asks the model and how it reads the answers. Plain Kotlin with no Android
 * code, so the same prompts can be tried against the model on a computer. Every prompt here
 * was checked against Gemma 4 E2B before shipping.
 *
 * Small models copy a pattern far better than they follow rules, so most prompts come with
 * a few worked examples, given to the model as earlier turns of the chat.
 */
object BrainPrompts {

    // ================================================================ chat

    const val CHAT_GENERAL =
        "You are Murmur, a friendly assistant inside a texting app, running privately on the user's phone. " +
            "Keep answers short and plain, a few sentences at most."

    const val CHAT_MESSAGES =
        "You are Murmur, a friendly assistant inside a texting app, running privately on the user's phone. " +
            "Some of the user's text messages are included. Answer from them, saying who sent it and when. " +
            "If they don't answer the question, say you couldn't find it in their texts, then help as best you can. Keep it short."

    /** Does this question need the user's texts? */
    const val ROUTE =
        "Decide whether answering needs the user's own text messages. Reply MESSAGES if it asks about something " +
            "someone texted, said, sent or planned, or about a person in their messages. Reply GENERAL for anything " +
            "else: chat, advice, writing, facts, jokes. One word."

    val ROUTE_EXAMPLES = listOf(
        "what's the wifi password sam sent?" to "MESSAGES",
        "write me a short poem about fall" to "GENERAL",
        "did mom say when she's coming" to "MESSAGES",
        "how long should I boil an egg" to "GENERAL",
        "what did jake want" to "MESSAGES",
        "help me word a birthday text for my sister" to "GENERAL",
        "what's the address for amy's place" to "MESSAGES",
    )

    private val textVerbs = Regex("(?i)\\b(text(ed)?|sent|send|said|say|told|tell me what|messag(e|ed)|wrote|asked)\\b")

    /**
     * Plain-code backup for the router: a question that names someone the user texts
     * ("jen's gate code") or talks about texting is about their messages, whatever the model says.
     */
    fun mentionsTexts(question: String, names: Collection<String>): Boolean {
        val words = question.lowercase().split(Regex("[^a-z0-9']+")).map { it.removeSuffix("'s").removeSuffix("’s") }.filter { it.length >= 3 }.toSet()
        val nameWords = names.flatMap { n -> n.lowercase().split(Regex("[^a-z0-9]+")).filter { it.length >= 3 } }.toSet()
        return words.any { it in nameWords } || textVerbs.containsMatchIn(question)
    }

    fun needsMessages(answer: String) = clean(answer).contains("MESSAGES", ignoreCase = true)

    const val SEARCH =
        "List search words for finding the text messages that answer a question. Up to 6 words or short phrases, " +
            "separated by commas, including likely synonyms and names."

    val SEARCH_EXAMPLES = listOf(
        "what's the wifi password sam sent?" to "wifi, password, network, sam",
        "where did mom say to meet" to "meet, address, where, mom",
    )

    private val stopWords = setOf(
        "the", "and", "for", "what", "when", "where", "who", "did", "does", "say", "said", "sent", "text", "texted",
        "message", "messages", "time", "about", "with", "from", "that", "this", "details", "info", "status", "reply",
    )

    /** Search words from the model's list: single words, no filler, no duplicates. */
    fun parseTerms(answer: String): List<String> =
        clean(answer).lowercase().split(',', '\n', ' ')
            .map { it.trim().trim('.', '"', '\'', '-', '*').removeSuffix("'s").removeSuffix("’s") }
            .filter { it.length >= 3 && it !in stopWords && it.any(Char::isLetterOrDigit) }
            .distinct().take(8)

    /** The question plus the texts found for it, as one message to the model. */
    fun withMessages(question: String, found: List<String>): String =
        if (found.isEmpty()) "No matching text messages were found.\n\nQuestion: $question"
        else "Text messages that might help:\n" + found.joinToString("\n") + "\n\nQuestion: $question"

    // ================================================================ plans → calendar

    const val EVENT =
        "Find a future plan or appointment in a text message. Reply in exactly this form: EVENT: <short title> | " +
            "<when, copied from the text> | <place, or nothing>. If there's no upcoming plan with a specific day or " +
            "time, or it already happened, reply NONE."

    val EVENT_EXAMPLES = listOf(
        "dinner friday at 7 at Luigi's?" to "EVENT: Dinner | friday at 7 | Luigi's",
        "Your appointment with Dr. Lee is tomorrow at 9:30. Reply C to confirm" to "EVENT: Dr. Lee appointment | tomorrow at 9:30 |",
        "we went bowling saturday night, so fun" to "NONE",
        "we should hang out sometime" to "NONE",
    )

    data class Plan(val title: String, val whenText: String, val place: String)

    fun parseEvent(answer: String): Plan? {
        val line = clean(answer).lineSequence().map { it.trim().replace("*", "") }
            .firstOrNull { it.startsWith("EVENT", true) } ?: return null
        val parts = line.substringAfter(':').split('|').map { it.trim() }
        if (parts.size < 2 || parts[0].isBlank() || parts[1].isBlank()) return null
        return Plan(parts[0].replaceFirstChar { it.uppercase() }, parts[1], parts.getOrElse(2) { "" })
    }

    /** Stored per message: "title|when|place", or "-" for no plan. */
    fun encodePlan(p: Plan?) = p?.let { listOf(it.title, it.whenText, it.place).joinToString("|") { s -> s.replace("|", "/") } } ?: "-"

    fun decodePlan(s: String): Plan? = s.takeIf { it != "-" }?.split('|')?.takeIf { it.size == 3 }?.let { Plan(it[0], it[1], it[2]) }

    // ================================================================ needs a reply

    enum class Triage { REPLY, INFO, PROMO }

    const val TRIAGE =
        "Sort a text message someone sent the user. REPLY if it asks the user something or clearly expects an answer. " +
            "INFO if it just tells them something. PROMO if it's marketing, automated, or a code. Reply with one word."

    val TRIAGE_EXAMPLES = listOf(
        "are you coming tonight?" to "REPLY",
        "running 5 min late" to "INFO",
        "SALE! 40% off everything this weekend only. Reply STOP to opt out" to "PROMO",
        "let me know what you think of the photos" to "REPLY",
        "Your order has shipped" to "PROMO",
    )

    fun parseTriage(answer: String): Triage? {
        val a = clean(answer).uppercase()
        return Triage.entries.firstOrNull { a.startsWith(it.name) } ?: Triage.entries.firstOrNull { a.contains(it.name) }
    }

    // ================================================================ catch me up

    const val SUMMARY =
        "Summarize a text conversation for someone who missed it. Use at most 3 short bullet points, each starting " +
            "with '- '. Cover what was decided, what's still open, and anything asked of 'You'. Nothing else."

    // ================================================================ shared

    data class ScamOpinion(val scam: Boolean, val reason: String)

    const val SCAM =
        "You are a careful fraud analyst. Decide if a text message is a scam (phishing, fake delivery, " +
            "fake toll or bank alert, prize, job or investment bait, someone asking for money or codes). " +
            "Answer on one line in exactly this form: SCAM: <short reason> or SAFE: <short reason>. " +
            "Most texts are SAFE; only say SCAM when it pushes a link, money, codes or personal details."

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
