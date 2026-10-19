package app.murmur.brain

import android.app.DownloadManager
import android.content.Context
import android.net.Uri
import android.os.Environment
import android.util.Log
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.Content
import com.google.ai.edge.litertlm.Contents
import com.google.ai.edge.litertlm.ConversationConfig
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import com.google.ai.edge.litertlm.Message
import com.google.ai.edge.litertlm.SamplerConfig
import com.google.ai.edge.litertlm.ToolProvider
import com.google.ai.edge.litertlm.ExperimentalApi
import com.google.ai.edge.litertlm.MessageCallback
import com.google.ai.edge.litertlm.benchmark
import app.murmur.MurmurApp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.NonCancellable
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Murmur Brain: a small language model (see [MODEL]) that runs entirely on
 * the phone through Google's LiteRT-LM. The model is fetched once by Android's download
 * manager (the only thing Murmur uses the internet for), and everything after that is local.
 */
object Brain {

    /** A model Murmur knows how to run. */
    data class ModelSpec(val name: String, val file: String, val url: String, val bytes: Long) {
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

    /** What the speed test measured on this phone. */
    data class Speed(val loadSeconds: Float, val firstWordSeconds: Float, val readPerSecond: Float, val writePerSecond: Float, val gpu: Boolean)

    private val _speed = MutableStateFlow<Speed?>(null)
    val speed: StateFlow<Speed?> = _speed

    private fun prefs(context: Context) = context.getSharedPreferences("brain", Context.MODE_PRIVATE)

    fun modelFile(context: Context) = File(context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS), MODEL.file)

    fun enabled(context: Context) = prefs(context).getBoolean("on", false)

    /** Reply chips in chats (on by default once the Brain is on). */
    fun suggestOn(context: Context) = prefs(context).getBoolean("suggest", true)
    fun setSuggestOn(context: Context, v: Boolean) = prefs(context).edit().putBoolean("suggest", v).apply()

    /** Suggestions already made, by message key, so reopening a chat doesn't wake the model again. */
    val replyCache = java.util.concurrent.ConcurrentHashMap<String, List<String>>()

    fun ready(context: Context) = enabled(context) && modelFile(context).let { it.exists() && it.length() == MODEL.bytes }

    /** Call when the app starts or Settings opens: works out where things stand. */
    /** Called by the crash reporter: the app died last time while the graphics chip was running the model. */
    fun gpuSessionDied(context: Context): Boolean = prefs(context.applicationContext).getBoolean("gpu_session", false)

    fun blameGpu(context: Context) {
        prefs(context.applicationContext).edit().putBoolean("gpu", false).putBoolean("gpu_bad", true).putBoolean("gpu_session", false).apply()
    }

