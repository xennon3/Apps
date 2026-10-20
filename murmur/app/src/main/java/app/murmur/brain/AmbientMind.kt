package app.murmur.brain

/**
 * Ambient mode's thinking: what the Brain is shown when something changes, and how its answer
 * becomes actions. Plain Kotlin so the lab can run it against the real model.
 *
 * The model writes a few labelled lines; plain code reads them and does only what's allowed.
 * Anything that leaves the phone (a text) comes back as an ASK and waits for the user's tap.
 */
object AmbientMind {

    /** What the phone senses right now, already in words. */
    data class Situation(
        val time: String,
        val senses: List<String>,
        val events: List<String>,
        val goals: List<String>,
        val plans: List<String>,
        val journal: List<String>,
        val memory: List<String> = emptyList(),
    )

    sealed interface Act {
        data class Notify(val text: String) : Act
        data class Journal(val text: String) : Act
        data class Remind(val minutes: Int, val text: String) : Act
        data class Flashlight(val on: Boolean) : Act
        data class Ringer(val mode: String) : Act
        /** Needs the user's tap before it happens. */
        data class AskSend(val who: String, val message: String) : Act
    }

    data class Thought(
        val observe: String,
        val thought: String,
        val plans: List<Pair<String, String>>,
        val acts: List<Act>,
    )

    const val SYSTEM =
        "You are Murmur, an AI living in the user's phone. The phone is your body: its sensors are how you perceive " +
            "the world. You wake up when something changes, look at what you sense, and decide whether to help. " +
            "Be useful and quiet: most of the time the right action is nothing or a short journal note. Only notify " +
            "when it truly helps the user right now (a problem coming, a goal they set, something they'd want to know); notify " +
            "for things that matter now, remind only for later. Act on the user's goals directly (for example the ringer). " +
            "Reply in exactly these lines:\n" +
            "OBSERVE: what is going on, in one sentence\n" +
            "THOUGHT: what it means for the user, in one or two sentences\n" +
            "PLAN: <when> | <what you'll do then>   (zero to two lines)\n" +
            "DO: one or more of: notify | <message to the user>; journal | <note>; remind | <minutes from now> | <message>; " +
            "flashlight | on or off; ringer | vibrate, normal or silent; ask_send | <who> | <text to send them>; nothing\n" +
            "Texts to other people are only ever ask_send, which waits for the user's OK."

    fun situation(s: Situation): String = buildString {
        append("Time: ").append(s.time).append('\n')
        append("Senses:\n"); s.senses.forEach { append("- ").append(it).append('\n') }
        append("What just changed:\n"); (s.events.ifEmpty { listOf("Nothing new; a regular check-in.") }).forEach { append("- ").append(it).append('\n') }
        if (s.goals.isNotEmpty()) { append("The user's goals for you:\n"); s.goals.forEach { append("- ").append(it).append('\n') } }
        if (s.plans.isNotEmpty()) { append("Your open plans:\n"); s.plans.forEach { append("- ").append(it).append('\n') } }
        if (s.memory.isNotEmpty()) { append("What you know about the user:\n"); s.memory.takeLast(10).forEach { append("- ").append(it).append('\n') } }
        if (s.journal.isNotEmpty()) { append("Your recent journal:\n"); s.journal.takeLast(6).forEach { append("- ").append(it).append('\n') } }
    }.trim()

