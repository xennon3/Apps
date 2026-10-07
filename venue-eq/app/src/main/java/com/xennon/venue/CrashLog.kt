package com.xennon.venue

import android.app.Application
import android.content.Context
import android.util.Log
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Remembers the last crash or failure so the app can show it, no adb needed. */
object CrashLog {
    private const val PREFS = "venue-crash"

    fun record(context: Context, where: String, t: Throwable) {
        val sw = StringWriter()
        t.printStackTrace(PrintWriter(sw))
        val stamp = SimpleDateFormat("MMM d HH:mm:ss", Locale.US).format(Date())
        val text = "$stamp, $where\n" + sw.toString().lines().take(14).joinToString("\n")
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString("last", text).commit()
    }

    fun last(context: Context): String? =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString("last", null)

    fun clear(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().remove("last").commit()
    }
}

class VenueApp : Application() {
    override fun onCreate() {
        super.onCreate()
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, t ->
            try {
                Log.e("VenueEffects", "crash on ${thread.name}", t)
                CrashLog.record(this, "crash on ${thread.name}", t)
            } catch (_: Throwable) {
            }
            previous?.uncaughtException(thread, t)
        }
    }
}
