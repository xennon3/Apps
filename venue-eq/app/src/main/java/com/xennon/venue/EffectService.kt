package com.xennon.venue

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
import android.content.SharedPreferences
import android.content.pm.ServiceInfo
import android.media.audiofx.AudioEffect
import android.os.Build
import android.os.IBinder
import android.util.Log

/**
 * Keeps effects alive while music plays in other apps.
 *
 * Well-behaved players (Spotify, YouTube Music, Poweramp, VLC, ...) broadcast
 * ACTION_OPEN_AUDIO_EFFECT_CONTROL_SESSION with their audio session id when playback
 * starts; we attach an [EffectChain] to each one. Optionally we also attach to the
 * legacy global session 0, which some phones still honour for EQ/bass/width.
 */
class EffectService : Service() {

    private lateinit var settings: SoundSettings
    private val chains = LinkedHashMap<Int, EffectChain>()
    private var scanner: SessionScanner? = null
    /** Sessions we found ourselves (vs. ones the player announced). */
    private val scanned = HashSet<Int>()

    private val sessionReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val session = intent.getIntExtra(AudioEffect.EXTRA_AUDIO_SESSION, -1)
            if (session <= 0) return
            val pkg = intent.getStringExtra(AudioEffect.EXTRA_PACKAGE_NAME) ?: "unknown app"
            when (intent.action) {
                AudioEffect.ACTION_OPEN_AUDIO_EFFECT_CONTROL_SESSION -> attach(session, pkg)
                AudioEffect.ACTION_CLOSE_AUDIO_EFFECT_CONTROL_SESSION -> detach(session)
            }
        }
    }

    private val prefListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        when (key) {
            "global" -> if (settings.globalMode) attach(0, GLOBAL) else detach(0)
            "enabled" -> Unit
            else -> applyAll()
        }
    }

    override fun onCreate() {
        super.onCreate()
        settings = SoundSettings(this)
        goForeground()

        val filter = IntentFilter().apply {
            addAction(AudioEffect.ACTION_OPEN_AUDIO_EFFECT_CONTROL_SESSION)
            addAction(AudioEffect.ACTION_CLOSE_AUDIO_EFFECT_CONTROL_SESSION)
        }
        registerSessionReceiver(filter)
        settings.prefs.registerOnSharedPreferenceChangeListener(prefListener)
        if (settings.globalMode) attach(0, GLOBAL)
        scanner = SessionScanner(this, ::onScan).also { it.start() }
        Log.i(TAG, "service started; room=${settings.room.name}, global=${settings.globalMode}")

        running = true
        notifyListeners()
    }

    // Exported: the broadcasts come from other apps (the music players).
    // Before Android 13 receivers were always exported and the flag didn't exist.
    @SuppressLint("UnspecifiedRegisterReceiverFlag")
    private fun registerSessionReceiver(filter: IntentFilter) {
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(sessionReceiver, filter, Context.RECEIVER_EXPORTED)
        } else {
            registerReceiver(sessionReceiver, filter)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            Log.i(TAG, "turned off from the notification")
            settings.enabled = false
            stopSelf()
        }
        return START_STICKY
    }

    override fun onDestroy() {
        Log.i(TAG, "service stopped")
        running = false
        settings.prefs.unregisterOnSharedPreferenceChangeListener(prefListener)
        unregisterReceiver(sessionReceiver)
        scanner?.stop()
        scanner = null
        scanned.clear()
        chains.values.forEach { it.release() }
        chains.clear()
        attached.clear()
        reports.clear()
        notifyListeners()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun attach(session: Int, owner: String) {
        if (chains.containsKey(session)) return
        try {
            val chain = EffectChain(session) { refresh() }
            chains[session] = chain
            attached[session] = owner
            chain.apply(settings.snapshot())
            Log.i(TAG, "attached to $owner (session $session): ${chain.report()}")
        } catch (t: Throwable) {
            Log.e(TAG, "couldn't attach to $owner (session $session)", t)
            CrashLog.record(this, "attach to $owner", t)
        }
        refresh()
    }

    private fun onScan(live: Map<Int, String>) {
        for ((session, pkg) in live) {
            if (session !in chains) {
                scanned += session
                attach(session, pkg)
            }
        }
        for (session in scanned.toList()) {
            if (session !in live) {
                scanned -= session
                detach(session)
            }
        }
        notifyListeners()
    }

    private fun detach(session: Int) {
        Log.i(TAG, "detached session $session")
        chains.remove(session)?.release()
        attached.remove(session)
        refresh()
    }

    private fun applyAll() {
        val snap = settings.snapshot()
        chains.values.forEach { c ->
            try {
                c.apply(snap)
            } catch (t: Throwable) {
                Log.e(TAG, "apply failed on session ${c.sessionId}", t)
            }
        }
        refresh()
    }

    private fun refresh() {
        reports.clear()
        chains.forEach { (id, c) -> reports[id] = c.report() }
        try {
            getSystemService(NotificationManager::class.java).notify(NOTIF_ID, buildNotification())
        } catch (t: Throwable) {
            Log.w(TAG, "notification update failed", t)
        }
        notifyListeners()
    }

    private fun goForeground() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL, "Active effects", NotificationManager.IMPORTANCE_LOW)
        )
        val n = buildNotification()
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NOTIF_ID, n)
        }
    }

    private fun buildNotification(): Notification {
        val room = settings.room
        val apps = attached.count { it.key != 0 }
        val text = when {
            apps > 0 -> "Shaping $apps app${if (apps == 1) "" else "s"}"
            else -> "Waiting for music to start"
        }
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val stop = PendingIntent.getService(
            this, 1, Intent(this, EffectService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE,
        )
        return Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_venue)
            .setContentTitle("${room.emoji} ${room.name}")
            .setContentText(text)
            .setContentIntent(open)
            .setOngoing(true)
            .addAction(Notification.Action.Builder(null, "Turn off", stop).build())
            .build()
    }

    companion object {
        const val GLOBAL = "Global output"
        private const val TAG = "VenueEffects"
        private const val CHANNEL = "effects"
        private const val NOTIF_ID = 1
        private const val ACTION_STOP = "com.xennon.venue.STOP"

        @Volatile var running = false
            private set

        /** session id -> package name of the app that owns it. Main thread only. */
        val attached = LinkedHashMap<Int, String>()

        /** session id -> [EffectChain.report] health line. Main thread only. */
        val reports = HashMap<Int, String>()

        private val listeners = mutableSetOf<() -> Unit>()
        fun addListener(l: () -> Unit) = listeners.add(l)
        fun removeListener(l: () -> Unit) = listeners.remove(l)
        private fun notifyListeners() = listeners.toList().forEach { it() }

        fun start(context: Context) {
            context.startForegroundService(Intent(context, EffectService::class.java))
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, EffectService::class.java))
        }
    }
}
