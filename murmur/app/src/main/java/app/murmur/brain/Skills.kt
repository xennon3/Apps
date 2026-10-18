package app.murmur.brain

/**
 * Brain skills: short instruction packs that switch on when a question matches, the way
 * Claude's own skills are folders of instructions loaded for the right task. Built-in ones
 * ship with Murmur; users can write their own in Settings.
 */
data class BrainSkill(
    val id: String,
    val name: String,
    /** Words or phrases that switch it on. */
    val triggers: List<String>,
    val instructions: String,
    val builtIn: Boolean = true,
)

object Skills {
    val BUILT_IN = listOf(
        BrainSkill(
            "cooking", "Cooking",
            listOf("recipe", "cook", "bake", "dinner", "lunch", "breakfast", "meal", "ingredients", "what can i make"),
            "Suggest simple recipes: a short ingredient list, then numbered steps. Respect any diet in memory. " +
                "Offer a swap for anything uncommon. Keep it to what fits on a phone screen.",
        ),
        BrainSkill(
            "split", "Bill splitter",
            listOf("split", "tip", "bill", "owe", "per person", "each pay", "venmo"),
            "Use the calculate tool for every number; never do math in your head. Show what each person pays, " +
                "including tip if asked, rounded to cents.",
        ),
        BrainSkill(
            "translate", "Translator",
            listOf("translate", "in spanish", "in french", "in german", "in italian", "in japanese", "in chinese", "how do you say", "what does"),
            "Give the translation first on its own line, then a short pronunciation hint. Keep the tone of the original.",
        ),
        BrainSkill(
            "polish", "Message polisher",
            listOf("polish", "rewrite", "reword", "rephrase", "proofread", "fix my", "sound more", "more professional", "more formal", "nicer way"),
            "Return only the improved message first, ready to copy and send. Then one short line saying what you changed.",
        ),
        BrainSkill(
            "gifts", "Gift ideas",
            listOf("gift", "present", "birthday idea", "anniversary", "what should i get"),
            "Give 5 specific gift ideas at different prices, using anything memory says about the person. One line each.",
        ),
    )

    /** The skills that apply to this question. */
    fun match(question: String, skills: List<BrainSkill>): List<BrainSkill> {
        val q = " " + question.lowercase() + " "
        return skills.filter { s -> s.triggers.any { t -> t.isNotBlank() && q.contains(t.lowercase().trim()) } }.take(2)
    }

    // Worded as guidance, not as a named "skill": given a name, the model took it for a tool
    // and told the user "the gift ideas tool isn't working".
    fun block(active: List<BrainSkill>): String =
        if (active.isEmpty()) "" else "How to answer this kind of question:\n" + active.joinToString("\n") { "- ${it.instructions}" }
}
