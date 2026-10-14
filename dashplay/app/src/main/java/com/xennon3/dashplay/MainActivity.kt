package com.xennon3.dashplay

import android.annotation.SuppressLint
import android.os.Bundle
import android.webkit.JavascriptInterface
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
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
    private val assets by lazy { Local.loader(this) }

    // Read by Store.Api on a WebView thread.
    @Volatile private var pageUrl = ""
    private val onStoreChanged: () -> Unit = { web.evaluateJavascript("window.onStoreChanged && onStoreChanged()", null) }
    private val onStatus: (CarBridge.Status) -> Unit = { push(it) }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        CarBridge.init(this)
        web = WebView(this).apply {
            setBackgroundColor(getColor(R.color.bg))
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.mediaPlaybackRequiresUserGesture = false
            addJavascriptInterface(Remote(), "DashplayRemote")
            addJavascriptInterface(Store.Api(this@MainActivity, { pageUrl }, { js -> evaluateJavascript(js, null) }), "DashplayStore")
            webViewClient = object : WebViewClient() {
                override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest) =
                    assets.shouldInterceptRequest(request.url)

                override fun onPageStarted(view: WebView, url: String, favicon: android.graphics.Bitmap?) {
                    pageUrl = url
                }

                // The page crashed; start the screen over instead of letting the app close.
                override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
                    recreate()
                    return true
                }
            }
            loadUrl("${Local.ASSETS}/remote.html")
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
        Store.observe(onStoreChanged)
    }

    override fun onStop() {
        CarBridge.stopObserving(onStatus)
        Store.stopObserving(onStoreChanged)
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
        JSONObject().put("connected", s.connected).put("moving", s.moving).put("url", s.url)
            .put("title", s.title).put("drivingLock", s.drivingLock)
            .put("lockResetsEachDrive", s.lockResetsEachDrive).toString()

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
        fun nav(action: String) = CarBridge.nav(action)

        @JavascriptInterface
        fun history(): String = History.json(this@MainActivity)

        @JavascriptInterface
        fun setDrivingLock(on: Boolean) = CarBridge.switchDrivingLock(on)

        @JavascriptInterface
        fun setLockResetsEachDrive(on: Boolean) = CarBridge.switchLockResetsEachDrive(on)

        @JavascriptInterface
        fun status(): String = statusJson(CarBridge.status())
    }
}
