package app.murmur.brain

import android.Manifest
import android.annotation.SuppressLint
import android.app.ActivityManager
import android.app.AppOpsManager
import android.app.NotificationManager
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.location.Geocoder
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.BatteryManager
import android.os.Build
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.os.Process
import android.os.StatFs
import android.os.SystemClock
import android.provider.AlarmClock
import android.provider.Settings
import android.telephony.TelephonyManager
import androidx.core.content.ContextCompat
import app.murmur.brain.tools.PhoneAction
import app.murmur.brain.tools.PhoneController
import app.murmur.brain.tools.PhoneMath
import app.murmur.brain.tools.PhoneReader
import app.murmur.brain.tools.PhoneTopic
import java.util.Calendar
import java.util.Locale
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.math.roundToInt

/**
 * The phone's own sensors and status, read on request for Murmur Brain. Each reading is
 * allowed by a switch in Settings (and Android's permission where one is needed), runs on
 * the phone, and comes back as a finished sentence.
 */
class PhoneSensors(context: Context) : PhoneReader {
    private val c = context.applicationContext
    private val metric = Locale.getDefault().country !in setOf("US", "LR", "MM", "GB")

    override fun read(topic: PhoneTopic): String = runCatching {
        when (topic) {
            PhoneTopic.BATTERY -> need(BrainAccess.Source.PHONE) ?: battery()
            PhoneTopic.SPEED -> need(BrainAccess.Source.LOCATION) ?: speed()
            PhoneTopic.LOCATION -> need(BrainAccess.Source.LOCATION) ?: location()
            PhoneTopic.STORAGE -> need(BrainAccess.Source.PHONE) ?: storage()
            PhoneTopic.NETWORK -> need(BrainAccess.Source.PHONE) ?: network()
            PhoneTopic.STEPS -> need(BrainAccess.Source.MOTION) ?: steps()
            PhoneTopic.SCREEN_TIME -> need(BrainAccess.Source.SCREEN_TIME) ?: screenTime()
            PhoneTopic.SURROUNDINGS -> need(BrainAccess.Source.PHONE) ?: surroundings()
            PhoneTopic.DEVICE -> need(BrainAccess.Source.PHONE) ?: device()
        }
    }.getOrElse { "Couldn't read that from the phone just now (${it.message ?: it.javaClass.simpleName})." }

    /** Null when allowed; otherwise what to tell the user. */
    private fun need(s: BrainAccess.Source): String? =
        if (BrainAccess.on(c, s)) null else "Murmur Brain isn't allowed to read ${s.label.lowercase()} yet. Tell the user they can turn on \"${s.label}\" in Settings, under What Murmur Brain can use."

    // ------------------------------------------------------------ battery

