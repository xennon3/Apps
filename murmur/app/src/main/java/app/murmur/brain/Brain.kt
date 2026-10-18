package app.murmur.brain

import android.app.DownloadManager
import android.content.Context
import android.net.Uri
import android.os.Environment
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.Content
import com.google.ai.edge.litertlm.Contents
import com.google.ai.edge.litertlm.ConversationConfig
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import com.google.ai.edge.litertlm.Message
import com.google.ai.edge.litertlm.SamplerConfig
import com.google.ai.edge.litertlm.ThinkingConfig
import app.murmur.MurmurApp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Murmur Brain: a small language model (see [MODEL]) that runs entirely on
 * the phone through Google's LiteRT-LM. Murmur has no internet permission, so the model
 * is fetched once by Android's own download manager, and everything after that is local.
 */
object Brain {

    /** A model Murmur knows how to run. Only [chatTemplate] differs between model families. */
    data class ModelSpec(
        val name: String,
        val file: String,
        val url: String,
        val bytes: Long,
        /** Murmur's own chat template, for a model whose bundled one doesn't work with LiteRT-LM. */
        val chatTemplate: String? = null,
    ) {
        val sizeLabel get() = if (bytes >= 1_000_000_000) "%.1f GB".format(bytes / 1e9) else "${bytes / 1_000_000} MB"
    }

    /**
     * Gemma 4 E2B (Apache 2.0, no sign-in needed). Tested against Murmur's own prompts before
     * shipping: it rewrote heated texts well and sorted 8 of 8 sample texts into scam / not scam,
     * where the smaller Qwen3 0.6B and Qwen2.5 1.5B got 4 and 6. It peaks around 1.5 GB of memory
     * while working and is unloaded a minute after use.
     */
    val GEMMA4_E2B = ModelSpec(
        "Gemma 4 E2B", "gemma-4-E2B-it.litertlm",
        "https://huggingface.co/litert-community/gemma-4-E2B-it-litert-lm/resolve/main/gemma-4-E2B-it.litertlm",
        2_588_147_712L,
    )

    /** The model Murmur downloads. */
    val MODEL = GEMMA4_E2B

    sealed interface State {
        data object Off : State
        data class Downloading(val fraction: Float, val waitingForWifi: Boolean) : State
        data object Ready : State
        data class Failed(val why: String) : State
    }

    private val _state = MutableStateFlow<State>(State.Off)
    val state: StateFlow<State> = _state

    /** Last speed test, in tokens per second, so Settings can show it. */
    private val _speed = MutableStateFlow<Float?>(null)
    val speed: StateFlow<Float?> = _speed

    private fun prefs(context: Context) = context.getSharedPreferences("brain", Context.MODE_PRIVATE)

