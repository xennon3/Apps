package app.murmur.brain

import android.Manifest
import android.annotation.SuppressLint
import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.BatteryManager
import android.os.Build
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import app.murmur.MainActivity
import app.murmur.R
import app.murmur.brain.tools.PhoneMath
import org.json.JSONArray
import java.util.Calendar
import java.util.Locale
import kotlin.math.roundToInt

/**
 * A battery reading every half hour (an inexact alarm, so it never wakes the phone on its own),
 * kept for a week on the phone. From it Murmur learns how fast the battery usually drains and
 * when it usually gets charged, and the battery coach speaks up when something is off.
 */
object BatteryLog {
    private const val HOUR = 3_600_000L
    const val CHANNEL = "battery"

    private fun prefs(c: Context) = c.getSharedPreferences("battery_log", Context.MODE_PRIVATE)

    fun samples(c: Context): List<PhoneMath.Sample> = runCatching {
        val a = JSONArray(prefs(c).getString("s", "[]"))
        List(a.length()) { i -> a.getJSONArray(i).let { PhoneMath.Sample(it.getLong(0), it.getInt(1), it.getInt(2) == 1) } }
    }.getOrDefault(emptyList())

    fun record(c: Context, level: Int, charging: Boolean) {
        val now = System.currentTimeMillis()
        val list = samples(c).filter { it.at > now - 7 * 24 * HOUR }
        // Readings minutes apart add nothing but noise.
        if (list.lastOrNull()?.let { now - it.at < 10 * 60_000L && it.charging == charging } == true) return
        val keep = list + PhoneMath.Sample(now, level, charging)
        prefs(c).edit().putString("s", JSONArray(keep.map { JSONArray(listOf(it.at, it.level, if (it.charging) 1 else 0)) }).toString()).apply()
    }

