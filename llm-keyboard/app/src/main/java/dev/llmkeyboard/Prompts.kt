package dev.llmkeyboard

/** Small instruction-tuned models need tight, single-purpose prompts. Uses the Gemma chat template. */
object Prompts {
    private fun chat(instruction: String) =
        "<start_of_turn>user\n$instruction<end_of_turn>\n<start_of_turn>model\n"

    fun continuation(textBefore: String) = chat(
        "Continue this text with the next 3 to 10 words. Output ONLY the continuation.\n\n$textBefore"
    )

    /** One worked example per language: small models translate far better when shown the format. */
    private class Demo(val sentence: String, val result: String, val word: String, val wordResult: String)

    private val demos = mapOf(
        "Spanish" to Demo("I would like a big coffee", "Quisiera un café grande", "big", "grande"),
        "French" to Demo("I would like a big coffee", "Je voudrais un grand café", "big", "grand"),
        "German" to Demo("I would like a big coffee", "Ich möchte einen großen Kaffee", "big", "großen"),
        "Portuguese" to Demo("I would like a big coffee", "Eu gostaria de um café grande", "big", "grande"),
        "Italian" to Demo("I would like a big coffee", "Vorrei un caffè grande", "big", "grande"),
        "Japanese" to Demo("I would like a big coffee", "大きなコーヒーをください", "big", "大きな"),
        "English" to Demo("Je voudrais un grand café", "I would like a big coffee", "grand", "big"),
    )

    fun translate(text: String, language: String): String {
        val d = demos[language] ?: return chat("Translate to $language. Output ONLY the translation.\n\n$text")
        val from = if (language == "English") "Text" else "English"
        return chat(
            "Translate the text to $language. Output ONLY the translation.\n\n" +
                "$from: ${d.sentence}\n$language: ${d.result}\n\n$from: $text\n$language:"
        )
    }

    fun translateWord(sentence: String, word: String, language: String): String {
        val d = demos[language] ?: return chat("Translate the word \"$word\" into $language. Reply with just that word.")
        return chat(
            "Translate one word to $language, as it fits in its sentence. Output ONLY that word.\n\n" +
                "Sentence: ${d.sentence}\nWord: ${d.word}\n$language: ${d.wordResult}\n\n" +
                "Sentence: $sentence\nWord: $word\n$language:"
        )
    }

    fun simplify(text: String) = chat(
        "Rewrite in simpler, plainer words and shorter sentences, same meaning. Output ONLY the rewrite.\n\n$text"
    )

    fun fix(text: String) = chat(
        "Fix spelling and grammar, keep the meaning and voice. Output ONLY the corrected text.\n\n$text"
    )
}
