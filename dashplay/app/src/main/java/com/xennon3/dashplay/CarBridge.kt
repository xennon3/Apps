package com.xennon3.dashplay

import android.content.Context
import android.content.SharedPreferences
import android.os.Handler
import android.os.Looper

/**
 * The phone screen and the car screen run in the same process, so they talk through this
 * object instead of over the network. Everything here is called on the main thread.
 */
object CarBridge {

    const val HOME_URL = "file:///android_asset/index.html"
    const val START_URL = "file:///android_asset/browser.html"

    interface CarScreen {
        fun load(url: String)
        fun press(key: GameKey)
        fun type(text: String)
        /** "back", "forward" or "reload" in the car's browser. */
        fun nav(action: String)
        fun onDrivingLockChanged()
    }

    /** What the phone screen shows about the car. */
    data class Status(
        val connected: Boolean,
        val moving: Boolean,
        val url: String,
        val title: String,
        val drivingLock: Boolean,
        val lockResetsEachDrive: Boolean,
    )

    private val main = Handler(Looper.getMainLooper())
    private var prefs: SharedPreferences? = null

    /** Loads the saved lock settings. Called by both the phone screen and the car session. */
    fun init(context: Context) {
        if (prefs != null) return
        val p = context.applicationContext.getSharedPreferences("settings", Context.MODE_PRIVATE)
        prefs = p
        lockResetsEachDrive = p.getBoolean("lockResetsEachDrive", true)
        drivingLock = p.getBoolean("drivingLock", true)
    }
    private val statusListeners = mutableSetOf<(Status) -> Unit>()

    var car: CarScreen? = null
        set(value) {
            field = value
            publish()
        }

    /** Whether the car reports it is moving, whatever the lock setting. */
    var moving = false
        set(value) {
            field = value
            publish()
        }

    /** Cover the car screen while moving. */
    var drivingLock = true
        set(value) {
            if (field == value) return
            field = value
            prefs?.edit()?.putBoolean("drivingLock", value)?.apply()
            car?.onDrivingLockChanged()
            publish()
        }

    /**
     * When true (the default), every new car session turns the lock back on, so switching it
     * off for a passenger never carries over to the next drive. When false, the lock keeps
     * whatever setting it had.
     */
    var lockResetsEachDrive = true
        set(value) {
            if (field == value) return
            field = value
            prefs?.edit()?.putBoolean("lockResetsEachDrive", value)?.apply()
            publish()
        }

    /** A car session started. */
    fun onDriveStarted() {
        if (lockResetsEachDrive) drivingLock = true
    }

    var currentUrl = HOME_URL
        private set
    var currentTitle = ""
        private set

    fun setPage(url: String, title: String) {
        currentUrl = url
        currentTitle = title
        publish()
    }

    /** Remembered so the car opens on the last page the phone sent, even before it connects. */
    fun load(url: String) = onMain {
        setPage(url, "")
        car?.load(url)
    }

    fun press(key: GameKey) = onMain { car?.press(key) }

    fun type(text: String) = onMain { car?.type(text) }

    fun nav(action: String) = onMain { car?.nav(action) }

    fun switchDrivingLock(on: Boolean) = onMain { drivingLock = on }

    fun switchLockResetsEachDrive(on: Boolean) = onMain { lockResetsEachDrive = on }

    fun observe(listener: (Status) -> Unit) {
        statusListeners += listener
        listener(status())
    }

    fun stopObserving(listener: (Status) -> Unit) {
        statusListeners -= listener
    }

    fun status() = Status(car != null, moving, currentUrl, currentTitle, drivingLock, lockResetsEachDrive)

    private fun publish() {
        val s = status()
        statusListeners.toList().forEach { it(s) }
    }

    private fun onMain(block: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) block() else main.post(block)
    }

    /** Turns whatever the user typed into something a WebView can open. */
    fun normalizeUrl(input: String): String {
        val text = input.trim()
        return when {
            text.isEmpty() -> START_URL
            text.contains("://") -> text
            text.contains(' ') || !text.contains('.') ->
                "https://duckduckgo.com/?q=" + android.net.Uri.encode(text)
            else -> "https://$text"
        }
    }
}

/** Buttons on the phone gamepad. They reach web games as ordinary keyboard events. */
enum class GameKey(val key: String, val code: String, val keyCode: Int) {
    UP("ArrowUp", "ArrowUp", 38),
    DOWN("ArrowDown", "ArrowDown", 40),
    LEFT("ArrowLeft", "ArrowLeft", 37),
    RIGHT("ArrowRight", "ArrowRight", 39),
    A(" ", "Space", 32),
    B("Enter", "Enter", 13),
}
