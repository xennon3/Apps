package app.murmur.brain

import android.content.Context
import android.text.format.DateFormat
import app.murmur.MurmurApp
import app.murmur.data.Phone
import app.murmur.data.Prefs
import app.murmur.data.SmsRepository
import app.murmur.sms.Outbox
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject

/**
 * The Brain tab: a private chat with the on-device model. It can answer from the user's texts
 * and (if allowed) calendar, calls and contacts, remember things it's told, and draft texts to
 * send, always behind a confirmation card. Runs in the app's scope so an answer keeps writing
 * if you switch tabs.
 */
object BrainChat {

    data class Source(val threadId: Long, val label: String)

    enum class SendState { WAITING, SENT, SCHEDULED, CANCELLED }

    /** A text the Brain drafted; nothing goes out until the user taps Send. */
    data class SendCard(
        val name: String, val number: String, val message: String,
        val state: SendState = SendState.WAITING, val at: Long = 0,
        /** Other people the name could mean, for a quick switch. */
        val alternatives: List<Pair<String, String>> = emptyList(),
    )

    data class Turn(
        val fromUser: Boolean,
        val text: String,
        /** Words Murmur searched the user's texts for, shown under the answer. */
        val searched: List<String> = emptyList(),
        val sources: List<Source> = emptyList(),
        val writing: Boolean = false,
        /** What else it looked at, e.g. "Calendar", "Memory". */
        val used: List<String> = emptyList(),
        val send: SendCard? = null,
    )

    enum class Step { IDLE, DECIDING, SEARCHING, WRITING }

    private val _turns = MutableStateFlow<List<Turn>>(emptyList())
    val turns: StateFlow<List<Turn>> = _turns

    private val _step = MutableStateFlow(Step.IDLE)
    val step: StateFlow<Step> = _step

    private var loaded = false
    private var job: Job? = null

    private fun prefs(c: Context) = c.getSharedPreferences("brain_chat", Context.MODE_PRIVATE)

    // ================================================================ storage

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
                    used = o.optJSONArray("w")?.let { w -> List(w.length()) { w.getString(it) } }.orEmpty(),
                    send = o.optJSONObject("send")?.let { x ->
                        SendCard(x.getString("n"), x.getString("p"), x.getString("m"), SendState.valueOf(x.getString("st")), x.optLong("at"))
                            .let { if (it.state == SendState.WAITING) it.copy(state = SendState.CANCELLED) else it } // don't resurrect old drafts
                    },
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
                if (t.used.isNotEmpty()) put("w", JSONArray(t.used))
                t.send?.let { put("send", JSONObject().put("n", it.name).put("p", it.number).put("m", it.message).put("st", it.state.name).put("at", it.at)) }
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

    // ================================================================ asking

    fun send(context: Context, question: String) {
        val c = context.applicationContext
        val q = question.trim()
        if (q.isEmpty() || _step.value != Step.IDLE) return
        val history = _turns.value.filter { !it.writing && it.send == null }.zipWithNext()
            .filter { (a, b) -> a.fromUser && !b.fromUser }
            .map { (a, b) -> a.text to b.text }
            .takeLast(4)
        _turns.value = _turns.value + Turn(true, q)

        // Memory commands are handled by plain code: no model, no guessing.
        BrainPrompts.rememberCommand(q)?.let { fact ->
            BrainMemory.add(c, fact)
            reply(c, "Got it. I'll remember: \"$fact\". You can see and edit what I remember in Settings.", used = listOf("Memory"))
            return
        }
        BrainPrompts.forgetCommand(q)?.let { what ->
            val gone = BrainMemory.forget(c, what)
            reply(c, if (gone.isEmpty()) "I didn't have anything about \"$what\" to forget." else "Forgotten: " + gone.joinToString("; ") { "\"$it\"" }, used = listOf("Memory"))
            return
        }

        job = MurmurApp.scope.launch {
            try {
                if (BrainPrompts.isSendRequest(q)) draftText(c, q) else answer(c, q, history)
            } finally {
                _step.value = Step.IDLE
                if (_turns.value.lastOrNull()?.writing == true) replaceLast(_turns.value.last().copy(writing = false))
            }
        }
    }