    fun refresh(context: Context) {
        val c = context.applicationContext
        // If the app died while trying the graphics chip, that chip can't run the model: stay on the CPU.
        if (prefs(c).getBoolean("gpu_trying", false)) {
            prefs(c).edit().putBoolean("gpu_trying", false).putBoolean("gpu_bad", true).putBoolean("gpu", false).apply()
        }
        prefs(c).edit().putBoolean("gpu_session", false).apply()
        _speed.value = prefs(c).getString("speed2", null)?.split(',')?.takeIf { it.size == 5 }?.let {
            Speed(it[0].toFloat(), it[1].toFloat(), it[2].toFloat(), it[3].toFloat(), it[4] == "1")
        }
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
        val id = try {
            dm.enqueue(
                DownloadManager.Request(Uri.parse(MODEL.url))
                    .setTitle("Murmur Brain")
                    .setDescription("${MODEL.name} language model, ${MODEL.sizeLabel}")
                    .setDestinationInExternalFilesDir(c, Environment.DIRECTORY_DOWNLOADS, MODEL.file)
                    .setAllowedOverMetered(allowMobileData)
                    .setAllowedOverRoaming(false)
                    .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE),
            )
        } catch (e: Exception) {
            // Never let a refused download take the app down; say what happened instead.
            Log.w("MurmurBrain", "download refused", e)
            _state.value = State.Failed("Android wouldn't start the download (${e.javaClass.simpleName}). Try again")
            return
        }
        prefs(c).edit().putLong("download", id).apply()
        _state.value = State.Downloading(0f, false)
        watch(c)
    }

    fun turnOff(context: Context) {
        val c = context.applicationContext
        prefs(c).getLong("download", -1).takeIf { it >= 0 }?.let { id ->
            runCatching { c.getSystemService(DownloadManager::class.java).remove(id) }
        }
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
                val row = runCatching { dm.query(DownloadManager.Query().setFilterById(id)) }.getOrNull()?.use { cur ->
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

    private fun useGpu(c: Context) = prefs(c).getBoolean("gpu", false) && !prefs(c).getBoolean("gpu_bad", false)

    /**
     * Runs [block] while a "trying the GPU" flag is saved. A graphics driver can kill the whole
     * app in native code, which no try/catch sees; the flag left behind tells the next launch.
     */
    private inline fun <T> guardGpu(c: Context, gpu: Boolean, block: () -> T): T {
        if (gpu) prefs(c).edit().putBoolean("gpu_trying", true).commit()
        try {
            return block()
        } finally {
            if (gpu) prefs(c).edit().putBoolean("gpu_trying", false).commit()
        }
    }

    private suspend fun engine(context: Context): Engine? {
        engine?.let { return it }
        if (!ready(context)) return null
        return withContext(Dispatchers.IO) {
            fun load(gpu: Boolean) = guardGpu(context, gpu) {
                Engine(
                    EngineConfig(
                        modelPath = modelFile(context).path,
                        backend = if (gpu) Backend.GPU() else Backend.CPU(),
                        maxNumTokens = 4096, // room for chat history, found texts, tool descriptions and web results
                        cacheDir = context.cacheDir.path,
                    ),
                ).also { it.initialize() }
            }
            val gpu = useGpu(context)
            appContext = context.applicationContext
            runCatching { load(gpu) }.onSuccess {
                // While a graphics-chip engine is loaded, remember it: if Android kills the app for
                // memory or a driver crash, the next launch switches the Brain to the processor.
                if (gpu) prefs(context).edit().putBoolean("gpu_session", true).commit()
            }.recoverCatching {
                if (!gpu) throw it
                prefs(context).edit().putBoolean("gpu", false).putBoolean("gpu_bad", true).apply()
                load(false)
            }.getOrNull()
        }?.also { engine = it }
    }

    /** Start loading the model before it's needed (when a draft starts sounding heated). */
    fun warmUp(context: Context) {
        val c = context.applicationContext
        if (!ready(c) || engine != null || lock.isLocked) return
        MurmurApp.scope.launch {
            lock.withLock { withContext(NonCancellable) { engine(c) } }
            scheduleUnload()
        }
    }

    /** The model takes over a gigabyte of memory while loaded; give it back three minutes after the last use. */
    private fun scheduleUnload() {
        idle?.cancel()
        idle = MurmurApp.scope.launch { delay(180_000); unload() }
    }

    private suspend fun unload() = lock.withLock {
        runCatching { engine?.close() }
        engine = null
        prefs(appContext ?: return@withLock).edit().putBoolean("gpu_session", false).apply()
    }

    private var appContext: Context? = null

    /**
     * One question, one answer. Returns null if the Brain is off, still downloading, or the
     * model couldn't run. Only one request runs at a time.
     */
    suspend fun ask(
        context: Context, system: String, prompt: String, maxTokens: Int = 160, temperature: Double = 0.4,
        examples: List<Pair<String, String>> = emptyList(),
        /** Called with the answer so far as each word arrives (from a background thread). */
        onPartial: ((String) -> Unit)? = null,
        /** Tools the model may call on its own (web search, calculator, MCP…); results feed back in. */
        tools: List<ToolProvider> = emptyList(),
    ): String? {
        val c = context.applicationContext
        if (!ready(c)) return null
        return lock.withLock {
            // NonCancellable: once started, a load or an answer always finishes and cleans up.
            // Abandoning one halfway (a screen closing, a list refreshing) used to leave a 2.6 GB
            // model loaded but unreferenced, and the next request loaded a second copy.
            withContext(Dispatchers.IO + NonCancellable) {
                val e = engine(c) ?: return@withContext null
                runCatching {
                    val conv = e.createConversation(
                        ConversationConfig(
                            systemInstruction = Contents.of(BrainPrompts.engineSafe(system)),
                            // Worked examples go in as earlier turns of the chat.
                            initialMessages = examples.flatMap { (q, a) ->
                                listOf(Message.user(BrainPrompts.engineSafe(q)), Message.model(BrainPrompts.engineSafe(a)))
                            },
                            samplerConfig = SamplerConfig(topK = 40, topP = 0.9, temperature = temperature, seed = 7),
                            maxOutputToken = maxTokens,
                            tools = tools,
                            automaticToolCalling = tools.isNotEmpty(),
                        ),
                    )
                    try {
                        suspendCoroutine { cont ->
                            val text = StringBuilder()
                            val done = AtomicBoolean(false)
                            conv.sendMessageAsync(BrainPrompts.engineSafe(prompt), object : MessageCallback {
                                override fun onMessage(message: Message) {
                                    message.contents.contents.filterIsInstance<Content.Text>().forEach { text.append(it.text) }
                                    onPartial?.invoke(BrainPrompts.clean(text.toString()))
                                }
                                override fun onDone() { if (done.compareAndSet(false, true)) cont.resume(BrainPrompts.clean(text.toString())) }
                                override fun onError(throwable: Throwable) { if (done.compareAndSet(false, true)) cont.resume(null) }
                            })
                        }
                    } finally {
                        conv.close()
                    }
                }.getOrNull()
            }
        }.also { scheduleUnload() }
    }

    /**
     * Measures loading, reading (prefill) and writing (decode) separately with LiteRT-LM's own
     * benchmark, on the CPU and, once, on the graphics chip; then keeps whichever writes faster.
     */
    @OptIn(ExperimentalApi::class)
    suspend fun speedTest(context: Context): Speed? {
        val c = context.applicationContext
        if (!ready(c)) return null
        return lock.withLock {
            runCatching { engine?.close() }; engine = null // the benchmark loads its own copy
            withContext(Dispatchers.IO) {
                val path = modelFile(c).path
                fun run(gpu: Boolean) = runCatching {
                    guardGpu(c, gpu) { benchmark(path, if (gpu) Backend.GPU() else Backend.CPU(), 256, 32, c.cacheDir.path) }
                }.getOrNull()
                val cpu = run(false) ?: return@withContext null
                val gpu = if (prefs(c).getBoolean("gpu_bad", false)) null else run(true).also {
                    if (it == null) prefs(c).edit().putBoolean("gpu_bad", true).apply()
                }
                val useGpu = gpu != null && gpu.lastDecodeTokensPerSecond > cpu.lastDecodeTokensPerSecond * 1.15
                val best = if (useGpu) gpu else cpu
                Speed(
                    best.initTimeInSecond.toFloat(), best.timeToFirstTokenInSecond.toFloat(),
                    best.lastPrefillTokensPerSecond.toFloat(), best.lastDecodeTokensPerSecond.toFloat(), useGpu,
                ).also { sp ->
                    prefs(c).edit().putBoolean("gpu", useGpu)
                        .putString("speed2", listOf(sp.loadSeconds, sp.firstWordSeconds, sp.readPerSecond, sp.writePerSecond, if (useGpu) 1 else 0).joinToString(","))
                        .apply()
                    _speed.value = sp
                }
            }
        }
    }

    // ================================================================ what Murmur uses it for

    private fun ex(list: List<Pair<String, String>>, wrap: (String) -> String = { it }) = list.map { (q, a) -> wrap(q) to a }

    /** Should a chat question be answered from the user's texts? */
    suspend fun needsMessages(context: Context, question: String): Boolean =
        ask(context, BrainPrompts.ROUTE, question, maxTokens = 4, temperature = 0.0, examples = BrainPrompts.ROUTE_EXAMPLES)
            ?.let(BrainPrompts::needsMessages) ?: false

    suspend fun searchTerms(context: Context, question: String): List<String> =
        ask(context, BrainPrompts.SEARCH, question, maxTokens = 30, temperature = 0.1, examples = BrainPrompts.SEARCH_EXAMPLES)
            ?.let(BrainPrompts::parseTerms).orEmpty()

    /**
     * One chat reply. [history] is the earlier conversation as (user, assistant) pairs, given to
     * the model as real chat turns; [system] already carries memory and any phone data found.
     */
    suspend fun chat(
        context: Context, history: List<Pair<String, String>>, prompt: String, system: String,
        onPartial: (String) -> Unit,
    ): String? = ask(context, system, prompt, maxTokens = 320, temperature = 0.6, examples = history, onPartial = onPartial)

    /** A general question with tools available: the model decides which (if any) to call. */
    suspend fun chatWithTools(
        context: Context, history: List<Pair<String, String>>, prompt: String, system: String,
        box: app.murmur.brain.tools.Toolbox, onPartial: (String) -> Unit,
    ): String? = ask(context, system, prompt, maxTokens = 400, temperature = 0.3, examples = history, onPartial = onPartial, tools = box.providers())

    /** Who and what for "text mom I'm running late": the model words it, plain code is the fallback. */
    suspend fun draftSend(context: Context, request: String): BrainPrompts.SendDraft? =
        ask(context, BrainPrompts.SEND, request, maxTokens = 60, temperature = 0.0, examples = BrainPrompts.SEND_EXAMPLES)
            ?.let(BrainPrompts::parseSend) ?: BrainPrompts.fallbackSend(request)

    /**
     * The plan check for one text, ready to store: "title|when|place", "-" for no plan, or null
     * if the model couldn't answer right now (so the text gets checked again later instead of
     * being marked "no plan" for good).
     */
    suspend fun planCheck(context: Context, text: String): String? =
        ask(context, BrainPrompts.EVENT, text.replace('\n', ' '), maxTokens = 40, temperature = 0.0, examples = BrainPrompts.EVENT_EXAMPLES)
            ?.let { BrainPrompts.encodePlan(BrainPrompts.parseEvent(it)) }

    /** A plan in a text ("dinner friday at 7 at Luigi's"), or null. */
    suspend fun findPlan(context: Context, text: String): BrainPrompts.Plan? =
        ask(context, BrainPrompts.EVENT, text.replace('\n', ' '), maxTokens = 40, temperature = 0.0, examples = BrainPrompts.EVENT_EXAMPLES)
            ?.let(BrainPrompts::parseEvent)

    suspend fun triage(context: Context, text: String): BrainPrompts.Triage? =
        ask(context, BrainPrompts.TRIAGE, text.replace('\n', ' '), maxTokens = 4, temperature = 0.0, examples = BrainPrompts.TRIAGE_EXAMPLES)
            ?.let(BrainPrompts::parseTriage)

    /** Up to three replies the user could send next, worded like their own texts. */
    suspend fun suggestReplies(context: Context, lines: List<Pair<Boolean, String>>, mySent: List<String>): List<String>? {
        val answer = ask(
            context, BrainPrompts.REPLIES, BrainPrompts.replyTranscript(lines), maxTokens = 60, temperature = 0.4,
            examples = BrainPrompts.REPLIES_EXAMPLES,
        ) ?: return null
        return BrainPrompts.parseReplies(answer, mySent).takeIf { it.isNotEmpty() }
    }

    /** Three bullet points about a conversation, streamed as they're written. */
    suspend fun summarize(context: Context, transcript: String, onPartial: (String) -> Unit): String? =
        ask(context, BrainPrompts.SUMMARY, transcript, maxTokens = 160, temperature = 0.2, onPartial = onPartial)

    /** A second opinion on a text the rule-based shield wasn't sure about. */
    suspend fun scamCheck(context: Context, message: String): BrainPrompts.ScamOpinion? {
        val answer = ask(
            context,
            system = BrainPrompts.SCAM,
            prompt = BrainPrompts.scamQuestion(message),
            maxTokens = 32,
            temperature = 0.1,
            examples = ex(BrainPrompts.SCAM_EXAMPLES, BrainPrompts::scamQuestion),
        ) ?: return null
        return BrainPrompts.parseScamAnswer(answer)
    }

}
