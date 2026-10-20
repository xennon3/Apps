package app.murmur

import android.Manifest
import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.PowerManager
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import app.murmur.brain.Ambient
import app.murmur.brain.BatteryLog
import app.murmur.brain.Brain
import app.murmur.brain.BrainAccess
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * "Send diagnostics": one text file with what a USB cable would show (the app's own logs, why
 * Android last stopped it, permissions, Brain and Ambient state, the sensor diary), for the user
 * to share when something misbehaves. Locations are rounded to about 100 m; no message text from
 * the inbox is included, only what Ambient mode already shows in the Mind window.
 */
object Diagnostics {
    private fun stamp(t: Long) = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date(t))

    fun build(context: Context): File {
        val c = context.applicationContext
        val out = StringBuilder()
        fun section(name: String) = out.append("\n===== ").append(name).append(" =====\n")
        val pkg = c.packageManager.getPackageInfo(c.packageName, 0)

        section("Device")
        out.append("Murmur ${pkg.versionName} (${if (Build.VERSION.SDK_INT >= 28) pkg.longVersionCode else 0}) · ${Build.MANUFACTURER} ${Build.MODEL} · Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})\n")
        val am = c.getSystemService(ActivityManager::class.java)
        val mi = ActivityManager.MemoryInfo().also { am.getMemoryInfo(it) }
        out.append("Memory: ${mi.availMem / 1_000_000} MB free of ${mi.totalMem / 1_000_000} MB, low=${mi.lowMemory}\n")
        val pm = c.getSystemService(PowerManager::class.java)
        out.append("Battery optimisation: ${if (pm.isIgnoringBatteryOptimizations(c.packageName)) "unrestricted" else "optimised (Android may stop Ambient mode)"}; saver=${pm.isPowerSaveMode}\n")
        out.append("Generated: ${stamp(System.currentTimeMillis())}\n")

        section("Permissions")
        listOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION, Manifest.permission.ACTIVITY_RECOGNITION,
            Manifest.permission.POST_NOTIFICATIONS, Manifest.permission.RECORD_AUDIO, Manifest.permission.READ_CONTACTS, Manifest.permission.READ_SMS)
            .forEach { p -> out.append("${p.substringAfterLast('.')}: ${ContextCompat.checkSelfPermission(c, p) == PackageManager.PERMISSION_GRANTED}\n") }
        BrainAccess.Source.entries.forEach { s -> out.append("Brain may use ${s.label}: wanted=${BrainAccess.wanted(c, s)} granted=${BrainAccess.granted(c, s)}\n") }

        section("Brain")
        out.append("state=${Brain.state.value} ready=${Brain.ready(c)} model=${Brain.model(c).name} workspace=${Brain.workspace()} fastWriting=${Brain.fastWriting(c)} think=${Brain.thinkMode(c)}\n")
        Brain.speed.value?.let { out.append("speed: $it\n") }

        section("Ambient")
        out.append("on=${Ambient.on(c)} running=${Ambient.running.value} pace=${Ambient.pace(c)} thoughtsToday=${Ambient.thoughtsToday(c)} diaryRows=${Ambient.diarySize()}\n")
        out.append("place now: ${Ambient.currentPlace() ?: "none"}\n")
        out.append("goals: ${Ambient.goals(c)}\n")
        out.append("plans: ${Ambient.plans.value}\n")
        out.append("reminders: ${Ambient.reminders(c).map { stamp(it.at) + " " + it.text }}\n")
        out.append("senses now:\n"); Ambient.senses.value.forEach { out.append("  $it\n") }
        out.append("timeline (oldest first):\n")
        Ambient.timeline.value.forEach { e -> out.append("  ${stamp(e.at)} ${e.kind} ${e.text}\n") }

        section("Why Android stopped Murmur recently")
        if (Build.VERSION.SDK_INT >= 30) runCatching {
            am.getHistoricalProcessExitReasons(c.packageName, 0, 15).forEach { x ->
                out.append("${stamp(x.timestamp)} reason=${x.reason} importance=${x.importance} pss=${x.pss / 1000} MB ${x.description ?: ""}\n")
            }
        } else out.append("(Android 11+ only)\n")
        File(c.filesDir, "last_crash.txt").takeIf { it.exists() }?.let { out.append("last crash file:\n").append(it.readText().take(8000)).append('\n') }

        section("Battery log (last 48 readings)")
        BatteryLog.samples(c).takeLast(48).forEach { out.append("${stamp(it.at)} ${it.level}% charging=${it.charging}\n") }

        section("Sensor diary (every 5 s; locations rounded to ~100 m)")
        out.append("time,motion,speed_mps,lat,lon,screen,charging,level,place\n")
        Ambient.samplesSince(0).forEach { s ->
            out.append("${stamp(s.at)},${s.motion},${"%.1f".format(Locale.US, s.speed)},${s.lat?.let { "%.3f".format(Locale.US, it) } ?: ""},${s.lon?.let { "%.3f".format(Locale.US, it) } ?: ""}," +
                "${if (s.screen) 1 else 0},${if (s.charging) 1 else 0},${s.level},${s.place ?: ""}\n")
        }

        section("App log (this app only, newest last)")
        runCatching {
            val p = Runtime.getRuntime().exec(arrayOf("logcat", "-d", "-v", "time", "-t", "4000", "--pid", android.os.Process.myPid().toString()))
            out.append(p.inputStream.bufferedReader().readText().takeLast(400_000))
        }.onFailure { out.append("(couldn't read the log: ${it.message})\n") }

        val dir = File(c.cacheDir, "diagnostics").also { it.mkdirs(); it.listFiles()?.forEach { f -> f.delete() } }
        return File(dir, "murmur-diagnostics-${SimpleDateFormat("yyyyMMdd-HHmm", Locale.US).format(Date())}.txt").also { it.writeText(out.toString()) }
    }

    /** Builds the file and opens Android's share sheet (save to Files, Drive, email, or attach in a chat). */
    fun share(context: Context) {
        val f = build(context)
        val uri = FileProvider.getUriForFile(context, "app.murmur.mms", f)
        val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_STREAM, uri)
            .putExtra(Intent.EXTRA_SUBJECT, f.name).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        context.startActivity(Intent.createChooser(send, "Send Murmur diagnostics").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }
}
