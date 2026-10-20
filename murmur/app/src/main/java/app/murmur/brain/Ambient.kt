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
        // "Wake me up at 8 AM" deserves a real alarm, set now while the app is on screen (Android blocks it from the background).
        if (AmbientMind.isAlarmGoal(goal)) setAlarm(c, goal)
    }

    /** Alarm goals added before alarms existed get theirs when the Mind window opens (the app is on screen then). */
    fun ensureAlarms(c: Context) {
        for (g in goals(c).filter { AmbientMind.isAlarmGoal(it) }) {
            val key = "alarm_" + g.hashCode()
            if (prefs(c).getBoolean(key, false)) continue
            setAlarm(c, g)
        }
    }

    private fun setAlarm(c: Context, goal: String) {
        val (h, m) = AmbientMind.goalTime(goal) ?: return
        val days = when {
            Regex("(?i)every ?day|daily|each day").containsMatchIn(goal) -> (1..7).toList()
            Regex("(?i)weekday").containsMatchIn(goal) -> listOf(2, 3, 4, 5, 6)
            else -> null
        }
        val i = Intent(android.provider.AlarmClock.ACTION_SET_ALARM)
            .putExtra(android.provider.AlarmClock.EXTRA_HOUR, h).putExtra(android.provider.AlarmClock.EXTRA_MINUTES, m)
            .putExtra(android.provider.AlarmClock.EXTRA_MESSAGE, "Murmur: " + goal.take(40))
            .putExtra(android.provider.AlarmClock.EXTRA_SKIP_UI, true)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        days?.let { i.putExtra(android.provider.AlarmClock.EXTRA_DAYS, ArrayList(it)) }
        val ok = runCatching { c.startActivity(i) }.isSuccess
        if (ok) prefs(c).edit().putBoolean("alarm_" + goal.hashCode(), true).apply()
        log(c, Kind.ACT, if (ok) "Set a real alarm in your Clock app for ${AmbientMind.clock(h to m)}" + (if (days != null) " (repeating)" else "") + "."
            else "Couldn't open the Clock app to set the alarm; set it there yourself for ${AmbientMind.clock(h to m)}.")
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

    /**
     * How often it thinks. Check-ins happen even when nothing changed; the gap is how long a burst of
     * changes is gathered into one thought; the daily cap keeps the battery cost predictable.
     */
    enum class Pace(val label: String, val checkMinutes: Int, val gapMinutes: Int, val daily: Int) {
        LIVELY("Lively", 5, 2, 250), BALANCED("Balanced", 10, 5, 140), RELAXED("Relaxed", 30, 10, 60),
    }

    fun pace(c: Context) = runCatching { Pace.valueOf(prefs(c).getString("pace", "BALANCED")!!) }.getOrDefault(Pace.BALANCED)
    fun setPace(c: Context, p: Pace) = prefs(c).edit().putString("pace", p.name).apply()

    fun stamp(t: Long): String = SimpleDateFormat("h:mm a", Locale.US).format(Date(t))

    // ------------------------------------------------------------ waking up

    private val pending = ArrayList<String>()
    private val mind = Mutex()
    private var wake: Job? = null
    private var lastThought = 0L

    fun updateSenses(list: List<String>) { _senses.value = list }

    // ------------------------------------------------------------ the sensor diary

    /** Readings every 5 seconds, newest last; six hours are kept (about 4,300 rows). */
    private val diary = ArrayDeque<SenseLog.Sample>()
    private const val KEEP = 6 * 3_600_000L

    fun addSample(s: SenseLog.Sample) = synchronized(diary) {
        diary.addLast(s)
        while (diary.isNotEmpty() && s.at - diary.first().at > KEEP) diary.removeFirst()
    }

    fun samplesSince(t: Long): List<SenseLog.Sample> = synchronized(diary) { diary.filter { it.at >= t } }

    private fun diaryFile(c: Context) = java.io.File(c.filesDir, "sense_log.csv")

    /** Saved every few minutes so a restart doesn't lose the morning; it's Murmur's own private storage. */
    fun saveSamples(c: Context) = runCatching {
        val rows = synchronized(diary) { diary.toList() }
        diaryFile(c).writeText(rows.joinToString("\n") { s ->
            listOf(s.at, s.motion, s.speed, s.lat ?: "", s.lon ?: "", if (s.screen) 1 else 0, if (s.charging) 1 else 0, s.level, (s.place ?: "").replace(',', ' ')).joinToString(",")
        })
    }

    fun loadSamples(c: Context) = runCatching {
        val f = diaryFile(c)
        if (!f.exists() || synchronized(diary) { diary.isNotEmpty() }) return@runCatching
        val now = System.currentTimeMillis()
        f.readLines().mapNotNull { line ->
            val p = line.split(',')
            if (p.size < 9) null else SenseLog.Sample(p[0].toLong(), p[1], p[2].toFloat(), p[3].toDoubleOrNull(), p[4].toDoubleOrNull(), p[5] == "1", p[6] == "1", p[7].toInt(), p[8].ifBlank { null })
        }.filter { now - it.at < KEEP }.forEach { addSample(it) }
    }

    fun diarySize() = synchronized(diary) { diary.size }

    /** Wipes the timeline, plans and the sensor diary (goals and learned places stay). */
    fun clear(context: Context) {
        val c = context.applicationContext
        _timeline.value = emptyList()
        _plans.value = emptyList()
        synchronized(diary) { diary.clear() }
        diaryFile(c).delete()
        saveAll(c)
    }

    /** "This is Work": names the place the phone is at now, so it stops guessing. */
    fun nameCurrentPlace(context: Context, name: String): Boolean {
        val c = context.applicationContext
        val n = name.trim().lowercase().ifBlank { return false }
        val (lat, lon) = AmbientService.lastFix ?: return false
        AmbientService.places.nameHere(lat, lon, System.currentTimeMillis(), n)
        AmbientService.savePlaces(c)
        log(c, Kind.JOURNAL, "You named this place \"$n\".")
        event(c, "The user named the current place \"$n\"")
        return true
    }

    fun currentPlace(): String? = AmbientService.places.current?.let { AmbientService.places.label(it) }

    /**
     * A regular check-in. If the diary shows nothing changed since the last thought (same motion,
     * same place, no charging change, no new texts) and no plan or reminder is coming due, the
     * model isn't woken at all.
     */
    fun checkIn(context: Context) {
        val c = context.applicationContext
        if (!on(c)) return
        val now = System.currentTimeMillis()
        val since = samplesSince(lastThought)
        val waiting = synchronized(pending) { pending.isNotEmpty() }
        val dueSoon = reminders(c).any { it.at - now < 15 * 60_000L } || _plans.value.isNotEmpty() && now - lastThought > 30 * 60_000L
        if (!waiting && !dueSoon && lastThought > 0 && !SenseLog.changed(since)) return
        synchronized(pending) { pending += "${stamp(now)} Regular check-in" }
        wake?.cancel()
        wake = MurmurApp.scope.launch { think(c) }
    }

    /**
     * Something changed. Urgent events wake the Brain at once; others gather for up to 10 minutes
     * so a burst of changes becomes one thought.
     */
    fun event(context: Context, what: String, urgent: Boolean = false) {
        val c = context.applicationContext
        if (!on(c)) return
        synchronized(pending) { pending += "${stamp(System.currentTimeMillis())} $what" }
        if (what != "Regular check-in") log(c, Kind.SENSE, what)
        val gap = System.currentTimeMillis() - lastThought
        val gapMs = pace(c).gapMinutes * 60_000L
        val wait = if (urgent || gap > gapMs) 20_000L else gapMs - gap
        if (wake?.isActive == true && !urgent) return
        wake?.cancel()
        wake = MurmurApp.scope.launch { delay(wait); think(c) }
    }

    /** Thinks once about everything sensed since the last thought. */
    suspend fun think(c: Context) = mind.withLock {
        if (!on(c) || !Brain.ready(c)) return@withLock
        if (thoughtsToday(c) >= pace(c).daily) { log(c, Kind.THOUGHT, "Resting: reached today's limit of ${pace(c).daily} thoughts."); return@withLock }
        val events = synchronized(pending) { pending.toList().also { pending.clear() } }
        val now = System.currentTimeMillis()
        // What the diary shows since the last thought (at least the last 30 minutes), with real times.
        val from = minOf(if (lastThought > 0) lastThought else now - 30 * 60_000L, now - 30 * 60_000L)
        val situation = AmbientMind.Situation(
            time = SimpleDateFormat("EEEE h:mm a", Locale.US).format(Date(now)),
            senses = _senses.value,
            events = events,
            timeline = SenseLog.condense(samplesSince(from), now),
            lastThought = if (lastThought > 0) stamp(lastThought) else null,
            goals = goals(c),
            plans = _plans.value.map { "${it.whenText}: ${it.what} (planned ${stamp(it.made)})" },
            journal = journal(c),
            texts = _timeline.value.filter { it.kind == Kind.SENSE && it.text.startsWith("New text from") && now - it.at < 2 * 3_600_000L }
                .takeLast(5).map { "${stamp(it.at)} ${it.text.removePrefix("New text from ").let { t -> "from $t" }}" },
            done = reminders(c).map { "reminder set for ${stamp(it.at)}: ${it.text}" } +
                _asks.value.map { "waiting for the user's OK to text ${it.who}: ${it.message}" } +
                _timeline.value.filter { it.kind == Kind.ACT && now - it.at < 4 * 3_600_000L && !it.text.startsWith("Did nothing") }
                    .takeLast(6).map { "${stamp(it.at)} ${it.text.replaceFirstChar { ch -> ch.lowercase() }}" },
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
        val t = AmbientMind.allowed(AmbientMind.parse(answer), prompt, goals(c))
        if (t.observe.isNotBlank() || t.thought.isNotBlank()) log(c, Kind.THOUGHT, listOf(t.observe, t.thought).filter { it.isNotBlank() }.joinToString(" "))
        // Plans persist; only genuinely new ones are added (and shown in the timeline).
        val (kept, added) = AmbientMind.mergePlans(
            _plans.value.filter { now - it.made < 12 * 3_600_000L }.map { it.whenText to it.what }, t.plans, java.util.Calendar.getInstance(),
        )
        _plans.value = kept.map { (w, x) -> _plans.value.firstOrNull { it.whenText == w && it.what == x } ?: Plan(now, w, x) }
        added.forEach { (w, x) -> log(c, Kind.PLAN, "$w: $x") }
        // Code's memory: no reminder or message that repeats one pending or said in the last 2 hours.
        val said = _timeline.value.filter { it.kind == Kind.ACT && now - it.at < 2 * 3_600_000L }.map { it.text.substringAfter(": ") }
        val received = _timeline.value.filter { it.kind == Kind.SENSE && it.text.startsWith("New text from") && now - it.at < 6 * 3_600_000L }
            .map { it.text.substringAfter(": ").trim('"') }
        val asked = _asks.value.map { it.message } + _timeline.value.filter { it.kind == Kind.ASK && now - it.at < 6 * 3_600_000L }.map { it.text.substringAfter(": ").substringBefore(" (waiting").trim('"') }
        val acts = AmbientMind.notRepeated(t.acts, reminders(c).map { it.text }, said, asked, received)
        acts.forEach { act(c, it) }
        saveAll(c)
        AmbientService.refreshNotification(c, t.thought.ifBlank { t.observe })
    }

    // ------------------------------------------------------------ doing

    private fun act(c: Context, a: AmbientMind.Act) {
        when (a) {
            is AmbientMind.Act.Notify -> { alert(c, a.text); log(c, Kind.ACT, "Told you: ${a.text}") }
            is AmbientMind.Act.Journal -> log(c, Kind.JOURNAL, a.text)
            is AmbientMind.Act.Remind -> {
                val at = AmbientMind.remindAt(a.whenText, java.util.Calendar.getInstance())
                when {
                    at == null -> log(c, Kind.ACT, "Couldn't tell when \"${a.whenText}\" is, so no reminder was set.")
                    at - System.currentTimeMillis() < 2 * 60_000L -> { alert(c, a.text); log(c, Kind.ACT, "Told you: ${a.text}") }
                    else -> { saveReminders(c, reminders(c) + Reminder(at, a.text)); log(c, Kind.ACT, "Will remind you at ${stamp(at)}: ${a.text}") }
                }
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
            // Alarm goals ring in the Clock app; everything else with a time gets a notification right on the minute.
            if (AmbientMind.goalTime(g) != hm || AmbientMind.isAlarmGoal(g)) continue
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
