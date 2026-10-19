package app.murmur.brain

import android.app.DownloadManager
import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.net.Uri
import android.os.Environment
import android.util.Log
import app.murmur.MurmurApp
import app.murmur.data.SmsRepository
import app.murmur.data.Vibes
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.EmbeddingEngine
import com.google.ai.edge.litertlm.EmbeddingEngineConfig
import com.google.ai.edge.litertlm.EmbeddingOptions
import com.google.ai.edge.litertlm.InputData
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Search by meaning: a small embedding model (EmbeddingGemma 2, 165 MB) turns each text into
 * 256 numbers on the phone, so "when did Jake mention the boat" finds "just bought a yacht".
 * In Murmur's lab it found the right text for 7 of 7 such questions. The index lives in
 * Murmur's own private storage and never leaves the phone.
 */
object Meaning {
    val MODEL = Brain.ModelSpec(
        "EmbeddingGemma 2", "embeddinggemma-2-text-270m.litertlm",
        "https://huggingface.co/litert-community/embeddinggemma-2-text-270m-litert-lm/resolve/main/embeddinggemma-2-text-270m.litertlm",
        164_626_432L,
    )
    private const val DIMS = 256
    /** Plenty for years of texting; the newest are indexed first. */
    private const val MAX = 15_000

    sealed interface State {
        data object Off : State
        data class Downloading(val fraction: Float) : State
        data class Indexing(val done: Int, val total: Int) : State
        data class Ready(val count: Int) : State
        data class Failed(val why: String) : State
    }

    private val _state = MutableStateFlow<State>(State.Off)
    val state: StateFlow<State> = _state

