package dev.llmkeyboard

import android.inputmethodservice.InputMethodService
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import java.io.File
import java.util.concurrent.Executors

class LlmKeyboardService : InputMethodService() {
    private val worker = Executors.newSingleThreadExecutor()
    private val ui = Handler(Looper.getMainLooper())
    private lateinit var ngram: NgramModel
    private lateinit var kb: KeyboardUi
    @Volatile private var engine: LlmEngine? = null
    @Volatile private var seq = 0          // bumped on every edit; stale LLM results are dropped
    private var private_ = false           // password / no-suggestion fields: no learning, no LLM
    private var pendingSentence = ""
    private var langIdx = 0
    private val languages = listOf("Spanish", "French", "German", "Portuguese", "Italian", "Japanese", "English")
    private val llmTask = Runnable { predictSentence() }
    private var live = false               // live translate: each finished word is replaced by its translation
    private var lastText = ""

    override fun onCreate() {
        super.onCreate()
        val old = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { t, e ->
            runCatching { ModelStore.log(this).appendText("CRASH in ${t.name}:\n${e.stackTraceToString().take(2500)}\n") }
            old?.uncaughtException(t, e)
        }
        ngram = NgramModel(File(filesDir, "ngram.tsv"))
        loadEngine()
    }

    override fun onDestroy() { ngram.save(); worker.shutdown(); super.onDestroy() }

    @Volatile private var engineMsg = "No model: load one in the app"
    @Volatile private var loading = false
    private var triedStamp = 0L

    private fun loadEngine() {
        val f = ModelStore.file(this)
        if (engine != null || loading || !f.exists() || f.lastModified() == triedStamp) return
        loading = true; triedStamp = f.lastModified(); engineMsg = "Loading model…"
        worker.execute {
            val e = MediaPipeEngine(this, f)
            engine = e.takeIf { it.ready }
            engineMsg = e.error?.take(60) ?: "Model ready"
            runCatching { ModelStore.log(this).appendText("Model load: ${e.error ?: "OK"}\nFile: ${f.length()} bytes\n") }
            loading = false
            ui.post { refresh() }
        }
    }

    override fun onCreateInputView(): View {
        kb = KeyboardUi(
            this,
            onText = { commit(it) },
            onBackspace = { ic()?.deleteSurroundingText(1, 0); refresh() },
            onEnter = { if (live) { liveTranslate(force = true); stopLive() }; sendDefaultEditorAction(true) },
            onWord = { acceptWord(it) },
            onSentence = { acceptSentence() },
            onTranslate = { toggleLiveTranslate() },
            onLanguage = { langIdx = (langIdx + 1) % languages.size; kb.setLanguage(languages[langIdx]); refresh() },
            onSimplify = { transform { Prompts.simplify(it) } },
            onFix = { transform { Prompts.fix(it) } },
        )
        kb.setLanguage(languages[langIdx])
        return kb.root
    }

    override fun onStartInputView(info: EditorInfo, restarting: Boolean) {
        super.onStartInputView(info, restarting)
        val cls = info.inputType and InputType.TYPE_MASK_CLASS
        val variation = info.inputType and InputType.TYPE_MASK_VARIATION
        private_ = (info.inputType and InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS) != 0 ||
            (cls == InputType.TYPE_CLASS_TEXT && variation in PASSWORD_VARIATIONS) ||
            cls == InputType.TYPE_CLASS_NUMBER && variation == InputType.TYPE_NUMBER_VARIATION_PASSWORD
        loadEngine()
        if (::kb.isInitialized) kb.azerty = getSharedPreferences("prefs", MODE_PRIVATE).getBoolean("azerty", true)
        refresh()
    }

    private fun ic(): InputConnection? = currentInputConnection

    private fun commit(s: String) {
        ic()?.commitText(s, 1)
        if (!private_ && (s == " " || s == "." || s == ",")) learnRecent()
        if (live && s.length == 1 && s[0] in WORD_END) liveTranslate()
        refresh()
    }

    private fun learnRecent() {
        val t = ic()?.getTextBeforeCursor(80, 0)?.toString() ?: return
        ngram.learn(t.substringAfterLast('.').takeLast(80))
    }

    private fun before(n: Int = 300) = ic()?.getTextBeforeCursor(n, 0)?.toString().orEmpty()

    /** Rebuild the suggestion strip and (re)start the debounced LLM prediction. */
    private fun refresh() {
        seq++
        ui.removeCallbacks(llmTask)
        if (!::kb.isInitialized) return
        val text = before()
        val keep = pendingSentence
        pendingSentence = ""
        if (live) { kb.status("Live translate → ${languages[langIdx]}  (tap Live to stop)") }
        else if (engine == null) kb.status(engineMsg) else kb.setSentence("")
        if (private_) { kb.setWords(emptyList()); return }
        val prefix = text.takeLastWhile { it.isLetter() || it == '\'' }
        val prev = NgramModel.WORD.findAll(text.dropLast(prefix.length).lowercase()).lastOrNull()?.value
        kb.setWords(ngram.suggest(prefix, prev))
        if (live) { lastText = text; return }

        // The user typed exactly what the suggestion predicted: keep the rest instead of asking the model again.
        // pendingSentence is always the exact text to insert at the cursor.
        if (keep.isNotEmpty() && text.length > lastText.length && text.startsWith(lastText)) {
            val typed = text.substring(lastText.length)
            if (keep.length > typed.length && keep.startsWith(typed)) {
                pendingSentence = keep.substring(typed.length)
                lastText = text
                kb.setSentence(pendingSentence.trim())
                if (pendingSentence.trim().split(' ').size >= 2) return   // still enough left to be useful
            }
        }
        lastText = text
        if (engine != null && text.trim().length >= 8) ui.postDelayed(llmTask, 250)
    }

