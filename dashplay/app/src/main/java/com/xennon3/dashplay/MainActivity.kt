package com.xennon3.dashplay

import android.app.Activity
import android.os.Bundle
import android.view.MotionEvent
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView

/** The phone half of Dashplay: a remote control for the car screen. */
class MainActivity : Activity() {

    private lateinit var status: TextView
    private val onStatus: (CarBridge.Status) -> Unit = { render(it) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        status = findViewById(R.id.status)

        val url = findViewById<EditText>(R.id.url)
        val send = { CarBridge.load(CarBridge.normalizeUrl(url.text.toString())) }
        findViewById<Button>(R.id.send).setOnClickListener { send() }
        url.setOnEditorActionListener { _, action, _ -> (action == EditorInfo.IME_ACTION_GO).also { if (it) send() } }

        val text = findViewById<EditText>(R.id.text)
        val type = {
            CarBridge.type(text.text.toString())
            text.text.clear()
        }
        findViewById<Button>(R.id.type).setOnClickListener { type() }
        text.setOnEditorActionListener { _, action, _ -> (action == EditorInfo.IME_ACTION_SEND).also { if (it) type() } }

        val games = findViewById<LinearLayout>(R.id.games)
        GAMES.forEach { game ->
            games.addView(Button(this).apply {
                this.text = "▶  ${game.title}"
                setOnClickListener { CarBridge.load(game.url) }
            })
        }

        pad(R.id.up, GameKey.UP)
        pad(R.id.down, GameKey.DOWN)
        pad(R.id.left, GameKey.LEFT)
        pad(R.id.right, GameKey.RIGHT)
        pad(R.id.a, GameKey.A)
        pad(R.id.b, GameKey.B)
    }

    override fun onStart() {
        super.onStart()
        CarBridge.observe(onStatus)
    }

    override fun onStop() {
        CarBridge.stopObserving(onStatus)
        super.onStop()
    }

    /** Fires on touch-down rather than click, so the pad feels instant. */
    private fun pad(id: Int, key: GameKey) {
        findViewById<View>(id).setOnTouchListener { v, event ->
            if (event.actionMasked == MotionEvent.ACTION_DOWN) {
                CarBridge.press(key)
                v.performHapticFeedback(android.view.HapticFeedbackConstants.VIRTUAL_KEY)
            }
            false
        }
    }

    private fun render(s: CarBridge.Status) {
        status.text = when {
            !s.connected -> "Not connected — open Dashplay on the car screen"
            s.moving -> "● Connected · paused while driving"
            else -> "● Connected · showing ${pageName(s.url)}"
        }
        status.setTextColor(getColor(if (s.connected && !s.moving) R.color.ok else R.color.muted))
    }

    private fun pageName(url: String): String {
        val uri = android.net.Uri.parse(url)
        return if (uri.scheme == "file") uri.lastPathSegment.orEmpty() else uri.host.orEmpty()
    }
}