    private suspend fun answer(c: Context, q: String, history: List<Pair<String, String>>) {
        _step.value = Step.DECIDING
        val repo = SmsRepository(c)
        val titles = runCatching { repo.conversations() }.getOrDefault(emptyList()).associate { it.threadId to it.title }
        val facts = BrainMemory.facts(c)
        val blocks = ArrayList<String>()
        val used = ArrayList<String>()
        if (facts.isNotEmpty()) blocks += BrainPrompts.memoryBlock(facts)
        if (BrainPrompts.asksCalendar(q)) PhoneData.calendar(c).takeIf { it.isNotEmpty() }?.let { blocks += it; used += "Calendar" }
        if (BrainPrompts.asksCalls(q)) PhoneData.calls(c).takeIf { it.isNotEmpty() }?.let { blocks += it; used += "Call history" }
        if (BrainPrompts.asksContact(q)) PhoneData.contacts(c, q, titles.values).takeIf { it.isNotEmpty() }?.let { blocks += it; used += "Contacts" }

        val useTexts = used.isEmpty() && (BrainPrompts.mentionsTexts(q, titles.values) || Brain.needsMessages(c, q))
        var prompt = q
        var searched = emptyList<String>()
        var sources = emptyList<Source>()
        if (useTexts) {
            _step.value = Step.SEARCHING
            searched = Brain.searchTerms(c, q)
            val hits = repo.searchByWords(searched, titles, limit = 10)
            val lines = hits.map { h ->
                val who = titles[h.threadId]?.takeIf { !it.contains(',') } ?: repo.contact(h.address).name ?: Phone.pretty(h.address)
                "[" + DateFormat.format("EEE MMM d", h.date) + ", " + who + "] " + h.body.replace('\n', ' ').take(300)
            }
            prompt = BrainPrompts.withMessages(q, lines)
            sources = hits.distinctBy { it.threadId }.take(3).map { h ->
                Source(h.threadId, (titles[h.threadId] ?: Phone.pretty(h.address)) + " · " + DateFormat.format("MMM d", h.date))
            }
        }
        val base = if (useTexts || used.isNotEmpty()) BrainPrompts.CHAT_MESSAGES else BrainPrompts.CHAT_GENERAL
        val system = BrainPrompts.system(base, BrainMemory.persona(c), blocks)
        if (facts.isNotEmpty()) used += "Memory"

        _step.value = Step.WRITING
        val start = Turn(false, "", searched, sources, writing = true, used = used)
        _turns.value = _turns.value + start
        val text = Brain.chat(c, history, prompt, system) { partial -> replaceLast(start.copy(text = partial)) }
        replaceLast(
            if (text.isNullOrBlank()) start.copy(text = "Sorry, I couldn't think of an answer. Murmur Brain may still be loading. Try again in a moment.", sources = emptyList(), writing = false)
            else start.copy(text = text, writing = false),
        )
        save(c)
    }

    /** "text mom I'm running late" → a card to confirm, edit, schedule or cancel. */
    private suspend fun draftText(c: Context, q: String) {
        _step.value = Step.DECIDING
        val draft = Brain.draftSend(c, q)
        if (draft == null) {
            reply(c, "I couldn't tell who to text or what to say. Try \"text Mom that I'm running late\".")
            return
        }
        var message = draft.message
        val used = ArrayList<String>()
        if (BrainPrompts.wantsLocation(q)) {
            val link = PhoneData.locationLink(c)
            if (link == null) {
                reply(c, if (BrainAccess.on(c, BrainAccess.Source.LOCATION)) "I couldn't get your location just now. Is location turned on?"
                else "To send where you are, turn on Location for Murmur Brain in Settings.")
                return
            }
            message = "I'm here: $link"
            used += "Location"
        }
        val people = Recipients.find(c, draft.who)
        if (people.isEmpty()) {
            reply(c, "I couldn't find \"${draft.who}\" in your contacts. Try their name as it's saved, or tell me who they are: \"remember my landlord is Sam Lee\".")
            return
        }
        val whoWords = draft.who.lowercase().removePrefix("my ").removePrefix("the ").trim()
        if (BrainMemory.facts(c).any { it.contains(whoWords, true) }) used += "Memory"
        val (name, number) = people.first()
        _turns.value = _turns.value + Turn(
            false, "Here's the text. Nothing goes out until you tap Send.", used = used,
            send = SendCard(name, number, message, alternatives = people.drop(1)),
        )
        save(c)
    }

