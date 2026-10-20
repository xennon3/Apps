package app.murmur.brain

import android.Manifest
import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.media.AudioManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import app.murmur.MainActivity
import app.murmur.MurmurApp
import app.murmur.R
import app.murmur.data.Phone
import app.murmur.data.Prefs
import app.murmur.data.SmsRepository
import app.murmur.sms.Outbox
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Ambient mode: Murmur Brain living in the phone. Cheap reflexes (plain code in [AmbientService])
 * watch the sensors all the time; when something changes they call [event], and the model wakes
 * up, looks at everything it senses, thinks, plans and acts, then sleeps again. Everything it
 * senses, thinks and does is shown in the Mind window and stays on the phone.
 */
object Ambient {
    /** What shows in the Mind window's timeline. */
    enum class Kind { SENSE, THOUGHT, PLAN, ACT, ASK, JOURNAL }

    data class Entry(val at: Long, val kind: Kind, val text: String)
    data class Plan(val made: Long, val whenText: String, val what: String)
    data class Ask(val id: Long, val who: String, val message: String)
    data class Reminder(val at: Long, val text: String)

    private fun prefs(c: Context) = c.getSharedPreferences("ambient", Context.MODE_PRIVATE)

    private val _timeline = MutableStateFlow<List<Entry>>(emptyList())
    val timeline: StateFlow<List<Entry>> = _timeline
    private val _senses = MutableStateFlow<List<String>>(emptyList())
    val senses: StateFlow<List<String>> = _senses
    /** The thought being written right now, or null while asleep. */
    private val _thinking = MutableStateFlow<String?>(null)
    val thinking: StateFlow<String?> = _thinking
    private val _plans = MutableStateFlow<List<Plan>>(emptyList())
    val plans: StateFlow<List<Plan>> = _plans
    private val _asks = MutableStateFlow<List<Ask>>(emptyList())
    val asks: StateFlow<List<Ask>> = _asks
    private val _running = MutableStateFlow(false)
    val running: StateFlow<Boolean> = _running

    fun on(c: Context) = prefs(c).getBoolean("on", false)

    fun start(context: Context) {
        val c = context.applicationContext
        prefs(c).edit().putBoolean("on", true).apply()
        load(c)
        ContextCompat.startForegroundService(c, Intent(c, AmbientService::class.java))
    }

    fun stop(context: Context) {
        val c = context.applicationContext
        prefs(c).edit().putBoolean("on", false).apply()
        c.stopService(Intent(c, AmbientService::class.java))
        _running.value = false
        log(c, Kind.SENSE, "Ambient mode switched off.")
    }

    internal fun setRunning(v: Boolean) { _running.value = v }

    // ------------------------------------------------------------ memory

    private var loaded = false

    fun load(c: Context) {
        if (loaded) return
        loaded = true
        val p = prefs(c)
        _timeline.value = runCatching {
            val a = JSONArray(p.getString("timeline", "[]"))
            List(a.length()) { i -> a.getJSONObject(i).let { Entry(it.getLong("t"), Kind.valueOf(it.getString("k")), it.getString("x")) } }
        }.getOrDefault(emptyList())
        _plans.value = runCatching {
            val a = JSONArray(p.getString("plans", "[]"))
            List(a.length()) { i -> a.getJSONObject(i).let { Plan(it.getLong("m"), it.getString("w"), it.getString("x")) } }
        }.getOrDefault(emptyList())
        _asks.value = runCatching {
            val a = JSONArray(p.getString("asks", "[]"))
            List(a.length()) { i -> a.getJSONObject(i).let { Ask(it.getLong("id"), it.getString("w"), it.getString("m")) } }
        }.getOrDefault(emptyList())
    }

    private fun saveAll(c: Context) {
        prefs(c).edit()
            .putString("timeline", JSONArray(_timeline.value.takeLast(300).map { JSONObject().put("t", it.at).put("k", it.kind.name).put("x", it.text) }).toString())
            .putString("plans", JSONArray(_plans.value.map { JSONObject().put("m", it.made).put("w", it.whenText).put("x", it.what) }).toString())
            .putString("asks", JSONArray(_asks.value.map { JSONObject().put("id", it.id).put("w", it.who).put("m", it.message) }).toString())
            .apply()
    }

    fun log(c: Context, kind: Kind, text: String) {
        _timeline.value = (_timeline.value + Entry(System.currentTimeMillis(), kind, text)).takeLast(300)
        saveAll(c)
    }

    fun goals(c: Context): List<String> = runCatching {
        val a = JSONArray(prefs(c).getString("goals", "[]")); List(a.length()) { a.getString(it) }
    }.getOrDefault(emptyList())

    fun addGoal(c: Context, goal: String) {
        if (goal.isBlank()) return
        prefs(c).edit().putString("goals", JSONArray(goals(c) + goal.trim()).toString()).apply()
        log(c, Kind.JOURNAL, "New goal: ${goal.trim()}")
    }

