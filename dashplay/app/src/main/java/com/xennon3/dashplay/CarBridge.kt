package com.xennon3.dashplay

import android.os.Handler
import android.os.Looper

/**
 * The phone screen and the car screen run in the same process, so they talk through this
 * object instead of over the network. Everything here is called on the main thread.
 */
object CarBridge {

    const val HOME_URL = "file:///android_asset/index.html"

    interface CarScreen {
        fun load(url: String)
        fun press(key: GameKey)
        fun type(text: String)
    }

    /** What the phone screen shows about the car. */
    data class Status(val connected: Boolean, val moving: Boolean, val url: String)

    private val main = Handler(Looper.getMainLooper())
    private val statusListeners = mutableSetOf<(Status) -> Unit>()

    var car: CarScreen? = null
        set(value) {
            field = value
            publish()
        }

    var moving = false
        set(value) {
            field = value
            publish()
        }

    var currentUrl = HOME_URL
        set(value) {
            field = value
            publish()
        }

    /** Remembered so the car opens on the last page the phone sent, even before it connects. */
    fun load(url: String) = onMain {
        currentUrl = url
        car?.load(url)
    }

    fun press(key: GameKey) = onMain { car?.press(key) }

    fun type(text: String) = onMain { car?.type(text) }

    fun observe(listener: (Status) -> Unit) {
        statusListeners += listener
        listener(status())
    }

    fun stopObserving(listener: (Status) -> Unit) {
        statusListeners -= listener
    }

    fun status() = Status(car != null, moving, currentUrl)

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
            text.isEmpty() -> HOME_URL
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
