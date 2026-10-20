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
    private val places = Places()

    // Last known state, so only changes become events.
    private var level = -1
    private var charging: Boolean? = null
    private var hot = false
    private var screenOn = true
    private var screenOffSince = 0L
    private var wifi: Boolean? = null
    private var headphones: Boolean? = null
    private var motion = "still"
    private var lastSteps: Long? = null
    private var lastSpeed = 0f
    private var lastSpeedAt = 0L

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

    private val location = LocationListener { loc ->
        if (loc.hasSpeed()) { lastSpeed = loc.speed; lastSpeedAt = System.currentTimeMillis() }
        if (loc.hasAccuracy() && loc.accuracy > 250f) return@LocationListener // too fuzzy to place
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
        loop = MurmurApp.scope.launch {
            var minute = 0
            while (isActive) {
                runCatching { Ambient.tick(this@AmbientService) }
                if (minute % 5 == 0) runCatching { movement() }
                // A regular check-in at the chosen pace, so plans and goals get looked at.
                if (minute > 0 && minute % Ambient.pace(this@AmbientService).checkMinutes == 0) Ambient.event(this@AmbientService, "Regular check-in")
                minute++
                delay(60_000L)
            }
        }
    }

    /** Still, walking or in a vehicle, from steps and speed; a change is an event. */
    private fun movement() {
        val steps = if (BrainAccess.on(this, BrainAccess.Source.MOTION)) BatteryLog.stepCounter(this) else null
        val walked = if (steps != null && lastSteps != null) steps - lastSteps!! else 0L
        lastSteps = steps
        val fresh = System.currentTimeMillis() - lastSpeedAt < 6 * 60_000L
        val now = when {
            fresh && lastSpeed > 7f -> "in a vehicle"
            walked > 250 || (fresh && lastSpeed in 1f..3f) -> "walking"
            else -> "still"
        }
        if (now != motion) {
            Ambient.event(this, when (now) {
                "in a vehicle" -> "Started driving or riding (about ${PhoneMath.describeSpeed(lastSpeed, false)})"
                "walking" -> "Started walking"
                else -> if (motion == "in a vehicle") "Stopped driving" else "Stopped walking"
            })
            motion = now
        }
        senses()
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
        places.current?.let { list += "At: ${places.label(it)} (since ${Ambient.stamp(places.arrivedAt)})" } ?: run { if (hasLocation()) list += "At: not at a known place" }
        list += "Moving: $motion"
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

    private fun savePlaces() = getSharedPreferences("ambient", MODE_PRIVATE).edit().putString("places", JSONArray(places.places.map {
        JSONObject().put("id", it.id).put("lat", it.lat).put("lon", it.lon).put("v", it.visits).put("n", it.nightMinutes).put("w", it.workMinutes).put("name", it.name ?: "")
    }).toString()).apply()

    companion object {
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
