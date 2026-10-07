package com.xennon.venue

import android.content.Context
import android.content.pm.PackageManager
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.Process
import android.util.Log
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit

/**
 * Finds audio sessions of apps that never announce them (YouTube, Chrome, games...).
 *
 * Reads Android's own list of active players from `dumpsys audio`. That needs the DUMP
 * permission, which a normal app can't request but can be granted once over adb:
 *   adb shell pm grant com.xennon.venue android.permission.DUMP
 */
class SessionScanner(
    private val context: Context,
    /** Called on the main thread with session id -> package of every live player. */
    private val onScan: (Map<Int, String>) -> Unit,
) {
    private val thread = HandlerThread("venue-scan").apply { start() }
    private val worker = Handler(thread.looper)
    private val main = Handler(Looper.getMainLooper())
    @Volatile private var running = false

    private val tick = object : Runnable {
        override fun run() {
            if (!running) return
            val found = scan(context)
            main.post { if (running && found != null) onScan(found) }
            worker.postDelayed(this, INTERVAL_MS)
        }
    }

    fun start() {
        running = true
        worker.post(tick)
    }

    fun stop() {
        running = false
        worker.removeCallbacksAndMessages(null)
        thread.quitSafely()
    }

    data class Player(val uid: Int, val session: Int, val state: String)

    enum class Status { NOT_STARTED, NO_PERMISSION, WORKING, NO_SESSION_IDS, FAILED }

    companion object {
        private const val TAG = "VenueScan"
        private const val INTERVAL_MS = 2000L
        const val GRANT_COMMAND = "adb shell pm grant com.xennon.venue android.permission.DUMP"

        @Volatile var status = Status.NOT_STARTED
            private set

        /** Human-readable result of the last scan, shown in the app for troubleshooting. */
        @Volatile var lastReport = "No scan yet"
            private set

        /** Runs one scan (blocking; call off the main thread). Returns null if it couldn't run. */
        fun scan(context: Context): Map<Int, String>? {
            val time = SimpleDateFormat("HH:mm:ss", Locale.US).format(Date())
            if (!hasPermission(context)) {
                status = Status.NO_PERMISSION
                lastReport = "$time: permission not granted yet"
                return null
            }
            val dump = try {
                dumpsys("audio")
            } catch (e: Exception) {
                Log.w(TAG, "dumpsys failed", e)
                status = Status.FAILED
                lastReport = "$time: couldn't run dumpsys: $e"
                return null
            }
            val players = parse(dump, Process.myUid())
            val pm = context.packageManager
            val found = players.associate { p ->
                p.session to (pm.getPackagesForUid(p.uid)?.firstOrNull() ?: "uid ${p.uid}")
            }
            status = if (dump.contains("u/pid") && players.isEmpty() && !dump.contains("session", true)) {
                Status.NO_SESSION_IDS
            } else {
                Status.WORKING
            }
            lastReport = buildString {
                append("$time: ${dump.length} chars read, ${players.size} player(s) found")
                players.forEach { p -> append("\n• ${found[p.session]}  session ${p.session}  ${p.state}") }
                if (players.isEmpty()) {
                    val samples = dump.lineSequence().filter { "u/pid" in it }.take(2).toList()
                    if (samples.isEmpty()) {
                        append("\nNo player lines. Start of output:\n" + dump.trim().take(300))
                    } else {
                        append("\nPlayer lines look like:")
                        samples.forEach { append("\n" + it.trim().take(300)) }
                    }
                }
            }
            Log.i(TAG, lastReport)
            return found
        }

        private fun dumpsys(service: String): String {
            val proc = ProcessBuilder("dumpsys", service).redirectErrorStream(true).start()
            val out = proc.inputStream.bufferedReader().use { it.readText() }
            proc.waitFor(3, TimeUnit.SECONDS)
            return out
        }

        fun hasPermission(context: Context) =
            context.checkSelfPermission("android.permission.DUMP") == PackageManager.PERMISSION_GRANTED

        private val UID = Regex("""u/pid:\s*(\d+)/""")
        private val STATE = Regex("""state:\s*(\w+)""")
        private val SESSION = Regex("""session(?:Id)?\s*[:=]\s*(\d+)""", RegexOption.IGNORE_CASE)

        /**
         * Pulls live players out of the playback-activity section of `dumpsys audio`, whose
         * lines look like: "AudioPlaybackConfiguration piid:79 ... u/pid:10145/5321
         * state:started attr:AudioAttributes: ... sessionId:1937 ...".
         */
        fun parse(dump: String, ownUid: Int): List<Player> {
            val players = LinkedHashMap<Int, Player>()
            // Prefer the live player list; the event history below it mentions dead players too.
            val live = dump.lineSequence().any { "AudioPlaybackConfiguration" in it && "u/pid" in it }
            for (line in dump.lineSequence()) {
                if ("u/pid" !in line) continue
                if (live && "AudioPlaybackConfiguration" !in line) continue
                val uid = UID.find(line)?.groupValues?.get(1)?.toIntOrNull() ?: continue
                val state = STATE.find(line)?.groupValues?.get(1) ?: continue
                val session = SESSION.find(line)?.groupValues?.get(1)?.toIntOrNull() ?: continue
                if (uid == ownUid || session <= 0 || state == "released") continue
                players[session] = Player(uid, session, state)
            }
            return players.values.toList()
        }
    }
}