    fun battery(): String {
        val i = c.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED)) ?: return "Couldn't read the battery."
        val level = (i.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) * 100f / i.getIntExtra(BatteryManager.EXTRA_SCALE, 100)).roundToInt()
        val status = i.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
        val charging = status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL
        val plug = when (i.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0)) {
            BatteryManager.BATTERY_PLUGGED_AC -> "a charger"; BatteryManager.BATTERY_PLUGGED_USB -> "USB"
            BatteryManager.BATTERY_PLUGGED_WIRELESS -> "a wireless charger"; else -> null
        }
        val temp = i.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0) / 10f
        val health = when (i.getIntExtra(BatteryManager.EXTRA_HEALTH, 0)) {
            BatteryManager.BATTERY_HEALTH_OVERHEAT -> "overheating"; BatteryManager.BATTERY_HEALTH_DEAD -> "worn out"
            BatteryManager.BATTERY_HEALTH_COLD -> "too cold"; else -> null
        }
        val cycles = if (Build.VERSION.SDK_INT >= 34) i.getIntExtra(BatteryManager.EXTRA_CYCLE_COUNT, -1).takeIf { it > 0 } else null
        BatteryLog.record(c, level, charging)
        val samples = BatteryLog.samples(c)
        val now = System.currentTimeMillis()
        val parts = ArrayList<String>()
        parts += "Battery is at $level%" + (if (charging) ", charging from ${plug ?: "power"}" else ", not charging") + ", ${temp.roundToInt()}°C" +
            (if (temp >= 42) " (hot)" else "") + "."
        if (charging) {
            val bm = c.getSystemService(BatteryManager::class.java)
            val ms = if (Build.VERSION.SDK_INT >= 28) bm.computeChargeTimeRemaining() else -1
            if (ms > 0) parts += "Full in about ${PhoneMath.duration(ms / 3_600_000.0)}."
            if (level >= 80) parts += "Unplugging around 80% is gentler on the battery over the years."
        } else {
            val drain = PhoneMath.drainPerHour(samples, now)
            val typical = PhoneMath.typicalDrain(samples, now)
            if (drain != null) {
                parts += "It's using about ${"%.0f".format(Locale.US, drain)}% an hour right now" +
                    (typical?.let { t -> if (drain > t * 1.6) ", faster than your usual ${"%.0f".format(Locale.US, t)}%" else if (drain < t * 0.6) ", slower than your usual ${"%.0f".format(Locale.US, t)}%" else ", about your usual" } ?: "") + "."
                PhoneMath.hoursLeft(level, drain)?.let { parts += "At this rate it lasts about ${PhoneMath.duration(it)}." }
            } else parts += "Murmur hasn't watched it long enough yet to say how fast it's draining (it checks every half hour)."
            PhoneMath.usualChargeHour(samples, now) { Calendar.getInstance().apply { timeInMillis = it }.get(Calendar.HOUR_OF_DAY) }?.let { h ->
                parts += "You usually plug in around ${hourLabel(h)}."
            }
        }
        health?.let { parts += "Android reports the battery as $it." }
        cycles?.let { parts += "It has been through $it charge cycles." }
        if (BrainAccess.on(c, BrainAccess.Source.SCREEN_TIME)) topApps(3).takeIf { it.isNotEmpty() }?.let { apps ->
            parts += "Most-used apps today (the usual battery users): " + apps.joinToString(", ") { (n, m) -> "$n ${minutes(m)}" } + "."
        }
        val saver = c.getSystemService(android.os.PowerManager::class.java)?.isPowerSaveMode == true
        if (saver) parts += "Battery saver is on."
        return parts.joinToString(" ")
    }

    // ------------------------------------------------------------ location and speed

    @SuppressLint("MissingPermission")
    private fun fixes(seconds: Int, wantSpeed: Boolean): List<Location> {
        val lm = c.getSystemService(LocationManager::class.java)
        val fine = ContextCompat.checkSelfPermission(c, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        val providers = listOfNotNull(
            LocationManager.GPS_PROVIDER.takeIf { fine && lm.isProviderEnabled(it) },
            LocationManager.NETWORK_PROVIDER.takeIf { lm.isProviderEnabled(it) && !wantSpeed },
        ).ifEmpty { listOfNotNull(LocationManager.NETWORK_PROVIDER.takeIf { lm.isProviderEnabled(it) }) }
        if (providers.isEmpty()) return emptyList()
        val got = java.util.concurrent.CopyOnWriteArrayList<Location>()
        val done = CountDownLatch(1)
        val listener = LocationListener { loc ->
            got += loc
            if (!wantSpeed || loc.hasSpeed() || got.size >= 3) done.countDown()
        }
        val main = Handler(Looper.getMainLooper())
        main.post { providers.forEach { p -> runCatching { lm.requestLocationUpdates(p, 1000L, 0f, listener, Looper.getMainLooper()) } } }
        done.await(seconds.toLong(), TimeUnit.SECONDS)
        main.post { runCatching { lm.removeUpdates(listener) } }
        if (got.isEmpty()) providers.mapNotNull { runCatching { lm.getLastKnownLocation(it) }.getOrNull() }
            .filter { System.currentTimeMillis() - it.time < 10 * 60_000L }.maxByOrNull { it.time }?.let { got += it }
        return got
    }

    private fun speed(): String {
        val fs = fixes(12, wantSpeed = true)
        if (fs.isEmpty()) return "Couldn't get a GPS fix. Location may be off, or the phone may be indoors."
        val last = fs.last()
        val mps = when {
            last.hasSpeed() -> last.speed
            fs.size >= 2 -> { val a = fs[fs.size - 2]; val dt = (last.time - a.time) / 1000f; if (dt > 0) a.distanceTo(last) / dt else 0f }
            else -> return "Got your location, but not your speed yet. GPS needs a few seconds outdoors; ask again in a moment."
        }
        val heading = if (last.hasBearing() && mps > 1.5f) ", heading ${PhoneMath.compass(last.bearing)}" else ""
        val acc = if (last.hasAccuracy()) " GPS accuracy about ${last.accuracy.roundToInt()} m." else ""
        return "You're moving at ${PhoneMath.describeSpeed(mps, metric)}$heading.$acc"
    }

    private fun location(): String {
        val loc = fixes(10, wantSpeed = false).lastOrNull() ?: return "Couldn't get your location. Is location turned on?"
        val place = runCatching {
            @Suppress("DEPRECATION")
            Geocoder(c).getFromLocation(loc.latitude, loc.longitude, 1)?.firstOrNull()?.let { a ->
                listOfNotNull(a.thoroughfare?.let { (a.subThoroughfare?.plus(" ") ?: "") + it }, a.locality, a.adminArea).joinToString(", ")
            }
        }.getOrNull()?.takeIf { it.isNotBlank() }
        val alt = if (loc.hasAltitude()) " Altitude about ${loc.altitude.roundToInt()} m." else ""
        return (if (place != null) "You're near $place" else "You're at") +
            " (${"%.5f".format(Locale.US, loc.latitude)}, ${"%.5f".format(Locale.US, loc.longitude)}), accurate to about ${loc.accuracy.roundToInt()} m.$alt " +
            "Map: https://maps.google.com/?q=${loc.latitude},${loc.longitude}"
    }

    // ------------------------------------------------------------ storage, network, device

    private fun storage(): String {
        val st = StatFs(Environment.getDataDirectory().path)
        val free = st.availableBytes; val total = st.totalBytes
        val full = 100 - (free * 100 / total)
        val murmur = PhoneMath.bytes(Brain.MODELS.sumOf { m -> Brain.modelFile(c, m).takeIf { it.exists() }?.length() ?: 0L } + Meaning.bytesOnDisk(c))
        return "${PhoneMath.bytes(free)} free of ${PhoneMath.bytes(total)} ($full% full)." +
            (if (full >= 90) " That's nearly full; big videos, downloads and app caches are the usual culprits." else "") +
            " Murmur Brain's models use $murmur."
    }

    @SuppressLint("MissingPermission")
    private fun network(): String {
        val cm = c.getSystemService(ConnectivityManager::class.java)
        val caps = cm.getNetworkCapabilities(cm.activeNetwork)
        val airplane = Settings.Global.getInt(c.contentResolver, Settings.Global.AIRPLANE_MODE_ON, 0) == 1
        val parts = ArrayList<String>()
        if (airplane) parts += "Airplane mode is on."
        if (caps == null) parts += "No internet connection right now."
        else {
            val kind = when {
                caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "Wi-Fi"
                caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "mobile data"
                caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "Ethernet"
                else -> "a network"
            }
            val works = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
            val down = caps.linkDownstreamBandwidthKbps / 1000
            parts += "On $kind, " + (if (works) "internet working" else "but the internet isn't reachable") + (if (down > 0) ", roughly $down Mbps down" else "") + "." +
                (if (caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) " A VPN is on." else "")
        }
        val tm = c.getSystemService(TelephonyManager::class.java)
        val level = if (Build.VERSION.SDK_INT >= 28) tm.signalStrength?.level else null
        val carrier = tm.networkOperatorName?.takeIf { it.isNotBlank() }
        if (level != null) parts += "Mobile signal: ${PhoneMath.signalWord(level)}" + (carrier?.let { " ($it)" } ?: "") + "."
        return parts.joinToString(" ")
    }

    private fun device(): String {
        val am = c.getSystemService(ActivityManager::class.java)
        val mi = ActivityManager.MemoryInfo().also { am.getMemoryInfo(it) }
        val audio = c.getSystemService(AudioManager::class.java)
        val ringer = when (audio.ringerMode) { AudioManager.RINGER_MODE_SILENT -> "silent"; AudioManager.RINGER_MODE_VIBRATE -> "vibrate"; else -> "ring" }
        val dnd = c.getSystemService(NotificationManager::class.java).currentInterruptionFilter > NotificationManager.INTERRUPTION_FILTER_ALL
        val heads = audio.getDevices(AudioManager.GET_DEVICES_OUTPUTS).filter {
            it.type in setOf(AudioDeviceInfo.TYPE_WIRED_HEADPHONES, AudioDeviceInfo.TYPE_WIRED_HEADSET, AudioDeviceInfo.TYPE_BLUETOOTH_A2DP, AudioDeviceInfo.TYPE_USB_HEADSET)
        }.map { it.productName.toString() }.distinct()
        val up = SystemClock.elapsedRealtime() / 3_600_000.0
        val media = audio.getStreamVolume(AudioManager.STREAM_MUSIC) * 100 / audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1)
        return "${Build.MANUFACTURER.replaceFirstChar { it.uppercase() }} ${Build.MODEL}, Android ${Build.VERSION.RELEASE}. " +
            "${PhoneMath.bytes(mi.availMem)} of ${PhoneMath.bytes(mi.totalMem)} memory free. Up for ${PhoneMath.duration(up)} since the last restart. " +
            "Ringer: $ringer" + (if (dnd) ", Do Not Disturb on" else "") + ". Media volume $media%. " +
            (if (heads.isEmpty()) "No headphones connected." else "Connected: ${heads.joinToString()}.")
    }

    // ------------------------------------------------------------ steps, screen time, sensors

    private fun steps(): String {
        val total = BatteryLog.stepCounter(c) ?: return "This phone doesn't have a step counter, or it hasn't reported yet."
        val base = BatteryLog.stepsAtStartOfDay(c, total)
        return if (base != null) "About ${"%,d".format(total - base)} steps today."
        else "${"%,d".format(total)} steps since the phone last restarted. (Murmur starts counting today's steps from its next check.)"
    }

    fun topApps(n: Int): List<Pair<String, Long>> {
        val usm = c.getSystemService(UsageStatsManager::class.java) ?: return emptyList()
        val start = Calendar.getInstance().apply { set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0) }.timeInMillis
        val pm = c.packageManager
        return usm.queryAndAggregateUsageStats(start, System.currentTimeMillis()).values
            .filter { it.totalTimeInForeground > 60_000 }
            .sortedByDescending { it.totalTimeInForeground }
            .mapNotNull { u ->
                val label = runCatching { pm.getApplicationLabel(pm.getApplicationInfo(u.packageName, 0)).toString() }.getOrNull() ?: return@mapNotNull null
                if (u.packageName.contains("launcher", true)) null else label to u.totalTimeInForeground
            }.take(n)
    }

    private fun screenTime(): String {
        val apps = topApps(40)
        if (apps.isEmpty()) return "No screen time recorded today yet."
        val total = apps.sumOf { it.second }
        return "Screen time today: about ${minutes(total)}. Top apps: " + apps.take(4).joinToString(", ") { (n, m) -> "$n ${minutes(m)}" } + "."
    }

    private fun minutes(ms: Long) = PhoneMath.duration(ms / 3_600_000.0)

    private fun hourLabel(h: Int) = when { h == 0 -> "midnight"; h < 12 -> "$h AM"; h == 12 -> "noon"; else -> "${h - 12} PM" }

    /** Listens to a few sensors for a moment and reports what they say. */
    private fun surroundings(): String {
        val sm = c.getSystemService(SensorManager::class.java)
        val wanted = listOfNotNull(Sensor.TYPE_PRESSURE, Sensor.TYPE_LIGHT, Sensor.TYPE_ROTATION_VECTOR, Sensor.TYPE_AMBIENT_TEMPERATURE).mapNotNull { sm.getDefaultSensor(it) }
        if (wanted.isEmpty()) return "This phone doesn't have those sensors."
        val values = java.util.concurrent.ConcurrentHashMap<Int, FloatArray>()
        val listener = object : SensorEventListener {
            override fun onSensorChanged(e: SensorEvent) { values[e.sensor.type] = e.values.clone() }
            override fun onAccuracyChanged(s: Sensor?, a: Int) {}
        }
        wanted.forEach { sm.registerListener(listener, it, SensorManager.SENSOR_DELAY_UI, Handler(Looper.getMainLooper())) }
        Thread.sleep(1500)
        sm.unregisterListener(listener)
        val parts = ArrayList<String>()
        values[Sensor.TYPE_ROTATION_VECTOR]?.let { rv ->
            val r = FloatArray(9); SensorManager.getRotationMatrixFromVector(r, rv)
            val o = FloatArray(3); SensorManager.getOrientation(r, o)
            parts += "The top of the phone points ${PhoneMath.compass(Math.toDegrees(o[0].toDouble()).toFloat())} (hold it flat for a true compass reading)."
        }
        values[Sensor.TYPE_PRESSURE]?.let { p ->
            parts += "Air pressure ${p[0].roundToInt()} hPa, so altitude is roughly ${PhoneMath.altitude(p[0]).roundToInt()} m (weather shifts this a little)."
        }
        values[Sensor.TYPE_LIGHT]?.let { l -> parts += "Light: ${PhoneMath.lightWord(l[0])} (${l[0].roundToInt()} lux)." }
        values[Sensor.TYPE_AMBIENT_TEMPERATURE]?.let { t -> parts += "Air temperature ${t[0].roundToInt()}°C." }
        return parts.ifEmpty { listOf("The sensors didn't report anything just now.") }.joinToString(" ")
    }

    companion object {
        fun usageAccess(c: Context): Boolean {
            val ops = c.getSystemService(AppOpsManager::class.java)
            @Suppress("DEPRECATION")
            val mode = if (Build.VERSION.SDK_INT >= 29) ops.unsafeCheckOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), c.packageName)
            else ops.checkOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), c.packageName)
            return mode == AppOpsManager.MODE_ALLOWED
        }
    }
}

