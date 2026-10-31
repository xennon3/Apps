package dev.llmkeyboard

/** Small instruction-tuned models need tight, single-purpose prompts. Uses the Gemma chat template. */
object Prompts {
    private fun chat(instruction: String) =
        "<start_of_turn>user\n$instruction<end_of_turn>\n<start_of_turn>model\n"

    fun continuation(textBefore: String) = chat(
        "Continue the text below with the most likely next 3 to 12 words, finishing the sentence. " +
            "Output ONLY the continuation, no quotes, do not repeat the text.\n\nText: $textBefore"
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
