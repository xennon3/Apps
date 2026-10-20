package app.murmur.brain

import android.Manifest
import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.location.LocationListener
import android.location.LocationManager
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.BatteryManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import app.murmur.MainActivity
import app.murmur.MurmurApp
import app.murmur.R
import app.murmur.brain.tools.PhoneMath
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.util.Calendar
import kotlin.math.roundToInt

/**
 * Ambient mode's body: a foreground service (Android shows its notification the whole time) with
 * cheap reflexes in plain code. Battery, screen, headphones, Wi-Fi, location and steps are watched
 * continuously; only real changes reach [Ambient.event], which wakes the model.
 */
class AmbientService : Service() {
    private val main = Handler(Looper.getMainLooper())
    private var loop: Job? = null
    private val places get() = AmbientService.places

    // Last known state, so only changes become events.
    private var level = -1
    private var charging: Boolean? = null
    private var hot = false
    private var screenOn = true
    private var screenOffSince = 0L
    private var wifi: Boolean? = null
    private var headphones: Boolean? = null
    private var motion = "still"
    private var motionSince = System.currentTimeMillis()
    private var lastSpeed = 0f
    private var lastSpeedAt = 0L
    private var lastLat: Double? = null
    private var lastLon: Double? = null
    private var sampler: Job? = null
    private var gpsOn = false
    /** Accelerometer readings over the current few seconds (how much the phone is being moved). */
    private val jiggle = java.util.concurrent.ConcurrentLinkedQueue<Float>()

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        channel(this)
        // Location while it's allowed; after a reboot Android may refuse that from the background, so fall back.
        val first = if (hasLocation()) ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION else ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
        val started = listOf(first, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE).distinct().any { type ->
            runCatching {
                ServiceCompat.startForeground(this, NOTE_ID, note(this, "Watching quietly. It thinks when something changes."), if (Build.VERSION.SDK_INT >= 29) type else 0)
            }.isSuccess
        }
        if (!started) { stopSelf(); return }
        Ambient.load(this)
        Ambient.setRunning(true)
        loadPlaces()
        reflexes()
        Ambient.event(this, "Ambient mode switched on.", urgent = true)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == STOP) { Ambient.stop(this); return START_NOT_STICKY }
        return START_STICKY
    }

    override fun onDestroy() {
        loop?.cancel()
        sampler?.cancel()
        runCatching { getSystemService(android.hardware.SensorManager::class.java).unregisterListener(accel) }
        Ambient.saveSamples(this)
        runCatching { unregisterReceiver(battery) }
        runCatching { unregisterReceiver(screen) }
        runCatching { getSystemService(ConnectivityManager::class.java).unregisterNetworkCallback(network) }
        runCatching { getSystemService(AudioManager::class.java).unregisterAudioDeviceCallback(audio) }
        runCatching { getSystemService(LocationManager::class.java).removeUpdates(location) }
        savePlaces()
        Ambient.setRunning(false)
        super.onDestroy()
    }

    private fun hasLocation() = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED

    // ------------------------------------------------------------ reflexes

    private val battery = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) {
            val l = (i.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) * 100f / i.getIntExtra(BatteryManager.EXTRA_SCALE, 100)).roundToInt()
            val s = i.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
            val ch = s == BatteryManager.BATTERY_STATUS_CHARGING || s == BatteryManager.BATTERY_STATUS_FULL
            val temp = i.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0) / 10f
            charging?.let { was -> if (was != ch) Ambient.event(c, if (ch) "Plugged in to charge at $l%" else "Unplugged at $l%") }
            if (!ch && level > 0) for (t in listOf(30, 20, 10)) if (level > t && l <= t) Ambient.event(c, "Battery dropped to $l%", urgent = t <= 10)
            if (temp >= 43 && !hot) Ambient.event(c, "Phone temperature rose to ${temp.roundToInt()}°C", urgent = true)
            hot = temp >= 41
            level = l; charging = ch
            senses()
        }
    }

    private val screen = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) {
            when (i.action) {
                Intent.ACTION_SCREEN_OFF -> { screenOn = false; screenOffSince = System.currentTimeMillis() }
                Intent.ACTION_USER_PRESENT -> {
                    // Only a long break matters: picking the phone up after hours away (often: waking up).
                    val away = System.currentTimeMillis() - screenOffSince
                    if (screenOffSince > 0 && away > 3 * 3_600_000L) Ambient.event(c, "Phone picked up after ${PhoneMath.duration(away / 3_600_000.0)} untouched")
                    screenOn = true
                }
                Intent.ACTION_SCREEN_ON -> screenOn = true
            }
            senses()
        }
    }

    private val network = object : ConnectivityManager.NetworkCallback() {
        override fun onCapabilitiesChanged(n: Network, caps: NetworkCapabilities) = setWifi(caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI))
        override fun onLost(n: Network) = setWifi(false)
    }

    private fun setWifi(on: Boolean) {
        if (wifi != null && wifi != on) Ambient.event(this, if (on) "Joined Wi-Fi" else "Left Wi-Fi")
        wifi = on
        senses()
    }

    private val audio = object : AudioDeviceCallback() {
        override fun onAudioDevicesAdded(added: Array<out AudioDeviceInfo>) = checkHeadphones()
        override fun onAudioDevicesRemoved(removed: Array<out AudioDeviceInfo>) = checkHeadphones()
    }

    private fun checkHeadphones() {
        val now = getSystemService(AudioManager::class.java).getDevices(AudioManager.GET_DEVICES_OUTPUTS).any {
            it.type in setOf(AudioDeviceInfo.TYPE_WIRED_HEADPHONES, AudioDeviceInfo.TYPE_WIRED_HEADSET, AudioDeviceInfo.TYPE_BLUETOOTH_A2DP, AudioDeviceInfo.TYPE_USB_HEADSET)
        }
        if (headphones != null && headphones != now) Ambient.event(this, if (now) "Headphones connected" else "Headphones disconnected")
        headphones = now
        senses()
    }

    private val accel = object : android.hardware.SensorEventListener {
        override fun onSensorChanged(e: android.hardware.SensorEvent) {
            jiggle += kotlin.math.sqrt(e.values[0] * e.values[0] + e.values[1] * e.values[1] + e.values[2] * e.values[2])
            while (jiggle.size > 200) jiggle.poll()
        }
        override fun onAccuracyChanged(s: android.hardware.Sensor?, a: Int) {}
    }

    private val location = LocationListener { loc ->
        if (loc.hasSpeed()) { lastSpeed = loc.speed; lastSpeedAt = System.currentTimeMillis() }
        if (loc.hasAccuracy() && loc.accuracy > 250f) return@LocationListener // too fuzzy to place
        lastLat = loc.latitude; lastLon = loc.longitude
        lastFix = loc.latitude to loc.longitude
        when (val ch = places.fix(loc.latitude, loc.longitude, loc.time.takeIf { it > 0 } ?: System.currentTimeMillis())) {
            is Places.Change.Arrived -> Ambient.event(this, "Arrived at ${ch.label}")
            is Places.Change.Left -> Ambient.event(this, "Left ${ch.label} after ${PhoneMath.duration(ch.stayedMinutes / 60.0)}")
            null -> {}
        }
        savePlaces()
        senses()
    }

    @SuppressLint("MissingPermission")
    private fun reflexes() {
        ContextCompat.registerReceiver(this, battery, IntentFilter(Intent.ACTION_BATTERY_CHANGED), ContextCompat.RECEIVER_NOT_EXPORTED)
        ContextCompat.registerReceiver(this, screen, IntentFilter().apply { addAction(Intent.ACTION_SCREEN_OFF); addAction(Intent.ACTION_SCREEN_ON); addAction(Intent.ACTION_USER_PRESENT) }, ContextCompat.RECEIVER_NOT_EXPORTED)
        runCatching { getSystemService(ConnectivityManager::class.java).registerNetworkCallback(NetworkRequest.Builder().build(), network) }
        runCatching { getSystemService(AudioManager::class.java).registerAudioDeviceCallback(audio, main) }
        checkHeadphones()
        if (hasLocation()) runCatching {
            val lm = getSystemService(LocationManager::class.java)
            // The network provider is cheap; GPS only rides along when another app already has it on.
            if (lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER)) lm.requestLocationUpdates(LocationManager.NETWORK_PROVIDER, 3 * 60_000L, 50f, location, Looper.getMainLooper())
            lm.requestLocationUpdates(LocationManager.PASSIVE_PROVIDER, 60_000L, 30f, location, Looper.getMainLooper())
        }
        // The accelerometer batches readings in hardware, so watching it costs little.
        runCatching {
            val sm = getSystemService(android.hardware.SensorManager::class.java)
            sm.getDefaultSensor(android.hardware.Sensor.TYPE_ACCELEROMETER)?.let { sm.registerListener(accel, it, android.hardware.SensorManager.SENSOR_DELAY_NORMAL, 5_000_000) }
        }
        Ambient.loadSamples(this)
        // The sensor diary: a timestamped reading every 5 seconds.
        sampler = MurmurApp.scope.launch {
            while (isActive) {
                runCatching { sample() }
                delay(5_000L)
            }
        }
        loop = MurmurApp.scope.launch {
            var minute = 0
            while (isActive) {
                runCatching { Ambient.tick(this@AmbientService) }
                // A regular check-in at the chosen pace; it only wakes the model if something changed.
                if (minute > 0 && minute % Ambient.pace(this@AmbientService).checkMinutes == 0) Ambient.checkIn(this@AmbientService)
                if (minute % 5 == 0) runCatching { Ambient.saveSamples(this@AmbientService) }
                minute++
                delay(60_000L)
            }
        }
    }

    /** One diary row: motion from the accelerometer (and GPS speed while moving), place, screen, battery. */
    private fun sample() {
        val now = System.currentTimeMillis()
        val readings = jiggle.toList(); jiggle.clear()
        val moved = if (readings.size >= 5) {
            val mean = readings.average()
            kotlin.math.sqrt(readings.sumOf { (it - mean) * (it - mean) } / readings.size) > 0.35
        } else false
        val fresh = now - lastSpeedAt < 20_000L
        val instant = when {
            fresh && lastSpeed > 6f -> "vehicle"
            fresh && lastSpeed > 0.8f -> "walking"
            moved && motion == "vehicle" -> "vehicle" // bumps on the road while GPS catches up
            moved -> "walking"
            else -> "still"
        }
        Ambient.addSample(SenseLog.Sample(now, instant, if (fresh) lastSpeed else 0f, lastLat, lastLon, screenOn, charging == true, level, placeLabel()))
        gps(instant != "still" || (motion != "still" && now - motionSince < 3 * 60_000L))
        movementFromDiary(now)
    }

    /** Motion changes only count once they've held for a minute; the event says when and for how long. */
    private fun movementFromDiary(now: Long) {
        val recent = Ambient.samplesSince(now - 60_000L)
        if (recent.size < 8) return
        val state = recent.groupingBy { it.motion }.eachCount().maxBy { it.value }.key
        if (state == motion) { senses(); return }
        val miles = SenseLog.segments(Ambient.samplesSince(motionSince)).filter { it.motion != "still" }.sumOf { it.miles }
        val lasted = PhoneMath.duration((now - motionSince) / 3_600_000.0)
        val at = Ambient.stamp(now - 60_000L)
        val event = when {
            state == "vehicle" -> "Started driving or riding at $at"
            state == "walking" && motion == "still" -> "Started walking at $at"
            motion == "vehicle" -> "Stopped driving at $at after $lasted" + (if (miles >= 0.1) ", about ${"%.1f".format(java.util.Locale.US, miles)} miles" else "")
            motion == "walking" -> "Stopped walking at $at after $lasted"
            else -> null
        }
        motion = state; motionSince = now - 60_000L
        event?.let { Ambient.event(this, it) }
        senses()
    }

    /** GPS only while moving: speed and distance need it, and it's the costly sensor. */
    @SuppressLint("MissingPermission")
    private fun gps(on: Boolean) {
        if (on == gpsOn || ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) return
        val lm = getSystemService(LocationManager::class.java)
        runCatching {
            if (on) {
                lm.requestLocationUpdates(LocationManager.GPS_PROVIDER, 5_000L, 0f, location, Looper.getMainLooper())
            } else {
                lm.removeUpdates(location)
                if (lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER)) lm.requestLocationUpdates(LocationManager.NETWORK_PROVIDER, 3 * 60_000L, 50f, location, Looper.getMainLooper())
                lm.requestLocationUpdates(LocationManager.PASSIVE_PROVIDER, 60_000L, 30f, location, Looper.getMainLooper())
            }
            gpsOn = on
        }
    }

    /** Plain words for where the phone is; "unfamiliar" means not a place it knows, never a guess. */
    private fun placeLabel(): String? = when {
        !hasLocation() || lastLat == null -> null
        places.current != null -> places.label(places.current!!)
        else -> "an unfamiliar place (not home or work)"
    }

    /** The "Now" list in the Mind window, and what the Brain is told it senses. */
    private fun senses() {
        val list = ArrayList<String>()
        if (level >= 0) {
            val drain = PhoneMath.drainPerHour(BatteryLog.samples(this), System.currentTimeMillis())
            list += "Battery $level%, " + (if (charging == true) "charging" else "not charging") +
                (if (charging != true && drain != null && drain >= 1) ", using about ${drain.roundToInt()}% an hour" else "") + (if (hot) ", hot" else "")
        }
        PhoneMath.usualChargeHour(BatteryLog.samples(this), System.currentTimeMillis()) { Calendar.getInstance().apply { timeInMillis = it }.get(Calendar.HOUR_OF_DAY) }
            ?.let { list += "Usually plugs in around ${if (it == 0) "midnight" else if (it < 12) "$it AM" else if (it == 12) "noon" else "${it - 12} PM"}" }
        val now = System.currentTimeMillis()
        places.current?.let { list += "At: ${places.label(it)} (since ${Ambient.stamp(places.arrivedAt)})" }
            ?: placeLabel()?.let { list += "At: $it" }
        list += "Moving: " + (if (motion == "vehicle") "driving or riding" else motion) +
            " since ${Ambient.stamp(motionSince)} (${PhoneMath.duration((now - motionSince) / 3_600_000.0)})"
        list += "Screen: " + if (screenOn) "on" else "off" + (if (screenOffSince > 0) " for ${PhoneMath.duration((System.currentTimeMillis() - screenOffSince) / 3_600_000.0)}" else "")
        headphones?.let { list += "Headphones: " + if (it) "connected" else "none" }
        wifi?.let { list += "Wi-Fi: " + if (it) "connected" else "not connected" }
        list += "Ringer: " + when (getSystemService(AudioManager::class.java).ringerMode) { AudioManager.RINGER_MODE_SILENT -> "silent"; AudioManager.RINGER_MODE_VIBRATE -> "vibrate"; else -> "normal" }
        Ambient.updateSenses(list)
    }

    // ------------------------------------------------------------ learned places, kept on the phone

    private fun loadPlaces() = runCatching {
        val a = JSONArray(getSharedPreferences("ambient", MODE_PRIVATE).getString("places", "[]"))
        places.places = MutableList(a.length()) { i ->
            a.getJSONObject(i).let { Places.Place(it.getInt("id"), it.getDouble("lat"), it.getDouble("lon"), it.getInt("v"), it.getInt("n"), it.getInt("w"), it.optString("name").ifBlank { null }) }
        }
    }

    private fun savePlaces() = savePlaces(this)

    companion object {
        /** Learned places, shared with the Mind window (to name the current one). */
        val places = Places()
        /** The latest location fix, for naming the place the phone is at. */
        @Volatile var lastFix: Pair<Double, Double>? = null

        fun savePlaces(c: Context) = c.getSharedPreferences("ambient", MODE_PRIVATE).edit().putString("places", JSONArray(places.places.map {
            JSONObject().put("id", it.id).put("lat", it.lat).put("lon", it.lon).put("v", it.visits).put("n", it.nightMinutes).put("w", it.workMinutes).put("name", it.name ?: "")
        }).toString()).apply()

        const val CHANNEL = "ambient"
        const val NOTE_ID = 7500
        const val STOP = "app.murmur.AMBIENT_STOP"

        private fun channel(c: Context) = c.getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL, "Ambient mode", NotificationManager.IMPORTANCE_LOW).apply { description = "Shown the whole time Murmur's Ambient mode is on" },
        )

        private fun note(c: Context, text: String): Notification {
            val open = PendingIntent.getActivity(c, 7501, Intent(c, MainActivity::class.java).setAction(MainActivity.OPEN_MIND), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
            val stop = PendingIntent.getService(c, 7502, Intent(c, AmbientService::class.java).setAction(STOP), PendingIntent.FLAG_IMMUTABLE)
            return NotificationCompat.Builder(c, CHANNEL)
                .setSmallIcon(R.drawable.ic_stat_murmur).setContentTitle("Murmur is aware").setContentText(text)
                .setStyle(NotificationCompat.BigTextStyle().bigText(text))
                .setOngoing(true).setContentIntent(open).addAction(0, "Turn off", stop).build()
        }

        @SuppressLint("MissingPermission")
        fun refreshNotification(c: Context, lastThought: String) {
            if (!Ambient.running.value) return
            runCatching { c.getSystemService(NotificationManager::class.java).notify(NOTE_ID, note(c, "Last thought (${Ambient.stamp(System.currentTimeMillis())}): $lastThought")) }
        }
    }
}