    /** Takes a reading now, notes the step counter, and lets the coach look. */
    fun sample(c: Context) {
        val i = c.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED)) ?: return
        val level = (i.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) * 100f / i.getIntExtra(BatteryManager.EXTRA_SCALE, 100)).roundToInt()
        val status = i.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
        val charging = status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL
        val temp = i.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0) / 10f
        record(c, level, charging)
        if (BrainAccess.on(c, BrainAccess.Source.MOTION)) stepCounter(c)?.let { noteSteps(c, it) }
        if (coachOn(c)) coach(c, level, charging, temp)
    }

    // ------------------------------------------------------------ schedule

    fun schedule(c: Context) {
        val am = c.getSystemService(AlarmManager::class.java)
        val pi = PendingIntent.getBroadcast(c, 7301, Intent(c, BatteryLogReceiver::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        if (!wanted(c)) { am.cancel(pi); return }
        am.setInexactRepeating(AlarmManager.ELAPSED_REALTIME, SystemClock.elapsedRealtime() + 60_000L, AlarmManager.INTERVAL_HALF_HOUR, pi)
    }

    /** The log runs while the Brain may read phone status or the coach is on. */
    private fun wanted(c: Context) = coachOn(c) || BrainAccess.on(c, BrainAccess.Source.PHONE) || BrainAccess.on(c, BrainAccess.Source.MOTION)

    fun coachOn(c: Context) = prefs(c).getBoolean("coach", false)
    fun setCoach(c: Context, v: Boolean) { prefs(c).edit().putBoolean("coach", v).apply(); schedule(c); if (v) sample(c) }
    fun healthReminder(c: Context) = prefs(c).getBoolean("eighty", false)
    fun setHealthReminder(c: Context, v: Boolean) = prefs(c).edit().putBoolean("eighty", v).apply()

    // ------------------------------------------------------------ steps

    /** The step counter's total since the phone started, or null without the sensor or permission. */
    fun stepCounter(c: Context): Long? {
        if (Build.VERSION.SDK_INT >= 29 && ContextCompat.checkSelfPermission(c, Manifest.permission.ACTIVITY_RECOGNITION) != PackageManager.PERMISSION_GRANTED) return null
        val sm = c.getSystemService(SensorManager::class.java)
        val sensor = sm.getDefaultSensor(Sensor.TYPE_STEP_COUNTER) ?: return null
        var value: Long? = null
        val latch = java.util.concurrent.CountDownLatch(1)
        val l = object : SensorEventListener {
            override fun onSensorChanged(e: SensorEvent) { value = e.values[0].toLong(); latch.countDown() }
            override fun onAccuracyChanged(s: Sensor?, a: Int) {}
        }
        sm.registerListener(l, sensor, SensorManager.SENSOR_DELAY_NORMAL, android.os.Handler(android.os.Looper.getMainLooper()))
        latch.await(3, java.util.concurrent.TimeUnit.SECONDS)
        sm.unregisterListener(l)
        return value
    }

    private fun today() = Calendar.getInstance().let { it.get(Calendar.YEAR) * 1000 + it.get(Calendar.DAY_OF_YEAR) }

    private fun noteSteps(c: Context, total: Long) {
        val p = prefs(c)
        // The counter restarts at zero when the phone restarts; start the day's count again then.
        if (p.getInt("step_day", 0) != today() || total < p.getLong("step_base", 0)) {
            p.edit().putInt("step_day", today()).putLong("step_base", total).putLong("step_set_at", System.currentTimeMillis()).apply()
        }
    }

    /** The counter's value at the first reading today, or null if that reading is the one just taken. */
    fun stepsAtStartOfDay(c: Context, total: Long): Long? {
        noteSteps(c, total)
        val p = prefs(c)
        if (System.currentTimeMillis() - p.getLong("step_set_at", 0) < 2 * 60_000L) return null
        return p.getLong("step_base", -1).takeIf { it >= 0 }
    }

    // ------------------------------------------------------------ the coach

    private fun once(c: Context, key: String, every: Long): Boolean {
        val now = System.currentTimeMillis()
        if (now - prefs(c).getLong("said_$key", 0) < every) return false
        prefs(c).edit().putLong("said_$key", now).apply()
        return true
    }

    private fun coach(c: Context, level: Int, charging: Boolean, temp: Float) {
        val now = System.currentTimeMillis()
        val list = samples(c)
        if (temp >= 43f && once(c, "hot", 3 * HOUR)) {
            notify(c, 1, "Your phone is hot (${temp.roundToInt()}°C)", "Heat wears batteries out. Take it out of the sun or a case, and avoid gaming or fast charging until it cools.")
        }
        if (charging) {
            if (healthReminder(c) && level >= 80 && once(c, "eighty", 6 * HOUR)) {
                notify(c, 2, "Battery at $level%", "Unplugging around 80% is gentler on the battery over the years.")
            }
            return
        }
        val drain = PhoneMath.drainPerHour(list, now, window = 2 * HOUR) ?: return
        val typical = PhoneMath.typicalDrain(list, now)
        if (level < 85 && drain >= 6 && (typical == null || drain >= typical * 2) && once(c, "fast", 6 * HOUR)) {
            val top = runCatching { if (BrainAccess.on(c, BrainAccess.Source.SCREEN_TIME)) PhoneSensors(c).topApps(1).firstOrNull()?.first else null }.getOrNull()
            val until = PhoneMath.hoursLeft(level, drain)?.let { " At this rate it's empty in about ${PhoneMath.duration(it)}." } ?: ""
            notify(c, 3, "Battery draining fast: ${drain.roundToInt()}% an hour",
                (typical?.let { "That's about ${"%.0f".format(Locale.US, drain / it)}x your usual. " } ?: "") + until +
                    (top?.let { " $it is your most-used app today." } ?: " Check Settings > Battery for what's using it."))
        }
        val usual = PhoneMath.usualChargeHour(list, now) { Calendar.getInstance().apply { timeInMillis = it }.get(Calendar.HOUR_OF_DAY) } ?: return
        val left = PhoneMath.hoursLeft(level - 5, drain) ?: return
        val cal = Calendar.getInstance()
        val nowHour = cal.get(Calendar.HOUR_OF_DAY) + cal.get(Calendar.MINUTE) / 60.0
        val untilCharge = ((usual - nowHour) + 24) % 24
        if (left < untilCharge && untilCharge < 14 && once(c, "short", 12 * HOUR)) {
            val emptyAt = Calendar.getInstance().apply { add(Calendar.MINUTE, (left * 60).toInt()) }
            notify(c, 4, "Battery may not last until you charge",
                "At $level% and ${drain.roundToInt()}% an hour, it runs low around ${android.text.format.DateFormat.getTimeFormat(c).format(emptyAt.time)}, " +
                    "before you usually plug in (around ${if (usual == 0) "midnight" else if (usual < 12) "$usual AM" else if (usual == 12) "noon" else "${usual - 12} PM"}). Battery saver would stretch it.")
        }
    }

    @SuppressLint("MissingPermission")
    private fun notify(c: Context, id: Int, title: String, text: String) {
        if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(c, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return
        c.getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL, "Battery coach", NotificationManager.IMPORTANCE_DEFAULT).apply { description = "Unusual drain, heat, and whether the battery will last" },
        )
        val open = PendingIntent.getActivity(c, 7302, Intent(c, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        runCatching {
            NotificationManagerCompat.from(c).notify(7300 + id, NotificationCompat.Builder(c, CHANNEL)
                .setSmallIcon(R.drawable.ic_stat_murmur).setContentTitle(title).setContentText(text)
                .setStyle(NotificationCompat.BigTextStyle().bigText(text)).setContentIntent(open).setAutoCancel(true).build())
        }
    }
}

class BatteryLogReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val pending = goAsync()
        Thread {
            try { BatteryLog.sample(context.applicationContext) } catch (_: Exception) {} finally { pending.finish() }
        }.start()
    }
}