/** Works the phone's controls when the user asks the Brain to. Everything here is undoable from the phone itself. */
class PhoneControls(context: Context) : PhoneController {
    private val c = context.applicationContext

    override fun act(action: PhoneAction, value: String): String {
        if (!BrainAccess.on(c, BrainAccess.Source.CONTROLS)) return "Murmur Brain isn't allowed to work the phone's controls. Tell the user they can turn on \"Phone controls\" in Settings."
        return runCatching {
            when (action) {
                PhoneAction.FLASHLIGHT_ON, PhoneAction.FLASHLIGHT_OFF -> torch(action == PhoneAction.FLASHLIGHT_ON)
                PhoneAction.TIMER -> {
                    val secs = PhoneMath.parseTimer(value) ?: return "How long should the timer be?"
                    start(Intent(AlarmClock.ACTION_SET_TIMER).putExtra(AlarmClock.EXTRA_LENGTH, secs).putExtra(AlarmClock.EXTRA_SKIP_UI, true).putExtra(AlarmClock.EXTRA_MESSAGE, "Murmur"))
                    "Timer set for ${PhoneMath.duration(secs / 3600.0)} in the Clock app."
                }
                PhoneAction.ALARM -> {
                    val (h, m) = PhoneMath.parseClock(value) ?: return "What time should the alarm be?"
                    start(Intent(AlarmClock.ACTION_SET_ALARM).putExtra(AlarmClock.EXTRA_HOUR, h).putExtra(AlarmClock.EXTRA_MINUTES, m).putExtra(AlarmClock.EXTRA_SKIP_UI, true))
                    "Alarm set for ${"%d:%02d %s".format(if (h % 12 == 0) 12 else h % 12, m, if (h < 12) "AM" else "PM")} in the Clock app."
                }
                PhoneAction.SILENT, PhoneAction.VIBRATE, PhoneAction.SOUND_ON -> ringer(action)
                PhoneAction.VOLUME -> {
                    val pct = Regex("\\d+").find(value)?.value?.toInt()?.coerceIn(0, 100) ?: return "What volume, as a percent?"
                    val audio = c.getSystemService(AudioManager::class.java)
                    audio.setStreamVolume(AudioManager.STREAM_MUSIC, (audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC) * pct / 100f).roundToInt(), AudioManager.FLAG_SHOW_UI)
                    "Media volume set to $pct%."
                }
                PhoneAction.OPEN_APP -> openApp(value)
                PhoneAction.OPEN_SETTINGS -> openSettings(value)
            }
        }.getOrElse { "That didn't work (${it.message ?: it.javaClass.simpleName})." }
    }

    private fun start(i: Intent) = c.startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))

    private fun torch(on: Boolean): String {
        val cam = c.getSystemService(CameraManager::class.java)
        val id = cam.cameraIdList.firstOrNull { cam.getCameraCharacteristics(it).get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true }
            ?: return "This phone has no flashlight."
        cam.setTorchMode(id, on)
        return if (on) "Flashlight is on." else "Flashlight is off."
    }

    private fun ringer(a: PhoneAction): String {
        val audio = c.getSystemService(AudioManager::class.java)
        val nm = c.getSystemService(NotificationManager::class.java)
        if (a == PhoneAction.SILENT && !nm.isNotificationPolicyAccessGranted) {
            start(Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS))
            return "Android needs Murmur to have Do Not Disturb access to silence the phone. I opened that setting; turn Murmur on there and ask again."
        }
        audio.ringerMode = when (a) { PhoneAction.SILENT -> AudioManager.RINGER_MODE_SILENT; PhoneAction.VIBRATE -> AudioManager.RINGER_MODE_VIBRATE; else -> AudioManager.RINGER_MODE_NORMAL }
        return when (a) { PhoneAction.SILENT -> "Phone is on silent."; PhoneAction.VIBRATE -> "Phone is on vibrate."; else -> "Ringer is back on." }
    }

    private fun openApp(name: String): String {
        if (name.isBlank()) return "Which app?"
        val pm = c.packageManager
        val apps = pm.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0)
        val want = name.lowercase().removePrefix("the ").removeSuffix(" app").trim()
        val match = apps.map { it to it.loadLabel(pm).toString() }.let { list ->
            list.firstOrNull { it.second.equals(want, true) } ?: list.firstOrNull { it.second.lowercase().startsWith(want) } ?: list.firstOrNull { it.second.lowercase().contains(want) }
        } ?: return "I couldn't find an app called \"$name\" on this phone."
        start(pm.getLaunchIntentForPackage(match.first.activityInfo.packageName) ?: return "\"${match.second}\" can't be opened directly.")
        return "Opened ${match.second}."
    }

    private fun openSettings(page: String): String {
        val p = page.lowercase()
        val (action, label) = when {
            "wifi" in p || "wi-fi" in p -> Settings.ACTION_WIFI_SETTINGS to "Wi-Fi"
            "bluetooth" in p -> Settings.ACTION_BLUETOOTH_SETTINGS to "Bluetooth"
            "batt" in p || "power" in p -> Intent.ACTION_POWER_USAGE_SUMMARY to "Battery"
            "display" in p || "bright" in p || "screen" in p -> Settings.ACTION_DISPLAY_SETTINGS to "Display"
            "sound" in p || "volume" in p || "ring" in p -> Settings.ACTION_SOUND_SETTINGS to "Sound"
            "locat" in p || "gps" in p -> Settings.ACTION_LOCATION_SOURCE_SETTINGS to "Location"
            "storage" in p -> Settings.ACTION_INTERNAL_STORAGE_SETTINGS to "Storage"
            "data" in p || "mobile" in p -> Settings.ACTION_DATA_ROAMING_SETTINGS to "Mobile network"
            "airplane" in p -> Settings.ACTION_AIRPLANE_MODE_SETTINGS to "Airplane mode"
            "disturb" in p || "dnd" in p -> Settings.ACTION_ZEN_MODE_PRIORITY_SETTINGS to "Do Not Disturb"
            "notif" in p -> Settings.ACTION_APP_NOTIFICATION_SETTINGS to "Notifications"
            else -> Settings.ACTION_SETTINGS to "Settings"
        }
        val i = Intent(action)
        if (action == Settings.ACTION_APP_NOTIFICATION_SETTINGS) i.putExtra(Settings.EXTRA_APP_PACKAGE, c.packageName)
        runCatching { start(i) }.getOrElse { start(Intent(Settings.ACTION_SETTINGS)) }
        return "Opened $label settings."
    }
}