    private fun reply(c: Context, text: String, used: List<String> = emptyList()) {
        _turns.value = _turns.value + Turn(false, text, used = used)
        save(c)
    }

    private fun replaceLast(turn: Turn) {
        val list = _turns.value
        if (list.isEmpty() || list.last().fromUser) return
        _turns.value = list.dropLast(1) + turn
    }

    // ================================================================ the send card

    private fun updateCard(index: Int, c: Context, change: (SendCard) -> SendCard) {
        val list = _turns.value.toMutableList()
        val t = list.getOrNull(index) ?: return
        val card = t.send ?: return
        list[index] = t.copy(send = change(card))
        _turns.value = list
        save(c)
    }

    fun editCard(c: Context, index: Int, message: String) = updateCard(index, c) { it.copy(message = message) }

    fun switchRecipient(c: Context, index: Int, name: String, number: String) = updateCard(index, c) { card ->
        card.copy(name = name, number = number, alternatives = (card.alternatives - (name to number)) + (card.name to card.number))
    }

    /** Sends through the normal outbox, so the undo window and SIM choice still apply. */
    fun confirm(c: Context, index: Int, at: Long? = null) {
        val card = _turns.value.getOrNull(index)?.send ?: return
        if (card.state != SendState.WAITING || card.message.isBlank()) return
        val repo = SmsRepository(c)
        val threadId = runCatching { repo.threadIdFor(Phone.digits(card.number).ifEmpty { card.number }) }.getOrNull() ?: return
        val sim = Prefs(c).sim(threadId) ?: -1
        if (at == null) Outbox.send(c, threadId, card.number, card.message.trim(), subId = sim)
        else Outbox.schedule(c, threadId, card.number, card.message.trim(), at, subId = sim)
        updateCard(index, c) { it.copy(state = if (at == null) SendState.SENT else SendState.SCHEDULED, at = at ?: System.currentTimeMillis()) }
    }

    fun cancelCard(c: Context, index: Int) = updateCard(index, c) { it.copy(state = SendState.CANCELLED) }
}

/** Works out who "mom", "my sister" or "the landlord" means. */
object Recipients {
    private val filler = setOf("my", "the", "our", "a", "to")

    /** Up to three (name, number) matches, best first. */
    fun find(c: Context, who: String): List<Pair<String, String>> {
        val repo = SmsRepository(c)
        val words = who.lowercase().split(Regex("\\s+")).filter { it !in filler && it.isNotBlank() }
        if (words.isEmpty()) return emptyList()
        val queries = LinkedHashSet<String>()
        // Memory first: "My sister is Priya" turns "my sister" into "Priya".
        for (fact in BrainMemory.facts(c)) {
            val f = fact.lowercase()
            if (words.all { f.contains(it) }) {
                Regex("\\b[A-Z][a-z]+(?: [A-Z][a-z]+)?").findAll(fact).map { it.value }
                    .filter { n -> n.lowercase() !in words && n !in setOf("My", "I", "The", "Our") }
                    .forEach { queries += it }
            }
        }
        queries += words.joinToString(" ")
        val out = LinkedHashMap<String, String>()
        for (q in queries) {
            for (e in repo.searchContacts(q)) out.putIfAbsent(Phone.digits(e.number), e.name)
            if (out.size >= 3) break
        }
        if (out.isEmpty()) {
            // Not a contact, but maybe a conversation title ("Landlord" saved only by number won't match; a name will).
            runCatching { repo.conversations() }.getOrDefault(emptyList())
                .filter { conv -> conv.addresses.size == 1 && words.all { conv.title.lowercase().contains(it) } }
                .take(3).forEach { out.putIfAbsent(Phone.digits(it.addresses[0]), it.title) }
        }
        return out.entries.take(3).map { (number, name) -> name to number }
    }
}