    fun modelFile(context: Context) = File(context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS), MODEL.file)

    fun enabled(context: Context) = prefs(context).getBoolean("on", false)

    fun ready(context: Context) = enabled(context) && modelFile(context).let { it.exists() && it.length() == MODEL.bytes }

    /** Call when the app starts or Settings opens: works out where things stand. */
    fun refresh(context: Context) {
        val c = context.applicationContext
        _speed.value = prefs(c).getFloat("speed", -1f).takeIf { it > 0 }
        _state.value = when {
            !enabled(c) -> State.Off
            ready(c) -> State.Ready
            prefs(c).getLong("download", -1) >= 0 -> { watch(c); _state.value }
            else -> State.Failed("The download didn't finish")
        }
    }

    // ================================================================ turning it on and off

    fun turnOn(context: Context, allowMobileData: Boolean) {
        val c = context.applicationContext
        prefs(c).edit().putBoolean("on", true).apply()
        if (ready(c)) { _state.value = State.Ready; return }
        modelFile(c).delete()
        val dm = c.getSystemService(DownloadManager::class.java)
        val id = dm.enqueue(
            DownloadManager.Request(Uri.parse(MODEL.url))
                .setTitle("Murmur Brain")
                .setDescription("${MODEL.name} language model, ${MODEL.sizeLabel}")
                .setDestinationInExternalFilesDir(c, Environment.DIRECTORY_DOWNLOADS, MODEL.file)
                .setAllowedOverMetered(allowMobileData)
                .setAllowedOverRoaming(false)
                .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE),
        )
        prefs(c).edit().putLong("download", id).apply()
        _state.value = State.Downloading(0f, false)
        watch(c)
    }

    fun turnOff(context: Context) {
        val c = context.applicationContext
        prefs(c).getLong("download", -1).takeIf { it >= 0 }?.let { c.getSystemService(DownloadManager::class.java).remove(it) }
        prefs(c).edit().clear().apply()
        MurmurApp.scope.launch { unload() }
        modelFile(c).delete()
        _speed.value = null
        _state.value = State.Off
    }

    private var watcher: Job? = null

    private fun watch(c: Context) {
        if (watcher?.isActive == true) return
        watcher = MurmurApp.scope.launch {
            val dm = c.getSystemService(DownloadManager::class.java)
            while (true) {
                val id = prefs(c).getLong("download", -1)
                if (id < 0) return@launch
                val row = dm.query(DownloadManager.Query().setFilterById(id))?.use { cur ->
                    if (!cur.moveToFirst()) null
                    else Triple(
                        cur.getInt(cur.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS)),
                        cur.getLong(cur.getColumnIndexOrThrow(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR)),
                        cur.getInt(cur.getColumnIndexOrThrow(DownloadManager.COLUMN_REASON)),
                    )
                }
                if (row == null) { _state.value = State.Failed("The download was cancelled"); prefs(c).edit().remove("download").apply(); return@launch }
                val (status, done, reason) = row
                when (status) {
                    DownloadManager.STATUS_SUCCESSFUL -> {
                        prefs(c).edit().remove("download").apply()
                        _state.value = if (ready(c)) State.Ready else State.Failed("The download came out the wrong size. Try again")
                        return@launch
                    }
                    DownloadManager.STATUS_FAILED -> {
                        prefs(c).edit().remove("download").apply()
                        _state.value = State.Failed("The download failed (code $reason). Check your connection and try again")
                        return@launch
                    }
                    else -> _state.value = State.Downloading(
                        (done.toFloat() / MODEL.bytes).coerceIn(0f, 1f),
                        waitingForWifi = status == DownloadManager.STATUS_PAUSED && reason == DownloadManager.PAUSED_QUEUED_FOR_WIFI,
                    )
                }
                delay(700)
            }
        }
    }

    // ================================================================ thinking

    private val lock = Mutex()
    private var engine: Engine? = null
    private var idle: Job? = null

    private suspend fun engine(context: Context): Engine? {
        engine?.let { return it }
        if (!ready(context)) return null
        return withContext(Dispatchers.IO) {
            runCatching {
                Engine(
                    EngineConfig(
                        modelPath = modelFile(context).path,
                        backend = Backend.CPU(),
                        maxNumTokens = 1024,
                        cacheDir = context.cacheDir.path,
                    ),
                ).also { it.initialize() }
            }.getOrNull()
        }?.also { engine = it }
    }

    /** The model takes over a gigabyte of memory while loaded; give it back a minute after the last use. */
    private fun scheduleUnload() {
        idle?.cancel()
        idle = MurmurApp.scope.launch { delay(60_000); unload() }
    }

    private suspend fun unload() = lock.withLock { runCatching { engine?.close() }; engine = null }

    /**
     * One question, one answer. Returns null if the Brain is off, still downloading, or the
     * model couldn't run. Only one request runs at a time.
     */
    suspend fun ask(
        context: Context, system: String, prompt: String, maxTokens: Int = 160, temperature: Double = 0.4,
        examples: List<Pair<String, String>> = emptyList(),
    ): String? {
        val c = context.applicationContext
        if (!ready(c)) return null
        return lock.withLock {
            val e = engine(c) ?: return@withLock null
            withContext(Dispatchers.IO) {
                runCatching {
                    e.createConversation(
                        ConversationConfig(
                            systemInstruction = Contents.of(system),
                            // Worked examples go in as earlier turns of the chat.
                            initialMessages = examples.flatMap { (q, a) -> listOf(Message.user(q), Message.model(a)) },
                            samplerConfig = SamplerConfig(topK = 40, topP = 0.9, temperature = temperature, seed = 7),
                            maxOutputToken = maxTokens,
                            chatTemplate = MODEL.chatTemplate,
                            thinkingConfig = if (MODEL.chatTemplate != null) ThinkingConfig(enableThinking = false, thinkingTokenBudget = 0) else null,
                        ),
                    ).use { conv ->
                        val reply = conv.sendMessage(prompt)
                        BrainPrompts.clean(reply.contents.contents.filterIsInstance<Content.Text>().joinToString("") { it.text })
                    }
                }.getOrNull()
            }
        }.also { scheduleUnload() }
    }


    /** Times a short rewrite to tell how usable the Brain is on this phone. */
    suspend fun speedTest(context: Context): Float? {
        val started = System.nanoTime()
        val out = ask(context, "You rewrite text messages.", "Rewrite this kindly: \"why do you never answer my texts\"", maxTokens = 60) ?: return null
        val seconds = (System.nanoTime() - started) / 1e9f
        val tokens = (out.length / 4f).coerceAtLeast(1f) // about four characters a token
        return (tokens / seconds).also {
            _speed.value = it
            prefs(context).edit().putFloat("speed", it).apply()
        }
    }

    // ================================================================ what Murmur uses it for

    /** A calmer version of a heated draft, in the same voice. */
    suspend fun calmDown(context: Context, draft: String): String? = ask(
        context,
        system = BrainPrompts.CALM,
        prompt = BrainPrompts.calmPrompt(draft),
        maxTokens = 80,
        temperature = 0.3,
        examples = BrainPrompts.CALM_EXAMPLES.map { (a, b) -> BrainPrompts.calmPrompt(a) to b },
    )?.let(BrainPrompts::parseCalm)
        ?.takeIf { it.length < draft.length * 3 + 60 && !it.equals(draft.trim(), ignoreCase = true) }

    /** A second opinion on a text the rule-based shield wasn't sure about. */
    suspend fun scamCheck(context: Context, message: String): BrainPrompts.ScamOpinion? {
        val answer = ask(
            context,
            system = BrainPrompts.SCAM,
            prompt = BrainPrompts.scamQuestion(message),
            maxTokens = 32,
            temperature = 0.1,
            examples = BrainPrompts.SCAM_EXAMPLES.map { (a, b) -> BrainPrompts.scamQuestion(a) to b },
        ) ?: return null
        return BrainPrompts.parseScamAnswer(answer)
    }

}