    fun removeGoal(c: Context, goal: String) = prefs(c).edit().putString("goals", JSONArray(goals(c) - goal).toString()).apply()

    fun journal(c: Context): List<String> = _timeline.value.filter { it.kind == Kind.JOURNAL }.takeLast(8).map { stamp(it.at) + " " + it.text }

    fun reminders(c: Context): List<Reminder> = runCatching {
        val a = JSONArray(prefs(c).getString("reminders", "[]"))
        List(a.length()) { i -> a.getJSONObject(i).let { Reminder(it.getLong("t"), it.getString("x")) } }
    }.getOrDefault(emptyList())

    private fun saveReminders(c: Context, list: List<Reminder>) =
        prefs(c).edit().putString("reminders", JSONArray(list.map { JSONObject().put("t", it.at).put("x", it.text) }).toString()).apply()

    fun thoughtsToday(c: Context): Int {
        val p = prefs(c)
        val day = SimpleDateFormat("yyyyMMdd", Locale.US).format(Date())
        return if (p.getString("count_day", "") == day) p.getInt("count", 0) else 0
    }

    private fun countThought(c: Context) {
        val day = SimpleDateFormat("yyyyMMdd", Locale.US).format(Date())
        prefs(c).edit().putString("count_day", day).putInt("count", thoughtsToday(c) + 1).apply()
    }

    /** A cap on thoughts per day keeps the battery cost predictable. */
    const val DAILY_THOUGHTS = 60

    fun stamp(t: Long): String = SimpleDateFormat("h:mm a", Locale.US).format(Date(t))

    // ------------------------------------------------------------ waking up

    private val pending = ArrayList<String>()
    private val mind = Mutex()
    private var wake: Job? = null
    private var lastThought = 0L

    fun updateSenses(list: List<String>) { _senses.value = list }

    /**
     * Something changed. Urgent events wake the Brain at once; others gather for up to 10 minutes
     * so a burst of changes becomes one thought.
     */
    fun event(context: Context, what: String, urgent: Boolean = false) {
        val c = context.applicationContext
        if (!on(c)) return
        synchronized(pending) { pending += "${stamp(System.currentTimeMillis())} $what" }
        log(c, Kind.SENSE, what)
        val gap = System.currentTimeMillis() - lastThought
        val wait = if (urgent || gap > 10 * 60_000L) 20_000L else 10 * 60_000L - gap
        if (wake?.isActive == true && !urgent) return
        wake?.cancel()
        wake = MurmurApp.scope.launch { delay(wait); think(c) }
    }

    /** Thinks once about everything sensed since the last thought. */
    suspend fun think(c: Context) = mind.withLock {
        if (!on(c) || !Brain.ready(c)) return@withLock
        if (thoughtsToday(c) >= DAILY_THOUGHTS) { log(c, Kind.THOUGHT, "Resting: reached today's limit of $DAILY_THOUGHTS thoughts."); return@withLock }
        val events = synchronized(pending) { pending.toList().also { pending.clear() } }
        val now = System.currentTimeMillis()
        val situation = AmbientMind.Situation(
            time = SimpleDateFormat("EEEE h:mm a", Locale.US).format(Date(now)),
            senses = _senses.value,
            events = events,
            goals = goals(c),
            plans = _plans.value.map { "${it.whenText}: ${it.what} (planned ${stamp(it.made)})" },
            journal = journal(c),
            memory = BrainMemory.facts(c),
        )
        val prompt = AmbientMind.situation(situation)
        _thinking.value = ""
        lastThought = now
        countThought(c)
        val answer = try {
            Brain.ask(c, AmbientMind.SYSTEM, prompt, maxTokens = 220, temperature = 0.3, examples = AmbientMind.EXAMPLES, onPartial = { _thinking.value = it })
        } finally { _thinking.value = null }
        if (answer == null) { log(c, Kind.THOUGHT, "Couldn't think just now (the model was busy or not loaded)."); return@withLock }
        val t = AmbientMind.allowed(AmbientMind.parse(answer), prompt)
        if (t.observe.isNotBlank() || t.thought.isNotBlank()) log(c, Kind.THOUGHT, listOf(t.observe, t.thought).filter { it.isNotBlank() }.joinToString(" "))
        if (t.plans.isNotEmpty()) {
            _plans.value = t.plans.map { (w, x) -> Plan(now, w, x) }
            t.plans.forEach { (w, x) -> log(c, Kind.PLAN, "$w: $x") }
        } else _plans.value = _plans.value.filter { now - it.made < 12 * 3_600_000L }
        t.acts.forEach { act(c, it) }
        if (t.acts.isEmpty()) log(c, Kind.ACT, "Did nothing; all is well.")
        saveAll(c)
        AmbientService.refreshNotification(c, t.thought.ifBlank { t.observe })
    }

    // ------------------------------------------------------------ doing