    private fun prefs(c: Context) = c.getSharedPreferences("meaning", Context.MODE_PRIVATE)
    fun file(c: Context) = File(c.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS), MODEL.file)
    fun on(c: Context) = prefs(c).getBoolean("on", false)
    private fun fileReady(c: Context) = file(c).let { it.exists() && it.length() == MODEL.bytes }
    fun ready(c: Context) = on(c) && fileReady(c)
    fun bytesOnDisk(c: Context) = (if (file(c).exists()) file(c).length() else 0L) + c.getDatabasePath("meaning.db").length()

    // ------------------------------------------------------------ on and off

    fun refresh(context: Context) {
        val c = context.applicationContext
        _state.value = when {
            !on(c) -> State.Off
            fileReady(c) -> State.Ready(db(c).count()).also { update(c) }
            prefs(c).getLong("download", -1) >= 0 -> { watch(c); _state.value }
            else -> State.Failed("The download didn't finish")
        }
    }

    fun turnOn(context: Context) {
        val c = context.applicationContext
        prefs(c).edit().putBoolean("on", true).apply()
        if (fileReady(c)) { refresh(c); return }
        file(c).delete()
        val id = runCatching {
            c.getSystemService(DownloadManager::class.java).enqueue(
                DownloadManager.Request(Uri.parse(MODEL.url)).setTitle("Murmur meaning search")
                    .setDescription("${MODEL.name}, ${MODEL.sizeLabel}")
                    .setDestinationInExternalFilesDir(c, Environment.DIRECTORY_DOWNLOADS, MODEL.file)
                    .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE),
            )
        }.getOrElse { _state.value = State.Failed("Android wouldn't start the download"); return }
        prefs(c).edit().putLong("download", id).apply()
        _state.value = State.Downloading(0f)
        watch(c)
    }

    fun turnOff(context: Context) {
        val c = context.applicationContext
        prefs(c).getLong("download", -1).takeIf { it >= 0 }?.let { runCatching { c.getSystemService(DownloadManager::class.java).remove(it) } }
        prefs(c).edit().clear().apply()
        indexing?.cancel()
        MurmurApp.scope.launch { lock.withLock { closeEngine() } }
        file(c).delete()
        c.deleteDatabase("meaning.db"); helper = null
        _state.value = State.Off
    }

    private var watcher: Job? = null

    private fun watch(c: Context) {
        if (watcher?.isActive == true) return
        watcher = MurmurApp.scope.launch {
            val dm = c.getSystemService(DownloadManager::class.java)
            while (true) {
                val id = prefs(c).getLong("download", -1).takeIf { it >= 0 } ?: return@launch
                val row = runCatching { dm.query(DownloadManager.Query().setFilterById(id)) }.getOrNull()?.use { cur ->
                    if (!cur.moveToFirst()) null else cur.getInt(cur.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS)) to
                        cur.getLong(cur.getColumnIndexOrThrow(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR))
                }
                when (row?.first) {
                    null, DownloadManager.STATUS_FAILED -> { prefs(c).edit().remove("download").apply(); _state.value = State.Failed("The download failed. Try again"); return@launch }
                    DownloadManager.STATUS_SUCCESSFUL -> { prefs(c).edit().remove("download").apply(); refresh(c); return@launch }
                    else -> _state.value = State.Downloading((row.second.toFloat() / MODEL.bytes).coerceIn(0f, 1f))
                }
                delay(700)
            }
        }
    }

    // ------------------------------------------------------------ the model

    private val lock = Mutex()
    private var engine: EmbeddingEngine? = null
    private var idle: Job? = null

    private fun engine(c: Context): EmbeddingEngine = engine ?: EmbeddingEngine(
        EmbeddingEngineConfig(modelPath = file(c).path, backend = Backend.CPU(), cacheDir = c.cacheDir.path),
    ).also { it.initialize(); engine = it }

    private fun closeEngine() { runCatching { engine?.close() }; engine = null }

    /** It's small (about 300 MB loaded), but still give the memory back a minute after the last use. */
    private fun releaseSoon() {
        idle?.cancel()
        idle = MurmurApp.scope.launch { delay(60_000); lock.withLock { closeEngine() } }
    }

    private val options = EmbeddingOptions(normalize = true, outputSize = DIMS)

    private fun embed(c: Context, texts: List<String>): List<FloatArray> =
        engine(c).computeEmbeddingBatch(texts.map { listOf(InputData.Text(BrainPrompts.engineSafe(it))) }, options).map { it.embedding }

    // ------------------------------------------------------------ the index

    private class Db(c: Context) : SQLiteOpenHelper(c, "meaning.db", null, 1) {
        override fun onCreate(db: SQLiteDatabase) = db.execSQL("CREATE TABLE vec (id INTEGER PRIMARY KEY, thread INTEGER, date INTEGER, v BLOB)")
        override fun onUpgrade(db: SQLiteDatabase, o: Int, n: Int) {}
        fun count() = runCatching { readableDatabase.rawQuery("SELECT COUNT(*) FROM vec", null).use { it.moveToFirst(); it.getInt(0) } }.getOrDefault(0)
    }

    @Volatile private var helper: Db? = null
    private fun db(c: Context) = helper ?: Db(c.applicationContext).also { helper = it }

    private fun blob(v: FloatArray): ByteArray = ByteBuffer.allocate(v.size * 4).order(ByteOrder.LITTLE_ENDIAN).also { b -> v.forEach { b.putFloat(it) } }.array()
    private fun floats(b: ByteArray): FloatArray = ByteBuffer.wrap(b).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer().let { fb -> FloatArray(fb.remaining()).also { fb.get(it) } }

    private var indexing: Job? = null

    /** Indexes texts that aren't in the index yet, newest first, in the background. */
    fun update(context: Context) {
        val c = context.applicationContext
        if (!ready(c) || indexing?.isActive == true) return
        indexing = MurmurApp.scope.launch { runCatching { indexNew(c) }.onFailure { Log.w("MurmurMeaning", "indexing failed", it) } }
    }

    private suspend fun indexNew(c: Context) {
        val known = HashSet<Long>()
        db(c).readableDatabase.rawQuery("SELECT id FROM vec", null).use { while (it.moveToNext()) known += it.getLong(0) }
        val todo = ArrayList<SmsRepository.Hit>()
        SmsRepository(c).eachSms { h ->
            if (h.id !in known && worthIndexing(h.body)) todo += h
            known.size + todo.size < MAX
        }
        if (todo.isEmpty()) { _state.value = State.Ready(known.size); return }
        var done = 0
        for (batch in todo.chunked(16)) {
            lock.withLock {
                withContext(Dispatchers.IO + NonCancellable) {
                    val vs = embed(c, batch.map { "title: none | text: " + it.body.replace('\n', ' ').take(600) })
                    val w = db(c).writableDatabase
                    w.beginTransaction()
                    try {
                        batch.zip(vs).forEach { (h, v) ->
                            w.insertWithOnConflict("vec", null, ContentValues().apply { put("id", h.id); put("thread", h.threadId); put("date", h.date); put("v", blob(v)) }, SQLiteDatabase.CONFLICT_REPLACE)
                        }
                        w.setTransactionSuccessful()
                    } finally { w.endTransaction() }
                }
            }
            done += batch.size
            if (todo.size > 32) _state.value = State.Indexing(done, todo.size)
        }
        releaseSoon()
        _state.value = State.Ready(known.size + done)
    }

    private fun worthIndexing(body: String) = body.trim().split(Regex("\\s+")).size >= 2 && Vibes.code(body) == null

    data class Match(val id: Long, val threadId: Long, val score: Float)

    /** The texts closest in meaning to [question], best first. */
    suspend fun search(context: Context, question: String, k: Int = 8): List<Match> {
        val c = context.applicationContext
        if (!ready(c)) return emptyList()
        val q = lock.withLock {
            withContext(Dispatchers.IO + NonCancellable) { runCatching { embed(c, listOf("task: search result | query: $question")).first() }.getOrNull() }
        } ?: return emptyList()
        releaseSoon()
        return withContext(Dispatchers.IO) {
            val best = java.util.PriorityQueue<Match>(compareBy { it.score })
            db(c).readableDatabase.rawQuery("SELECT id, thread, v FROM vec", null).use { cur ->
                while (cur.moveToNext()) {
                    val v = floats(cur.getBlob(2))
                    var dot = 0f
                    for (i in 0 until minOf(v.size, q.size)) dot += v[i] * q[i]
                    if (best.size < k) best += Match(cur.getLong(0), cur.getLong(1), dot)
                    else if (dot > best.peek()!!.score) { best.poll(); best += Match(cur.getLong(0), cur.getLong(1), dot) }
                }
            }
            best.sortedByDescending { it.score }
        }
    }
}
