package app.murmur.brain

import android.content.Context
import org.json.JSONArray

/**
 * What the user taught Murmur Brain. Small models learn best from examples, so a 👍 answer or a
 * corrected 👎 answer becomes a worked example that's shown to the model the next time a
 * similar question comes up.
 */
object Lessons {
    data class Lesson(val question: String, val answer: String)

    private fun prefs(c: Context) = c.getSharedPreferences("brain_lessons", Context.MODE_PRIVATE)

    fun all(c: Context): List<Lesson> = runCatching {
        val a = JSONArray(prefs(c).getString("l", "[]"))
        List(a.length()) { i -> a.getJSONArray(i).let { Lesson(it.getString(0), it.getString(1)) } }
    }.getOrDefault(emptyList())

    private fun save(c: Context, list: List<Lesson>) =
        prefs(c).edit().putString("l", JSONArray(list.takeLast(40).map { JSONArray(listOf(it.question, it.answer)) }).toString()).apply()

    fun add(c: Context, question: String, answer: String) {
        if (question.isBlank() || answer.isBlank()) return
        save(c, all(c).filterNot { it.question.equals(question, true) } + Lesson(question.trim(), answer.trim().take(1200)))
    }

    fun remove(c: Context, l: Lesson) = save(c, all(c) - l)

    private val stop = setOf("what", "when", "where", "which", "who", "how", "the", "and", "for", "are", "you", "your", "can", "does", "did",
        "with", "about", "this", "that", "have", "has", "was", "were", "will", "would", "should", "could", "tell", "please", "give", "some")

    fun words(s: String) = s.lowercase().split(Regex("[^a-z0-9']+")).filter { it.length >= 3 && it !in stop }.toSet()

    /** Up to [n] lessons about the same thing as [question]: they share most of their meaningful words. */
    fun relevant(c: Context, question: String, n: Int = 2): List<Pair<String, String>> = pick(all(c), question, n)

    fun pick(lessons: List<Lesson>, question: String, n: Int = 2): List<Pair<String, String>> {
        val q = words(question)
        if (q.isEmpty()) return emptyList()
        return lessons.map { l -> l to words(l.question).let { w -> (w intersect q).size.toFloat() / (w union q).size.coerceAtLeast(1) } }
            .filter { it.second >= 0.34f }
            .sortedByDescending { it.second }
            .take(n)
            .map { it.first.question to it.first.answer }
    }
}

/**
 * How the user really replies: each time they send a suggested reply (as is or edited), that
 * moment becomes an example for the next suggestions, with their actual words first.
 */
object ReplyStyle {
    private fun prefs(c: Context) = c.getSharedPreferences("reply_style", Context.MODE_PRIVATE)

    fun examples(c: Context): List<Pair<String, String>> = runCatching {
        val a = JSONArray(prefs(c).getString("e", "[]"))
        List(a.length()) { i -> a.getJSONArray(i).let { it.getString(0) to it.getString(1) } }
    }.getOrDefault(emptyList())

    /** [transcript] is what the model saw; [sent] what the user actually sent; [others] the suggestions they didn't pick. */
    fun learn(c: Context, transcript: String, sent: String, others: List<String>) {
        val answer = (listOf(sent.replace('\n', ' ').trim()) + others.take(2)).joinToString("\n")
        val short = transcript.lines().takeLast(3).joinToString("\n")
        val list = (examples(c).filterNot { it.first == short } + (short to answer)).takeLast(3)
        prefs(c).edit().putString("e", JSONArray(list.map { JSONArray(listOf(it.first, it.second)) }).toString()).apply()
    }
}
