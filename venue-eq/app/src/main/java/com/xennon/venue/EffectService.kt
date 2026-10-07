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
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.util.Log

/**
 * Keeps effects alive while music plays in other apps.
 *
 * Sessions come from players that announce them (ACTION_OPEN_AUDIO_EFFECT_CONTROL_SESSION)
 * or from [SessionScanner]. Each session is handled one of two ways:
 *  - Full engine (when capture is on): the app's own output is muted with a [SessionMute]
 *    and its captured audio plays through [CaptureEngine], so every room works.
 *  - Built-in effects: an [EffectChain] of the phone's platform effects on the session.
 * Apps that block capture are detected (capture goes silent while they play) and fall
 * back to built-in effects.
 */
class EffectService : Service() {

    private lateinit var settings: SoundSettings
    private val chains = LinkedHashMap<Int, EffectChain>()
    private val mutes = HashMap<Int, SessionMute>()
    private val mutedSince = HashMap<Int, Long>()
    private val states = HashMap<Int, String>()
    /** Packages seen blocking capture during this run; they get built-in effects instead. */
    private val blocked = HashSet<String>()
    private var projection: MediaProjection? = null
    private var capture: CaptureEngine? = null
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
        when (intent?.action) {
            ACTION_STOP -> {
                Log.i(TAG, "turned off from the notification")
                settings.enabled = false
                stopSelf()
            }
            ACTION_CAPTURE -> {
                val code = intent.getIntExtra(EXTRA_CODE, 0)
                val data = if (Build.VERSION.SDK_INT >= 33) {
                    intent.getParcelableExtra(EXTRA_DATA, Intent::class.java)
                } else {
                    @Suppress("DEPRECATION") intent.getParcelableExtra(EXTRA_DATA)
                }
                if (data != null) startCapture(code, data)
            }
            ACTION_STOP_CAPTURE -> stopCapture()
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
        stopCapture(reroute = false)
        chains.values.forEach { it.release() }
        chains.clear()
        mutes.values.forEach { it.release() }
        mutes.clear()
        attached.clear()
        reports.clear()
        notifyListeners()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun attach(session: Int, owner: String) {
        if (attached.containsKey(session)) return
        // Global mode would also process our own capture output, so it pauses meanwhile.
        if (session == 0 && capture != null) return
        attached[session] = owner
        route(session)
        refresh()
    }

    /** (Re)builds whatever handles [session]: engine mute or built-in effects. */
    private fun route(session: Int) {
        val owner = attached[session] ?: return
        chains.remove(session)?.release()
        mutes.remove(session)?.release()
        mutedSince.remove(session)

        if (Build.VERSION.SDK_INT >= 29 && capture != null && session != 0 && owner !in blocked) {
            try {
                mutes[session] = SessionMute(session)
                mutedSince[session] = SystemClock.elapsedRealtime()
                Log.i(TAG, "$owner (session $session) -> Venue engine")
                return
            } catch (t: Throwable) {
                // Can't silence the original, so engine routing would play it twice.
                Log.w(TAG, "couldn't mute $owner (session $session), using built-in effects", t)
            }
        }
        try {
            val chain = EffectChain(session) { refresh() }
            chains[session] = chain
            chain.apply(settings.snapshot())
            Log.i(TAG, "$owner (session $session) -> built-in effects: ${chain.report()}")
        } catch (t: Throwable) {
            Log.e(TAG, "couldn't attach to $owner (session $session)", t)
            CrashLog.record(this, "attach to $owner", t)
        }
    }

    private fun onScan(live: Map<Int, SessionScanner.Found>) {
        for ((session, found) in live) {
            states[session] = found.state
            if (session !in attached) {
                scanned += session
                attach(session, found.pkg)
            }
        }
        for (session in scanned.toList()) {
            if (session !in live) {
                scanned -= session
                detach(session)
            }
        }
        checkForBlockedCapture()
        notifyListeners()
    }

    /**
     * Some apps (Chrome, Spotify...) forbid capture. If capture stays silent while a muted
     * app is playing, that app is blocking us: give it its sound back with built-in effects.
     */
    private fun checkForBlockedCapture() {
        val engine = capture ?: return
        val now = SystemClock.elapsedRealtime()
        if (now - engine.lastSoundAt < SILENCE_MS) return
        var changed = false
        for ((session, since) in mutedSince.toList()) {
            if (states[session] == "started" && now - since > SILENCE_MS) {
                val owner = attached[session] ?: continue
                Log.i(TAG, "$owner seems to block capture; switching it to built-in effects")
                blocked += owner
                route(session)
                changed = true
            }
        }
        if (changed) refresh()
    }

    private fun detach(session: Int) {
        Log.i(TAG, "detached session $session")
        chains.remove(session)?.release()
        mutes.remove(session)?.release()
        mutedSince.remove(session)
        states.remove(session)
        attached.remove(session)
        refresh()
    }

    private fun startCapture(code: Int, data: Intent) {
        if (capture != null || Build.VERSION.SDK_INT < 29) return
        try {
            // Android requires the mediaProjection service type before using the token.
            goForeground(withProjection = true)
            val mpm = getSystemService(MediaProjectionManager::class.java)
            val proj = mpm.getMediaProjection(code, data) ?: error("no media projection")
            proj.registerCallback(object : MediaProjection.Callback() {
                override fun onStop() {
                    Log.i(TAG, "capture permission ended by the system")
                    if (projection === proj) stopCapture()
                }
            }, Handler(Looper.getMainLooper()))
            projection = proj
            capture = CaptureEngine(proj).also { it.start(settings.snapshot()) }
            capturing = true
            blocked.clear()
            if (attached.containsKey(0)) detach(0)
            attached.keys.toList().forEach(::route)
            Log.i(TAG, "full engine on")
        } catch (t: Throwable) {
            Log.e(TAG, "couldn't start capture", t)
            CrashLog.record(this, "start full engine", t)
            stopCapture()
        }
        refresh()
    }

    private fun stopCapture(reroute: Boolean = true) {
        val engine = capture
        val proj = projection
        capture = null
        projection = null
        capturing = false
        engine?.release()
        runCatching { proj?.stop() }
        if (reroute) {
            attached.keys.toList().forEach(::route)
            if (settings.globalMode) attach(0, GLOBAL)
            refresh()
        }
    }

    private fun applyAll() {
        val snap = settings.snapshot()
        capture?.update(snap)
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
        mutes.keys.forEach { id -> reports[id] = "Venue engine ✓ (full rooms)" }
        try {
            getSystemService(NotificationManager::class.java).notify(NOTIF_ID, buildNotification())
        } catch (t: Throwable) {
            Log.w(TAG, "notification update failed", t)
        }
        notifyListeners()
    }

    private fun goForeground(withProjection: Boolean = false) {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL, "Active effects", NotificationManager.IMPORTANCE_LOW)
        )
        val n = buildNotification()
        if (withProjection && Build.VERSION.SDK_INT >= 34) {
            startForeground(
                NOTIF_ID, n,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE or ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION,
            )
        } else if (withProjection && Build.VERSION.SDK_INT >= 29) {
            startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
        } else if (Build.VERSION.SDK_INT >= 34) {
            startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NOTIF_ID, n)
        }
    }

    private fun buildNotification(): Notification {
        val room = settings.room
        val apps = attached.count { it.key != 0 }
        val text = when {
            apps > 0 && capture != null -> "Shaping $apps app${if (apps == 1) "" else "s"} · full engine"
            apps > 0 -> "Shaping $apps app${if (apps == 1) "" else "s"}"
            capture != null -> "Full engine ready · waiting for music"
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
        private const val ACTION_CAPTURE = "com.xennon.venue.CAPTURE"
        private const val ACTION_STOP_CAPTURE = "com.xennon.venue.STOP_CAPTURE"
        private const val EXTRA_CODE = "code"
        private const val EXTRA_DATA = "data"
        private const val SILENCE_MS = 3000L

        /** True while other apps are routed through Venue's own engine. */
        @Volatile var capturing = false
            private set

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

        /** Starts the full engine with the screen-capture consent result. */
        fun startCapture(context: Context, code: Int, data: Intent) {
            context.startForegroundService(
                Intent(context, EffectService::class.java)
                    .setAction(ACTION_CAPTURE)
                    .putExtra(EXTRA_CODE, code)
                    .putExtra(EXTRA_DATA, data)
            )
        }

        fun stopCapture(context: Context) {
            context.startService(Intent(context, EffectService::class.java).setAction(ACTION_STOP_CAPTURE))
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, EffectService::class.java))
        }
    }
}
