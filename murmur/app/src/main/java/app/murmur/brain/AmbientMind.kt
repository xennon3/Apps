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
        /** Reminders it already set and what it already told the user: its short-term memory. */
        val done: List<String> = emptyList(),
        /** Texts that arrived in the last two hours, so a text seen while driving isn't forgotten. */
        val texts: List<String> = emptyList(),
        /** The sensor diary since the last thought, condensed by code with real times ("9:44-9:52 AM driving, 8 min"). */
        val timeline: List<String> = emptyList(),
        val lastThought: String? = null,
    )

    sealed interface Act {
        data class Notify(val text: String) : Act
        data class Journal(val text: String) : Act
        /** [whenText] is a clock time ("7:30 PM") or "in 20 minutes"; code works out the moment. */
        data class Remind(val whenText: String, val text: String) : Act
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
            "DO: one or more of: notify | <message to the user>; journal | <note>; remind | <clock time like 7:30 PM> | <message>; " +
            "flashlight | on or off; ringer | vibrate, normal or silent; ask_send | <who> | <text to send them>; nothing\n" +
            "Texts to other people are only ever ask_send, which waits for the user's OK. " +
            "You remember through the lists you're shown: never repeat a plan, reminder or message that is already there. " +
            "Write PLAN lines only for new plans; your open plans are kept for you. " +
            "Never guess: times and durations come only from the timeline, places only from 'At'. An unfamiliar place is " +
            "not home or work. Act on a goal only when the senses clearly show its condition (a work goal needs 'At: work')."

    fun situation(s: Situation): String = buildString {
        append("Time: ").append(s.time).append('\n')
        append("Senses:\n"); s.senses.forEach { append("- ").append(it).append('\n') }
        if (s.timeline.isNotEmpty()) {
            append(if (s.lastThought != null) "Timeline since your last thought (at ${s.lastThought}):\n" else "Timeline (last 30 minutes):\n")
            s.timeline.forEach { append("- ").append(it).append('\n') }
        }
        append("What just changed:\n"); (s.events.ifEmpty { listOf("Nothing new; a regular check-in.") }).forEach { append("- ").append(it).append('\n') }
        if (s.texts.isNotEmpty()) { append("Texts in the last two hours:\n"); s.texts.forEach { append("- ").append(it).append('\n') } }
        if (s.goals.isNotEmpty()) {
            append("The user's goals for you:\n")
            s.goals.forEach { g ->
                append("- ").append(g)
                // Exact times are kept by code (and alarms set in the Clock app); the model must not redo them.
                goalTime(g)?.let { append(" (Murmur's clock handles this at ").append(clock(it)).append("; do nothing about it)") }
                append('\n')
            }
        }
        if (s.plans.isNotEmpty()) { append("Your open plans (already kept for you):\n"); s.plans.forEach { append("- ").append(it).append('\n') } }
        if (s.done.isNotEmpty()) { append("Already done or scheduled by you (don't repeat):\n"); s.done.forEach { append("- ").append(it).append('\n') } }
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
            "Thursday 6:40 PM",
            listOf("Battery 52%, not charging", "At: home (since 6:05 PM)", "Moving: still"),
            listOf("Regular check-in"),
            emptyList(), listOf("when they get home: remind them to answer Quinn about the movie"), emptyList(),
            done = listOf("6:06 PM told you: Quinn asked if the movie is still on. Reply when you can."),
        )) to "OBSERVE: Home since 6:05, nothing new since the last check.\n" +
            "THOUGHT: I already reminded them about Quinn; nothing more to do.\n" +
            "DO: nothing",
        situation(Situation(
            "Friday 10:04 AM",
            listOf("Battery 80%, not charging", "At: an unfamiliar place (not home or work)", "Moving: still since 9:56 AM (8 min)", "Ringer: silent"),
            listOf("Stopped driving at 9:56 AM after 8 min, about 3.0 miles"),
            listOf("Keep my phone on vibrate while I'm at work"), emptyList(), emptyList(),
            timeline = listOf("9:40-9:48 AM still, 8 min", "9:48-9:56 AM driving or riding, 8 min, about 3.0 miles", "9:56 AM-now still, 8 min at an unfamiliar place (not home or work)"),
            lastThought = "9:40 AM",
        )) to "OBSERVE: Drove 8 minutes, about 3 miles, and has been still at an unfamiliar place for 8 minutes.\n" +
            "THOUGHT: This isn't work, so the vibrate-at-work goal doesn't apply; nothing needs doing.\n" +
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
            listOf("New text from Quinn: \"is the movie still on?\""),
            emptyList(), emptyList(), emptyList(),
        )) to "OBSERVE: Driving when Quinn asked if the movie is still on.\n" +
            "THOUGHT: They shouldn't text while driving; it can wait until they stop.\n" +
            "PLAN: when they stop driving | remind them to answer Quinn about the movie\n" +
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
                kind.startsWith("remind") && p.size >= 3 && p[2].isNotBlank() && Regex("\\d").containsMatchIn(p[1]) -> acts += Act.Remind(p[1], p[2].take(240))
                kind.startsWith("flashlight") -> acts += Act.Flashlight(p.getOrNull(1)?.contains("on", true) == true)
                kind.startsWith("ringer") -> p.getOrNull(1)?.lowercase()?.let { m -> listOf("vibrate", "normal", "silent").firstOrNull { m.contains(it) } }?.let { acts += Act.Ringer(it) }
                kind.startsWith("ask_send") && p.size >= 3 && p[2].isNotBlank() -> acts += Act.AskSend(p[1], p[2].take(300))
            }
        }
        return Thought(field(text, "OBSERVE"), field(text, "THOUGHT"), plans, acts.take(3))
    }

    /**
     * Plain code's veto: no flashlight unless light is actually part of what's going on, and no
     * notification or reminder that repeats a goal the clock already keeps (the model once fired a
     * "wake up at 8 AM" reminder at 1:06 AM).
     */
    fun allowed(t: Thought, situation: String, goals: List<String> = emptyList()): Thought {
        val lightMatters = Regex("(?i)flashlight|torch|dark|light").containsMatchIn(situation)
        val timed = goals.filter { goalTime(it) != null }.map { words(it) }
        fun repeatsGoal(text: String) = words(text).let { w -> timed.any { g -> (g intersect w).size >= 2 || (g intersect w).any { it in setOf("wake", "alarm", "pills", "medicine", "medication") } } }
        return t.copy(acts = t.acts.filter { a ->
            when (a) {
                is Act.Flashlight -> lightMatters
                is Act.Notify -> !repeatsGoal(a.text)
                is Act.Remind -> !repeatsGoal(a.text)
                else -> true
            }
        })
    }

    private fun similar(a: String, b: String): Boolean {
        val x = words(a); val y = words(b)
        if (x.isEmpty() || y.isEmpty()) return a.trim().equals(b.trim(), true)
        return (x intersect y).size.toDouble() / (x union y).size >= 0.5 || x.containsAll(y) || y.containsAll(x)
    }

    /**
     * Plans persist across thoughts: existing ones are kept, a new one is added only if it isn't
     * already there, and plans whose clock time passed more than 10 minutes ago are dropped.
     */
    fun mergePlans(existing: List<Pair<String, String>>, new: List<Pair<String, String>>, now: java.util.Calendar): Pair<List<Pair<String, String>>, List<Pair<String, String>>> {
        fun passed(whenText: String): Boolean {
            if (!Regex("(?i)\\d{1,2}(:\\d{2})? ?[ap]\\.?m|\\d{1,2}:\\d{2}").containsMatchIn(whenText)) return false
            val hm = app.murmur.brain.tools.PhoneMath.parseClock(whenText) ?: return false
            val nowMin = now.get(java.util.Calendar.HOUR_OF_DAY) * 60 + now.get(java.util.Calendar.MINUTE)
            val d = nowMin - (hm.first * 60 + hm.second)
            return d in 11..(12 * 60)
        }
        val kept = existing.filter { !passed(it.first) }.toMutableList()
        val added = new.filter { n -> !passed(n.first) && kept.none { similar(it.second, n.second) } }
        kept += added
        return kept.takeLast(5) to added
    }

    /**
     * Code's memory, in case the model forgets: drops actions that repeat what's pending or was said
     * recently. A proposed text that just echoes a text the user received ("are we still on?" back to
     * the person who asked it) becomes a reminder to reply instead.
     */
    fun notRepeated(
        acts: List<Act>, pendingReminders: List<String>, recentlySaid: List<String>,
        pendingAsks: List<String> = emptyList(), receivedTexts: List<String> = emptyList(),
    ): List<Act> = acts.map { a ->
        if (a is Act.AskSend && receivedTexts.any { similar(it, a.message) }) Act.Notify("Reply to ${a.who}: \"${a.message}\"") else a
    }.filter { a ->
        val earlier = pendingReminders + recentlySaid
        when (a) {
            is Act.Remind -> earlier.none { similar(it, a.text) }
            is Act.Notify -> earlier.none { similar(it, a.text) }
            is Act.AskSend -> pendingAsks.none { similar(it, a.message) } && recentlySaid.none { similar(it, a.message) }
            else -> true
        }
    }.distinct()

    private val stop = setOf("remind", "me", "to", "my", "the", "at", "every", "day", "daily", "time", "your", "you", "and", "for", "a", "am", "pm", "up")
    private fun words(s: String) = s.lowercase().split(Regex("[^a-z]+")).filter { it.length >= 3 && it !in stop }.toSet()

    fun clock(hm: Pair<Int, Int>) = "%d:%02d %s".format(if (hm.first % 12 == 0) 12 else hm.first % 12, hm.second, if (hm.first < 12) "AM" else "PM")

    /**
     * When a reminder is due: "7:30 PM" is the next 7:30 PM, "in 20 minutes" or "45 min" is from now.
     * Small models are bad at clock arithmetic, so this is code. Null if it can't tell.
     */
    fun remindAt(whenText: String, now: java.util.Calendar): Long? {
        val w = whenText.lowercase()
        Regex("(\\d+)\\s*(h|hour|hr|m|min)").find(w)?.takeIf { "in " in w || !Regex("[ap]\\.?m|:").containsMatchIn(w) }?.let { m ->
            val n = m.groupValues[1].toLong()
            return now.timeInMillis + n * (if (m.groupValues[2].startsWith("h")) 60 else 1) * 60_000L
        }
        val hm = app.murmur.brain.tools.PhoneMath.parseClock(w) ?: return null
        val c = (now.clone() as java.util.Calendar).apply { set(java.util.Calendar.HOUR_OF_DAY, hm.first); set(java.util.Calendar.MINUTE, hm.second); set(java.util.Calendar.SECOND, 0) }
        if (c.timeInMillis <= now.timeInMillis - 60_000L) c.add(java.util.Calendar.DAY_OF_YEAR, 1)
        return c.timeInMillis
    }

    /** "wake me up at 8:00 AM", "set an alarm for 6:45 every weekday": goals that deserve a real Clock alarm. */
    fun isAlarmGoal(goal: String) = Regex("(?i)\\b(wake|alarm|get up)\\b").containsMatchIn(goal) && goalTime(goal) != null

    /** "Remind me to take my pills at 9 PM every day" → 21:00. Exact clock goals are kept by code, not the model. */
    fun goalTime(goal: String): Pair<Int, Int>? {
        val m = Regex("(?i)\\b(?:at|for) (\\d{1,2}(?::\\d{2})? ?(?:a\\.?m\\.?|p\\.?m\\.?)|\\d{1,2}:\\d{2})").find(goal) ?: return null
        return app.murmur.brain.tools.PhoneMath.parseClock(m.groupValues[1])
    }
}
