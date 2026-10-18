package app.murmur

import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.content.Context
import android.os.Build
import app.murmur.brain.Brain
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * If Murmur closes unexpectedly, the next launch says why, with details the user can copy and
 * send. Covers crashes in Murmur's own code (caught as they happen) and the ones no code can
 * catch: native crashes, and Android ending the app for using too much memory (read back from
 * Android's record of why the process last exited).
 */
object CrashReport {

    private fun file(c: Context) = File(c.filesDir, "last_crash.txt")
    private fun prefs(c: Context) = c.getSharedPreferences("crash", Context.MODE_PRIVATE)

    fun install(context: Context) {
        val c = context.applicationContext
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, e ->
            runCatching {
                val trace = e.stackTraceToString().lines().take(40).joinToString("\n")
                file(c).writeText("Crash in Murmur's code (thread ${thread.name})\n$trace")
            }
            previous?.uncaughtException(thread, e)
        }
    }

    /** Works out what happened last time. Call before anything resets Murmur Brain's state. */
    fun collect(context: Context) {
        val c = context.applicationContext
        val header = "Murmur ${version(c)} · Android ${Build.VERSION.RELEASE} · ${Build.MANUFACTURER} ${Build.MODEL}"
        val gpu = Brain.gpuSessionDied(c)

        file(c).takeIf { it.exists() }?.let { f ->
            save(c, header + "\n" + f.readText())
            f.delete()
            markExitSeen(c)
            return
        }
        if (Build.VERSION.SDK_INT < 30) return
        val exit = runCatching {
            c.getSystemService(ActivityManager::class.java).getHistoricalProcessExitReasons(c.packageName, 0, 1).firstOrNull()
        }.getOrNull() ?: return
        if (exit.timestamp <= prefs(c).getLong("seen", 0)) return
        markExitSeen(c, exit.timestamp)

        val reason = when (exit.reason) {
            ApplicationExitInfo.REASON_CRASH_NATIVE -> "Native crash"
            ApplicationExitInfo.REASON_LOW_MEMORY -> "Android ended it to free memory"
            ApplicationExitInfo.REASON_ANR -> "It stopped responding"
            ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USAGE -> "Android ended it for using too much"
            ApplicationExitInfo.REASON_SIGNALED ->
                if (exit.importance <= ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND) "Ended by the system while open (signal ${exit.status})" else return
            ApplicationExitInfo.REASON_CRASH -> "Crash"
            else -> return
        }
        val details = buildString {
            appendLine(header)
            appendLine(reason + (exit.description?.let { ": $it" } ?: ""))
            appendLine("When: " + SimpleDateFormat("MMM d HH:mm:ss", Locale.US).format(Date(exit.timestamp)))
            appendLine("Memory at the end: ${exit.pss / 1024} MB (pss), ${exit.rss / 1024} MB (rss)")
            if (gpu) appendLine("Murmur Brain was running on the graphics chip.")
        }
        if (gpu) Brain.blameGpu(c)
        save(c, details.trim() + if (gpu) "\n\nMurmur Brain has switched to the processor, which is steadier." else "")
    }

    private fun markExitSeen(c: Context, at: Long = System.currentTimeMillis()) =
        prefs(c).edit().putLong("seen", at).apply()

    private fun save(c: Context, text: String) = prefs(c).edit().putString("pending", text).apply()

    fun pending(context: Context): String? = prefs(context).getString("pending", null)

    fun dismiss(context: Context) = prefs(context).edit().remove("pending").apply()

    private fun version(c: Context) =
        runCatching { c.packageManager.getPackageInfo(c.packageName, 0).versionName }.getOrNull() ?: "?"
}