    private fun act(c: Context, a: AmbientMind.Act) {
        when (a) {
            is AmbientMind.Act.Notify -> { alert(c, a.text); log(c, Kind.ACT, "Told you: ${a.text}") }
            is AmbientMind.Act.Journal -> log(c, Kind.JOURNAL, a.text)
            is AmbientMind.Act.Remind -> {
                val at = System.currentTimeMillis() + a.minutes * 60_000L
                saveReminders(c, reminders(c) + Reminder(at, a.text))
                log(c, Kind.ACT, "Will remind you at ${stamp(at)}: ${a.text}")
            }
            is AmbientMind.Act.Flashlight -> runCatching {
                val cam = c.getSystemService(CameraManager::class.java)
                cam.cameraIdList.firstOrNull { cam.getCameraCharacteristics(it).get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true }?.let { cam.setTorchMode(it, a.on) }
                log(c, Kind.ACT, "Flashlight ${if (a.on) "on" else "off"}.")
            }
            is AmbientMind.Act.Ringer -> {
                val audio = c.getSystemService(AudioManager::class.java)
                val nm = c.getSystemService(NotificationManager::class.java)
                // Silent needs Do Not Disturb access; vibrate is the quiet we can always give.
                val mode = if (a.mode == "silent" && !nm.isNotificationPolicyAccessGranted) "vibrate" else a.mode
                runCatching {
                    audio.ringerMode = when (mode) { "silent" -> AudioManager.RINGER_MODE_SILENT; "vibrate" -> AudioManager.RINGER_MODE_VIBRATE; else -> AudioManager.RINGER_MODE_NORMAL }
                    log(c, Kind.ACT, "Ringer set to $mode.")
                }
            }
            is AmbientMind.Act.AskSend -> {
                _asks.value = _asks.value + Ask(System.currentTimeMillis(), a.who, a.message)
                log(c, Kind.ASK, "Wants to text ${a.who}: \"${a.message}\" (waiting for your OK)")
                alert(c, "Murmur wants to text ${a.who}: \"${a.message}\". Open the Mind window to send or skip.")
            }
        }
    }

    /** The user's answer to a text the Brain proposed. */
    fun answerAsk(context: Context, ask: Ask, send: Boolean) {
        val c = context.applicationContext
        _asks.value = _asks.value - ask
        if (send) {
            val person = Recipients.find(c, ask.who).firstOrNull()
            if (person == null) { log(c, Kind.ACT, "Couldn't find \"${ask.who}\" in your contacts, so nothing was sent."); saveAll(c); return }
            val repo = SmsRepository(c)
            runCatching {
                val thread = repo.threadIdFor(Phone.digits(person.second).ifEmpty { person.second })
                Outbox.send(c, thread, person.second, ask.message, subId = Prefs(c).sim(thread) ?: -1)
            }
            log(c, Kind.ACT, "You approved: texted ${person.first} \"${ask.message}\".")
        } else log(c, Kind.ACT, "You skipped texting ${ask.who}.")
        saveAll(c)
    }

    /** Called every minute by the service: due reminders and exact-time goals are kept by code. */
    fun tick(c: Context) {
        val now = System.currentTimeMillis()
        val (due, later) = reminders(c).partition { it.at <= now }
        if (due.isNotEmpty()) {
            saveReminders(c, later)
            due.forEach { alert(c, it.text); log(c, Kind.ACT, "Reminded you: ${it.text}") }
        }
        val cal = java.util.Calendar.getInstance()
        val hm = cal.get(java.util.Calendar.HOUR_OF_DAY) to cal.get(java.util.Calendar.MINUTE)
        val day = SimpleDateFormat("yyyyMMdd", Locale.US).format(Date())
        for (g in goals(c)) {
            if (AmbientMind.goalTime(g) != hm) continue
            val key = "goal_${g.hashCode()}"
            if (prefs(c).getString(key, "") == day) continue
            prefs(c).edit().putString(key, day).apply()
            alert(c, g.replace(Regex("(?i)^remind me (to )?"), "Time to ").replace(Regex("(?i)\\s+at \\d.*$"), "."))
            log(c, Kind.ACT, "Goal time: $g")
        }
    }

    const val ALERTS = "ambient_alerts"

    @SuppressLint("MissingPermission")
    private fun alert(c: Context, text: String) {
        if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(c, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return
        c.getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(ALERTS, "Ambient mode: messages", NotificationManager.IMPORTANCE_HIGH).apply { description = "What Murmur noticed and wants you to know" },
        )
        val open = PendingIntent.getActivity(c, 7400, Intent(c, MainActivity::class.java).setAction(MainActivity.OPEN_MIND), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        runCatching {
            NotificationManagerCompat.from(c).notify((System.currentTimeMillis() % 100_000).toInt() + 7400, NotificationCompat.Builder(c, ALERTS)
                .setSmallIcon(R.drawable.ic_stat_murmur).setContentTitle("Murmur noticed").setContentText(text)
                .setStyle(NotificationCompat.BigTextStyle().bigText(text)).setContentIntent(open).setAutoCancel(true).build())
        }
    }
}
