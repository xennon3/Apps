package app.murmur.brain

import android.content.Context
import android.text.format.DateFormat
import app.murmur.MurmurApp
import app.murmur.data.Phone
import app.murmur.data.SmsRepository
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject

/**
 * The Brain tab: a private chat with the on-device model that can also answer questions
 * about the user's texts ("what's the gate code Jen sent?"). Kept on the phone in Murmur's
 * own storage; runs in the app's scope so an answer keeps writing if you switch tabs.
 */
object BrainChat {

    data class Source(val threadId: Long, val label: String)

    data class Turn(
        val fromUser: Boolean,
        val text: String,
        /** Words Murmur searched the user's texts for, shown under the answer. */
        val searched: List<String> = emptyList(),
        val sources: List<Source> = emptyList(),
        val writing: Boolean = false,
    )

    enum class Step { IDLE, DECIDING, SEARCHING, WRITING }

    private val _turns = MutableStateFlow<List<Turn>>(emptyList())
    val turns: StateFlow<List<Turn>> = _turns

    private val _step = MutableStateFlow(Step.IDLE)
    val step: StateFlow<Step> = _step

    private var loaded = false
    private var job: Job? = null

    private fun prefs(c: Context) = c.getSharedPreferences("brain_chat", Context.MODE_PRIVATE)

    fun load(context: Context) {
        if (loaded) return
        loaded = true
        val raw = prefs(context).getString("turns", null) ?: return
        _turns.value = runCatching {
            val a = JSONArray(raw)
            List(a.length()) { i ->
                val o = a.getJSONObject(i)
                Turn(
                    o.getBoolean("u"), o.getString("t"),
                    o.optJSONArray("q")?.let { q -> List(q.length()) { q.getString(it) } }.orEmpty(),
                    o.optJSONArray("s")?.let { s -> List(s.length()) { s.getJSONObject(it).let { x -> Source(x.getLong("id"), x.getString("l")) } } }.orEmpty(),
                )
            }
        }.getOrDefault(emptyList())
    }

    private fun save(context: Context) {
        val a = JSONArray()
        for (t in _turns.value.filter { !it.writing }.takeLast(60)) {
            a.put(JSONObject().apply {
                put("u", t.fromUser); put("t", t.text)
                if (t.searched.isNotEmpty()) put("q", JSONArray(t.searched))
                if (t.sources.isNotEmpty()) put("s", JSONArray(t.sources.map { JSONObject().put("id", it.threadId).put("l", it.label) }))
            })
        }
        prefs(context).edit().putString("turns", a.toString()).apply()
    }

    fun clear(context: Context) {
        job?.cancel()
        _turns.value = emptyList()
        _step.value = Step.IDLE
        prefs(context).edit().remove("turns").apply()
    }

    fun send(context: Context, question: String) {
        val c = context.applicationContext
        val q = question.trim()
        if (q.isEmpty() || _step.value != Step.IDLE) return
        // Earlier turns go to the model as chat history (the last few, to stay quick).
        val history = _turns.value.filter { !it.writing }.zipWithNext()
            .filter { (a, b) -> a.fromUser && !b.fromUser }
            .map { (a, b) -> a.text to b.text }
            .takeLast(4)
        _turns.value = _turns.value + Turn(true, q)
        job = MurmurApp.scope.launch {
            try {
                _step.value = Step.DECIDING
                val repo = SmsRepository(c)
                val titles = runCatching { repo.conversations() }.getOrDefault(emptyList()).associate { it.threadId to it.title }
                val useTexts = BrainPrompts.mentionsTexts(q, titles.values) || Brain.needsMessages(c, q)
                var prompt = q
                var searched = emptyList<String>()
                var sources = emptyList<Source>()
                if (useTexts) {
                    _step.value = Step.SEARCHING
                    searched = Brain.searchTerms(c, q)
                    val hits = repo.searchByWords(searched, titles, limit = 10)
                    val lines = hits.map { h ->
                        val who = titles[h.threadId]?.takeIf { !it.contains(',') }
                            ?: repo.contact(h.address).name ?: Phone.pretty(h.address)
                        "[" + DateFormat.format("EEE MMM d", h.date) + ", " + who + "] " + h.body.replace('\n', ' ').take(300)
                    }
                    prompt = BrainPrompts.withMessages(q, lines)
                    sources = hits.distinctBy { it.threadId }.take(3).map { h ->
                        Source(h.threadId, (titles[h.threadId] ?: Phone.pretty(h.address)) + " · " + DateFormat.format("MMM d", h.date))
                    }
                }
                _step.value = Step.WRITING
                _turns.value = _turns.value + Turn(false, "", searched, sources, writing = true)
                val answer = Brain.chat(c, history, prompt, useTexts) { partial -> replaceLast(partial, searched, sources, writing = true) }
                replaceLast(
                    answer?.takeIf { it.isNotBlank() } ?: "Sorry, I couldn't think of an answer. Murmur Brain may still be loading. Try again in a moment.",
                    searched, if (answer.isNullOrBlank()) emptyList() else sources, writing = false,
                )
                save(c)
            } finally {
                _step.value = Step.IDLE
                // If the job was cancelled mid-answer, don't leave a half-written turn behind.
                if (_turns.value.lastOrNull()?.writing == true) replaceLast(_turns.value.last().text, emptyList(), emptyList(), false)
            }
        }
    }

    private fun replaceLast(text: String, searched: List<String>, sources: List<Source>, writing: Boolean) {
        val list = _turns.value
        if (list.isEmpty() || list.last().fromUser) return
        _turns.value = list.dropLast(1) + Turn(false, text, searched, sources, writing)
    }
}
