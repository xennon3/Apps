package dev.llmkeyboard

/** Small instruction-tuned models need tight, single-purpose prompts. Uses the Gemma chat template. */
object Prompts {
    private fun chat(instruction: String) =
        "<start_of_turn>user\n$instruction<end_of_turn>\n<start_of_turn>model\n"

    fun continuation(textBefore: String) = chat(
        "Continue this text with the next 3 to 10 words. Output ONLY the continuation.\n\n$textBefore"
    )

    fun translateWord(sentence: String, word: String, language: String) = chat(
        "Sentence so far: $sentence\nTranslate only the word \"$word\" into $language so it fits the sentence. " +
            "Reply with just that word."
    )

    fun translate(text: String, language: String) = chat(
        "Translate to $language. Output ONLY the translation.\n\n$text"
    )

    fun simplify(text: String) = chat(
        "Rewrite in simpler, plainer words and shorter sentences, same meaning. Output ONLY the rewrite.\n\n$text"
    )

    fun fix(text: String) = chat(
        "Fix spelling and grammar, keep the meaning and voice. Output ONLY the corrected text.\n\n$text"
    )
}