    private fun predictSentence() {
        val mine = seq
        val ctx = before(240)
        worker.execute {
            if (mine != seq) return@execute          // user kept typing; skip stale work
            engine?.generate(
                Prompts.continuation(ctx), maxWords = 10, stale = { mine != seq },
                onPartial = { part ->
                    val out = clean(part)
                    val lead = if (ctx.isEmpty() || ctx.last().isWhitespace()) "" else " "
                    ui.post { if (mine == seq && out.isNotEmpty()) { pendingSentence = lead + out; kb.setSentence(out) } }
                },
            )
        }
    }

    // ---- Live translate -------------------------------------------------------------------

    private fun toggleLiveTranslate() {
        val selected = ic()?.getSelectedText(0)?.toString().orEmpty()
        if (selected.isNotEmpty()) { transform { Prompts.translate(it, languages[langIdx]) }; return }
        if (engine == null) { kb.status(engineMsg); return }
        if (live) stopLive() else { live = true; kb.setTranslating(true) }
        refresh()
    }

    private fun stopLive() { live = false; kb.setTranslating(false); refresh() }

    /** Translate the word just finished and swap it in place; the user keeps typing meanwhile. */
    private fun liveTranslate(force: Boolean = false) {
        val eng = engine ?: return
        val text = before(300)
        val m = LAST_WORD.find(text) ?: return
        val word = m.groupValues[1]
        val trail = m.groupValues[2]
        if (trail.isEmpty() && !force) return
        val context = text.takeLastWhile { it !in ".?!\n" }.trim().takeLast(160)
        val lang = languages[langIdx]
        if (trail.any { it in ".?!" }) { live = false; kb.setTranslating(false) }   // sentence finished
        worker.execute {
            val out = cleanWord(eng.generate(Prompts.translateWord(context, word, lang), maxWords = 3), word)
            if (out.isEmpty() || out.equals(word, ignoreCase = true)) return@execute
            ui.post { replaceWord(word, trail, out) }
        }
    }

    private fun cleanWord(raw: String, original: String): String {
        val w = raw.lineSequence().firstOrNull().orEmpty().trim().trim('"', '“', '”', '.', ',', '!', '?', ' ')
        return if (original.first().isUpperCase()) w.replaceFirstChar(Char::uppercase) else w
    }

    /** The word is found by text, not position, so it still works after the user typed further. */
    private fun replaceWord(word: String, trail: String, translated: String) {
        val c = ic() ?: return
        val text = c.getTextBeforeCursor(400, 0)?.toString() ?: return
        val idx = text.lastIndexOf(word + trail)
        if (idx < 0 || text.length - idx > 150) return
        c.beginBatchEdit()
        c.deleteSurroundingText(text.length - idx, 0)
        c.commitText(translated + text.substring(idx + word.length), 1)
        c.endBatchEdit()
        refresh()
    }

    private fun clean(s: String) = s.lineSequence().firstOrNull().orEmpty().trim().trim('"', '“', '”')

    private fun acceptWord(word: String) {
        val text = before()
        val prefix = text.takeLastWhile { it.isLetter() || it == '\'' }
        ic()?.deleteSurroundingText(prefix.length, 0)
        ic()?.commitText("$word ", 1)
        if (!private_) learnRecent()
        if (live) liveTranslate()
        refresh()
    }

    private fun acceptSentence() {
        if (pendingSentence.isEmpty()) return
        ic()?.commitText(pendingSentence, 1)
        if (!private_) learnRecent()
        refresh()
    }

    /** Translate / simplify / fix: acts on the selection, or else the text before the cursor. */
    private fun transform(prompt: (String) -> String) {
        val conn = ic() ?: return
        val eng = engine
        if (eng == null) { kb.status(engineMsg); return }
        val selected = conn.getSelectedText(0)?.toString().orEmpty()
        val source = selected.ifEmpty { before(500) }
        if (source.isBlank()) return
        seq++; ui.removeCallbacks(llmTask)
        kb.status("Working…")
        val mine = seq
        worker.execute {
            val out = eng.generate(prompt(source), stale = { mine != seq }).trim().trim('"')
            ui.post {
                if (out.isEmpty()) { kb.status("No result"); return@post }
                val c = ic() ?: return@post
                if (mine != seq) return@post          // cursor / text changed meanwhile
                if (selected.isEmpty()) c.deleteSurroundingText(source.length, 0)
                c.commitText(out, 1)
                refresh()
            }
        }
    }

    private companion object {
        const val WORD_END = " .,!?;:"
        val LAST_WORD = Regex("([\\p{L}'’]+)([^\\p{L}\\d]{0,3})$")
        val PASSWORD_VARIATIONS = setOf(
            InputType.TYPE_TEXT_VARIATION_PASSWORD,
            InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD,
            InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD,
        )
    }
}
