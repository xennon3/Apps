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

    /**
     * Gemma is trained to hedge ("As an AI, I don't…", "it really depends!"), and a small model falls back
     * on that reflex unless told otherwise. The user wants a frank, capable helper on any everyday subject.
     */
    const val OPINIONS =
        "Talk like a knowledgeable, frank friend. Help with any subject the user brings up: health and medicines, legal " +
            "and money questions, relationships, dating and sex, politics, religion, mature or dark topics, fiction and jokes. " +
            "The user is an adult: give real, specific information and your honest view. When asked what you'd pick, which is " +
            "best, or what you think, commit to a specific answer and say why; don't say \"it depends\" without then choosing. " +
            "Never say you're an AI or that you can't have opinions. Mention a doctor, lawyer or other professional only when " +
            "it truly matters, in one short line after the answer."

    const val CHAT_GENERAL =
        "You are Murmur, a friendly assistant inside a texting app, running privately on the user's phone. " +
            "Answer plainly. Simple questions get a short answer; when a question needs more, give a complete, well-organized one. " + OPINIONS

    /** Worked examples of decisive answers; small models copy examples more reliably than they follow rules. */
    val OPINION_EXAMPLES = listOf(
        "what's the best pizza topping?" to
            "Pepperoni. It crisps at the edges, goes with almost everything, and it's hard to get wrong. If you want something less obvious, sausage and mushroom together is excellent.",
        "should I learn guitar or piano first? which would you pick" to
            "Piano. The notes are laid out in a straight line, so music theory clicks faster, and it doesn't hurt your fingers at the start. Guitar is the better pick only if you mostly want to strum songs with friends.",
        "how much ibuprofen can I take for a bad headache?" to
            "For an adult, 200 to 400 mg every 4 to 6 hours with food, and no more than 1,200 mg in a day without a doctor's okay. Skip it if you have stomach ulcers or kidney problems, or take blood thinners.",
    )

    private val refusal = Regex(
        "(?i)(as an ai|as a language model|i('m| am) (just )?an ai|i don'?t have (personal )?(opinions|preferences|feelings)|" +
            "i (can'?t|cannot|am not able to|'m not able to) (recommend|give (you )?(a )?(specific|personal)|choose|pick|have opinions)|" +
            "i'?m not able to (recommend|choose|pick))",
    )

    private val generalRefusal = Regex("(?i)^(i'?m sorry,? (but )?)?i (can'?t|cannot|won'?t|am not able to|'m not able to) (help|assist|provide|answer|discuss|talk about)")
    private val fence = Regex("(?i)^(that |it |this )?(really |all |truly )?(depends|varies)|^(both|there'?s no (single|one|right))|^(it'?s a |that'?s a )(popular |classic |great )?(debate|personal choice|matter of)")
    private val askingOpinion = Regex("(?i)\\b(best|better|would you|should i|your (opinion|pick|take|view)|what do you think|favou?rite|which (one|is)|is .{1,40} (good|worth|overrated))\\b")

    /** A dodge, caught by plain code so the app can ask again: "As an AI…", "I can't help with that", or fence-sitting when asked to choose. */
    fun isRefusal(answer: String, question: String = ""): Boolean {
        val a = answer.trim().take(300)
        return refusal.containsMatchIn(a) || generalRefusal.containsMatchIn(a) ||
            (askingOpinion.containsMatchIn(question) && fence.containsMatchIn(a))
    }

    /** Added to the question when the first answer dodged. */
    fun pushForOpinion(q: String) = "$q\n\n(Answer directly, the way a frank, knowledgeable friend would. If it asks for a choice, pick one specific option and say why.)"

    private val hardWords = Regex(
        "(?i)\\b(why|should i|should we|which is better|better option|pros and cons|compare|versus|vs\\.?|recommend|advice|" +
            "plan|schedule|budget|afford|worth it|how (do|can|should) i|help me (decide|figure|plan|write)|explain|what if|" +
            "strategy|step by step|figure out|calculate|how many|how much|when should|riddle|puzzle|percent|discount|interest|" +
            "would you (buy|purchase|pick|choose|get|do|go with|take|recommend)|your (opinion|pick|take|choice)|what do you think|which one)\\b",
    )

    /** Worth thinking about first: a question with reasoning words, or a long one with numbers in it. Plain code decides. */
    fun isHard(q: String): Boolean {
        val digits = Regex("\\d").findAll(q).count()
        return hardWords.containsMatchIn(q) && q.length > 25 || (q.length > 140 && digits >= 2) || q.length > 260
    }

    /** For questions that need working out: think first, then answer clearly. */
    const val CHAT_DEEP =
        "You are Murmur, a thoughtful assistant running privately on the user's phone. Work the problem out carefully " +
            "before answering: check the numbers, consider the options, and the person's situation. Then give a clear answer " +
            "with the reasoning that matters, without padding. Write maths in plain text (40 / 0.8 = 50), never LaTeX. " + OPINIONS

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

    // ================================================================ tools

    /**
     * The system prompt when tools are available. Tested on Gemma 4 E2B: it picked the right tool
     * (or none) for 13 of 13 questions. Lookup advice is only given when lookups are switched on.
     */
    fun toolsSystem(canLookUp: Boolean): String =
        "You are Murmur, a friendly assistant inside a texting app on the user's phone. Most questions need no tool: " +
            "just answer. Use a tool only when it helps, and never talk to the user about tools. For any math, use calculate. " +
            (if (canLookUp) "You don't know anything recent: for news, sports results, prices, opening hours, events or " +
                "anything that changes, use web_search. For facts about the world you aren't sure of, look them up " +
                "instead of guessing. When you used the web, use the specifics it found (names, models, years, prices) and say where they came from. "
            else "You can't browse the web; if asked for recent news, prices or opening hours, say web lookups are off in Settings. ") +
            "Simple questions get a short answer; bigger ones a complete one. " + OPINIONS

    // ================================================================ sending texts from the chat

    /** Plain code decides it's a send request; the model only words it. */
    private val sendStart = Regex(
        "(?i)^\\s*(please\\s+)?(text|send|message|msg|tell|ask|remind|reply to|answer|let)\\b(?!\\s+me\\b)",
    )

    fun isSendRequest(text: String): Boolean {
        val t = text.trim()
        if (!sendStart.containsMatchIn(t)) return false
        // "let X know …" is a send; "let me …" and questions about texting aren't.
        if (Regex("(?i)^\\s*let\\b").containsMatchIn(t) && !Regex("(?i)\\bknow\\b").containsMatchIn(t)) return false
        return !Regex("(?i)^\\s*(text|send|message)\\s+(a|an|the|photo|picture|pic|voice)\\b").containsMatchIn(t)
    }

    const val SEND =
        "The user is asking you to text someone. Reply in exactly this form: SEND: <who> | " +
            "<the message, written the way the user would text it, in their voice>"

    val SEND_EXAMPLES = listOf(
        "text mom that I'm running 10 minutes late" to "SEND: mom | Running 10 minutes late!",
        "ask priya if she wants to get lunch" to "SEND: priya | Want to get lunch?",
        "tell jake happy birthday" to "SEND: jake | Happy birthday!",
        "let the plumber know tuesday works" to "SEND: plumber | Tuesday works for me.",
    )

    data class SendDraft(val who: String, val message: String)

    fun parseSend(answer: String): SendDraft? {
        val line = clean(answer).lineSequence().map { it.trim().replace("*", "") }.firstOrNull { it.startsWith("SEND", true) } ?: return null
        val parts = line.substringAfter(':').split('|', limit = 2).map { it.trim() }
        if (parts.size < 2 || parts[0].isBlank() || parts[1].isBlank()) return null
        return SendDraft(parts[0], parts[1])
    }

    /** If the model can't, split "text mom that I'm late" by hand: the word after the verb is who. */
    fun fallbackSend(request: String): SendDraft? {
        val m = Regex("(?i)^\\s*(?:please\\s+)?(?:text|send|message|msg|tell|ask|remind|reply to|answer|let)\\s+(?:a message to\\s+|to\\s+)?(my\\s+\\w+|the\\s+\\w+|\\w+)[\\s,:]+(?:know\\s+)?(?:that\\s+|saying\\s+|to say\\s+|:\\s*)?(.+)$")
            .find(request.trim()) ?: return null
        val msg = m.groupValues[2].trim().trimEnd('.').replaceFirstChar { it.uppercase() }
        return SendDraft(m.groupValues[1].trim(), msg).takeIf { msg.isNotBlank() }
    }

    /** "text jake where I am": Murmur fills in a map link itself rather than letting the model guess. */
    fun wantsLocation(text: String) = Regex("(?i)\\b(where i am|where i'm at|my location|my address right now|where i'm)\\b").containsMatchIn(text)

    // ================================================================ memory

    /** "remember that my sister is Priya" → "My sister is Priya". */
    fun rememberCommand(text: String): String? =
        Regex("(?i)^\\s*(?:please\\s+)?(?:remember|note|don't forget|dont forget|keep in mind)(?:\\s+that)?[\\s:,]+(.+)$")
            .find(text)?.groupValues?.get(1)?.trim()?.trimEnd('.')?.replaceFirstChar { it.uppercase() }?.takeIf { it.length >= 3 }

    /** "forget that I'm vegetarian" / "forget my sister" → the words to match against what's remembered. */
    fun forgetCommand(text: String): String? =
        Regex("(?i)^\\s*(?:please\\s+)?forget(?:\\s+(?:that|about))?[\\s:,]+(.+)$").find(text)?.groupValues?.get(1)?.trim()?.trimEnd('.')

    fun memoryBlock(facts: List<String>) =
        if (facts.isEmpty()) "" else "Things the user asked you to remember:\n" + facts.joinToString("\n") { "- $it" }

    // ================================================================ which phone data a question needs

    fun asksCalendar(q: String) = Regex("(?i)\\b(free|busy|calendar|schedule|appointments?|meetings?|events?|what do i have|what's on|whats on|plans? (for|on|this|tomorrow|tonight))\\b").containsMatchIn(q)

    fun asksCalls(q: String) = Regex("(?i)\\b(call(ed|s|ing)?|missed|rang|phoned|last talk(ed)?|voicemail)\\b").containsMatchIn(q)

    fun asksContact(q: String) = Regex("(?i)\\b(number|phone number|birthday|bday|email|e-mail|contact (info|details))\\b").containsMatchIn(q)

    /** Everything Murmur knows for this question, as one system prompt. */
    fun system(base: String, persona: String, blocks: List<String>): String =
        listOf(base, persona.takeIf { it.isNotBlank() }?.let { "How the user wants you to talk: $it" })
            .plus(blocks.filter { it.isNotBlank() })
            .filterNotNull().joinToString("\n\n")

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

    // ================================================================ reply suggestions

    const val REPLIES =
        "Suggest 3 short replies 'You' could send next in this text conversation. Each answers the last message " +
            "in a different way: one yes or agree, one no or not now, one asking something back. Sound like 'You': " +
            "same tone, same length, casual. One reply per line. No numbers, quotes or names. Nothing else."

    val REPLIES_EXAMPLES = listOf(
        "Them: are you free saturday?\nYou: maybe, why\nThem: want to help me move? I'll buy pizza" to
            "sure, what time?\ncan't this saturday, sorry\nhow much stuff do you have?",
        "You: Hi Dr. Patel, could I move my appointment?\nThem: Of course. Would Thursday at 2pm work?" to
            "Thursday at 2pm works, thank you.\nThursday doesn't work for me. Is Friday possible?\nIs there anything earlier in the week?",
        "Them: running late, there in 15" to
            "no worries\nok, I'll grab a table\nwant me to order for you?",
    )

    /** "Them:/You:" transcript of the last few messages, newest last. */
    fun replyTranscript(lines: List<Pair<Boolean, String>>): String =
        lines.takeLast(8).joinToString("\n") { (mine, t) -> (if (mine) "You: " else "Them: ") + t.replace('\n', ' ').trim().take(300) }

    /** The model's lines → up to 3 clean, distinct replies, shaped like the user's own texts. */
    fun parseReplies(answer: String, mySent: List<String>): List<String> {
        val replies = clean(answer).lines()
            .map { it.trim().removePrefix("-").removePrefix("•").trim().replace(Regex("^\\d+[.)]\\s*"), "").removePrefix("You:").trim().trim('"', '\u201C', '\u201D') }
            .filter { it.length in 1..120 && !it.startsWith("Them:") }
            .distinctBy { it.lowercase().trimEnd('.', '!', '?') }
            .take(3)
        return replies.map { styleLike(it, mySent) }
    }

    /** Plain code matches the user's habits: all-lowercase writers get lowercase, no-full-stop writers lose the full stop. */
    fun styleLike(reply: String, mySent: List<String>): String {
        val sample = mySent.map { it.trim() }.filter { it.any(Char::isLetter) }.takeLast(15)
        if (sample.size < 3) return reply
        var r = reply
        val lowerWriter = sample.count { s -> s.first { it.isLetter() }.isLowerCase() } * 3 >= sample.size * 2
        val first = r.split(' ').first()
        val pronounI = first.trimEnd(',', '!', '.') == "I" || first.startsWith("I'") || first.startsWith("I\u2019")
        if (lowerWriter && !pronounI && r.firstOrNull()?.isUpperCase() == true && first.drop(1).all { it.isLowerCase() || !it.isLetter() }) {
            r = r.replaceFirstChar { it.lowercase() }
        }
        val capsWriter = sample.count { s -> s.first { it.isLetter() }.isUpperCase() } * 4 >= sample.size * 3
        if (capsWriter && r.firstOrNull()?.isLowerCase() == true) r = r.replaceFirstChar { it.uppercase() }
        val noFullStop = sample.count { !it.endsWith('.') } * 4 >= sample.size * 3
        if (noFullStop && r.endsWith('.') && !r.endsWith("..")) r = r.dropLast(1)
        return r
    }

    // ================================================================ eyes and ears

    const val LISTEN = "You write down voice messages word for word. Reply with only the words that are spoken, nothing else."

    fun cleanTranscript(answer: String): String? = clean(answer)
        .removePrefix("Transcript:").trim().trim('"', '\u201C', '\u201D').trim()
        .takeIf { it.isNotBlank() && !it.startsWith("I can't", true) && !it.startsWith("I cannot", true) }

    const val LOOK = "You look at pictures people texted the user and answer briefly and plainly."

    const val PIC_EVENT = "If this picture shows an upcoming plan, event or appointment with a day or time, answer on one line: " +
        "EVENT: <short title> | <when, as written> | <place, or nothing>. Otherwise answer NONE."

    const val PIC_DESCRIBE = "What is in this picture? Answer in one or two short sentences. If it shows writing, include the important details."

    const val PIC_SCAM = "Is the message or offer shown in this picture a scam? Answer on one line in exactly this form: " +
        "SCAM: <short reason> or SAFE: <short reason>."

    // ================================================================ the phone itself

    private val phoneWords = Regex(
        "(?i)\\b(battery|charg(e|ing|ed)|flash ?light|torch|timer|alarm|vibrate|silent mode|ringer|volume|storage|wi-?fi|signal|" +
            "steps|screen ?time|how fast|my speed|altitude|elevation|compass|which way|where am i|my location|airplane mode|" +
            "(turn|switch) (on|off) the|open (the )?\\w+( app)?\\b|phone('s)? (temp|status|memory))",
    )

    fun asksPhone(q: String) = phoneWords.containsMatchIn(q)

    const val PHONE_OFF = "Murmur Brain can't read or control this phone yet. If the user asks about the battery, speed, location, " +
        "storage, network, steps, screen time, or asks to use the flashlight, timers, alarms or apps, tell them to turn on the matching switch " +
        "in Settings, under What Murmur Brain can use. Don't guess the answer."

    // ================================================================ things worth remembering

    const val FACT =
        "Read a text someone sent the user. If the sender shares a lasting fact about themselves (diet, allergy, birthday, " +
            "job, where they live, family, pets, likes and dislikes), reply FACT: <the fact, written with their name>. " +
            "Otherwise reply NONE. Plans, moods and one-off news are NONE."

    val FACT_EXAMPLES = listOf(
        "Sender: Priya\nText: I'm vegetarian btw, so no meat for me" to "FACT: Priya is vegetarian",
        "Sender: Jake\nText: running late, there in 10" to "NONE",
        "Sender: Mom\nText: remember I'm allergic to shellfish when you order" to "FACT: Mom is allergic to shellfish",
        "Sender: Sam\nText: I'm so tired today" to "NONE",
        "Sender: Leo\nText: we just moved to Denver! new address soon" to "FACT: Leo lives in Denver",
    )

    private val firstPerson = Regex("(?i)\\b(i|i'?m|im|i'?ve|i'?ll|my|me)\\b")
    private val factWords = Regex(
        "(?i)\\b(vegetarian|vegan|allergic|allergy|gluten|lactose|kosher|halal|sober|don'?t drink|pregnant|birthday|born|work(?:ing)? (?:at|for|as|in|on)|job|" +
            "moved|live in|living in|new address|married|engaged|divorced|husband|wife|boyfriend|girlfriend|partner|kids?|son|daughter|" +
            "dog|cat|pet|favou?rite|hate|can'?t stand|love|nickname|call me|new number|retired|graduat\\w*)\\b",
    )

    /** Cheap check before waking the model: does the text sound like the sender is telling something about themselves? */
    fun mightHaveFact(text: String) = text.length in 12..400 && firstPerson.containsMatchIn(text) && factWords.containsMatchIn(text)

    fun factQuestion(sender: String, text: String) = "Sender: $sender\nText: " + text.replace('\n', ' ').trim()

    fun parseFact(answer: String): String? = clean(answer).lineSequence().map { it.trim().replace("*", "") }
        .firstOrNull { it.startsWith("FACT", true) }?.substringAfter(':')?.trim()?.trimEnd('.')
        ?.takeIf { it.length in 4..120 }

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

    /**
     * Text safe to hand to the engine. LiteRT-LM aborts the whole app on characters outside
     * Unicode's basic plane (emoji like 🍝 or 😂, stored as surrogate pairs), so those are dropped
     * before anything reaches it. Accented letters and most symbols are fine. Found by testing:
     * "dinner at 7 🍝" killed the engine; "café" didn't.
     */
    fun engineSafe(text: String): String {
        if (text.none { Character.isSurrogate(it) || it == '\uFE0F' }) return text
        val out = StringBuilder(text.length)
        var i = 0
        while (i < text.length) {
            val cp = text.codePointAt(i)
            if (cp <= 0xFFFF && !Character.isSurrogate(text[i])) out.append(text[i])
            i += Character.charCount(cp)
        }
        return out.toString().replace(Regex("[ \\t]{2,}"), " ").replace("\uFE0F", "").trim()
    }

    /** Small models sometimes leave reasoning tags or wrapping quotes; strip them. */
    fun clean(raw: String): String =
        raw.replace(Regex("(?s)<think>.*?</think>"), "")
            .replace(Regex("^\\s*(\\*\\*)?Murmur:?(\\*\\*)?:?\\s*"), "")
            .replace("<think>", "").replace("</think>", "")
            .trim().trim('"', '“', '”').trim()

    /** Reads "SCAM: fake toll notice" / "SAFE: a friend making plans" out of whatever the model said. */
    fun parseScamAnswer(answer: String): ScamOpinion? {
        val line = clean(answer).lineSequence().map { it.trim().replace("*", "").trimStart('-', ' ') }
            .map { it.removePrefix("Label:").trim() }
            .firstOrNull { it.startsWith("SCAM", true) || it.startsWith("SAFE", true) } ?: return null
        val scam = line.startsWith("SCAM", true)
        val reason = line.removePrefix("Label:").trim().drop(4).trimStart(':', ' ', '-', '—', '(').trim().trimEnd('.', ')')
            .replace(Regex("(?i)^reason\\b\\s*(reason:)?\\s*"), "") // "SCAM: reason **Reason:** phishing"
            .takeUnless { it.contains("short reason", true) }.orEmpty()
            .ifBlank { if (scam) "It reads like a scam" else "" }
        return ScamOpinion(scam, reason.replaceFirstChar { it.uppercase() })
    }
}
