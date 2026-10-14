package com.xennon3.dashplay

import android.annotation.SuppressLint
import android.os.Bundle
import android.webkit.JavascriptInterface
import android.webkit.WebView
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import org.json.JSONObject

/**
 * The phone half of Dashplay: a remote control for the car screen. The interface itself is
 * assets/remote.html, so it shares its look and its game list with the car pages.
 */
class MainActivity : ComponentActivity() {

    private lateinit var web: WebView
    private val onStatus: (CarBridge.Status) -> Unit = { push(it) }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        web = WebView(this).apply {
            setBackgroundColor(getColor(R.color.bg))
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.mediaPlaybackRequiresUserGesture = false
            addJavascriptInterface(Remote(), "DashplayRemote")
            loadUrl("file:///android_asset/remote.html")
        }
        // Keep the page clear of the status bar, navigation bar and keyboard.
        ViewCompat.setOnApplyWindowInsetsListener(web) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.ime())
            v.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            WindowInsetsCompat.CONSUMED
        }
        setContentView(web)

        // A game opened on the phone goes back to the remote instead of closing the app.
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (web.canGoBack()) web.goBack() else finish()
            }
        })
    }

    override fun onStart() {
        super.onStart()
        CarBridge.observe(onStatus)
    }

    override fun onStop() {
        CarBridge.stopObserving(onStatus)
        super.onStop()
    }

    override fun onDestroy() {
        web.destroy()
        super.onDestroy()
    }

    private fun push(s: CarBridge.Status) {
        web.evaluateJavascript("window.onCarStatus && onCarStatus(${statusJson(s)})", null)
    }

    private fun statusJson(s: CarBridge.Status) =
        JSONObject().put("connected", s.connected).put("moving", s.moving).put("url", s.url).toString()

    /** Called from remote.html on a WebView thread; CarBridge hops to the main thread. */
    private inner class Remote {
        @JavascriptInterface
        fun load(url: String) = CarBridge.load(url)

        @JavascriptInterface
        fun press(key: String) {
            GameKey.entries.firstOrNull { it.name.equals(key, ignoreCase = true) }?.let(CarBridge::press)
        }

        @JavascriptInterface
        fun type(text: String) = CarBridge.type(text)

        @JavascriptInterface
        fun status(): String = statusJson(CarBridge.status())
    }
}