    /** Two worked examples: one where it helps, one where it stays quiet. */
    val EXAMPLES = listOf(
        situation(Situation(
            "Tuesday 5:05 PM",
            listOf("Battery 21%, not charging, using about 9% an hour", "At: work (since 9:02 AM)", "Moving: still", "Screen: off"),
            listOf("Battery dropped below 25%"),
            emptyList(), emptyList(), listOf("9:02 AM arrived at work"),
        )) to "OBSERVE: Battery is at 21% and draining fast, still at work near the usual leaving time.\n" +
            "THOUGHT: At this rate it runs out around 7:20 PM, before they usually get home to charge.\n" +
            "PLAN: 5:30 PM | check the battery again before they leave\n" +
            "DO: notify | Battery 21% and dropping fast. Charge before you leave work, or it may die around 7:20.",
        situation(Situation(
            "Saturday 2:14 PM",
            listOf("Battery 68%, not charging", "At: home (since 11:40 AM)", "Moving: still", "Screen: on"),
            emptyList(), emptyList(), emptyList(), listOf("11:40 AM arrived home"),
        )) to "OBSERVE: A quiet Saturday afternoon at home, phone in use.\n" +
            "THOUGHT: Nothing needs attention.\n" +
            "DO: nothing",
        situation(Situation(
            "Monday 8:58 AM",
            listOf("At: work (just arrived)", "Moving: still", "Ringer: normal", "Battery 88%"),
            listOf("Arrived at work"),
            listOf("Keep my phone on vibrate while I'm at work"), emptyList(), emptyList(),
        )) to "OBSERVE: Just arrived at work with the ringer on.\n" +
            "THOUGHT: Their goal is vibrate at work, so switch it now.\n" +
            "DO: ringer | vibrate; journal | Arrived at work 8:58 AM, set the phone to vibrate.",
        situation(Situation(
            "Wednesday 12:20 PM",
            listOf("Moving: in a vehicle, 42 mph", "Battery 70%", "Screen: off"),
            listOf("New text from Jake: \"are we still on for tonight?\""),
            emptyList(), emptyList(), emptyList(),
        )) to "OBSERVE: Driving when Jake asked if tonight's plan is still on.\n" +
            "THOUGHT: They shouldn't text while driving; it can wait until they stop.\n" +
            "PLAN: when they stop driving | remind them to answer Jake about tonight\n" +
            "DO: nothing",
    )

    private fun field(text: String, name: String): String =
        text.lineSequence().map { it.trim().trimStart('*', '-', ' ') }.firstOrNull { it.startsWith("$name:", true) }
            ?.substringAfter(':')?.trim().orEmpty()

    fun parse(answer: String): Thought {
        val text = BrainPrompts.clean(answer).replace("**", "")
        val lines = text.lines().map { it.trim().trimStart('*', '-', ' ') }
        val plans = lines.filter { it.startsWith("PLAN:", true) }.mapNotNull { l ->
            val p = l.substringAfter(':').split('|').map { it.trim() }
            if (p.size >= 2 && p[1].isNotBlank() && !p[1].contains("maintain", true) && p[0].uppercase() != "N/A") p[0] to p[1] else null
        }.take(2)
        val acts = ArrayList<Act>()
        // "DO:" may hold several actions separated by ';', or come as several DO lines.
        lines.filter { it.startsWith("DO:", true) }.flatMap { it.substringAfter(':').split(';') }.forEach { raw ->
            val p = raw.split('|').map { it.trim() }
            val kind = p[0].lowercase().replace(' ', '_')
            when {
                kind.startsWith("notify") && p.getOrNull(1)?.isNotBlank() == true -> acts += Act.Notify(p[1].take(240))
                kind.startsWith("journal") && p.getOrNull(1)?.isNotBlank() == true -> acts += Act.Journal(p[1].take(240))
                // A "reminder" due in a minute or two is really a notification now.
                kind.startsWith("remind") && p.size >= 3 -> Regex("\\d+").find(p[1])?.value?.toIntOrNull()?.takeIf { it in 0..24 * 60 }?.let {
                    acts += if (it <= 2) Act.Notify(p[2].take(240)) else Act.Remind(it, p[2].take(240))
                }
                kind.startsWith("flashlight") -> acts += Act.Flashlight(p.getOrNull(1)?.contains("on", true) == true)
                kind.startsWith("ringer") -> p.getOrNull(1)?.lowercase()?.let { m -> listOf("vibrate", "normal", "silent").firstOrNull { m.contains(it) } }?.let { acts += Act.Ringer(it) }
                kind.startsWith("ask_send") && p.size >= 3 && p[2].isNotBlank() -> acts += Act.AskSend(p[1], p[2].take(300))
            }
        }
        return Thought(field(text, "OBSERVE"), field(text, "THOUGHT"), plans, acts.take(3))
    }

    /** Plain code's veto: no flashlight unless light or the flashlight is actually part of what's going on. */
    fun allowed(t: Thought, situation: String): Thought {
        val lightMatters = Regex("(?i)flashlight|torch|dark|light").containsMatchIn(situation)
        return t.copy(acts = t.acts.filter { it !is Act.Flashlight || lightMatters })
    }

    /** "Remind me to take my pills at 9 PM every day" → 21:00. Exact clock goals are kept by code, not the model. */
    fun goalTime(goal: String): Pair<Int, Int>? {
        val m = Regex("(?i)\\bat (\\d{1,2}(?::\\d{2})? ?(?:a\\.?m\\.?|p\\.?m\\.?)|\\d{1,2}:\\d{2})").find(goal) ?: return null
        return app.murmur.brain.tools.PhoneMath.parseClock(m.groupValues[1])
    }
}
