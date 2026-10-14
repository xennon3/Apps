package com.xennon3.dashplay.car

import android.annotation.SuppressLint
import android.app.Presentation
import android.content.Context
import android.graphics.Color
import android.graphics.Rect
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.TypedValue
import android.view.Gravity
import android.view.InputDevice
import android.view.MotionEvent
import android.view.View
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.car.app.SurfaceContainer
import com.xennon3.dashplay.CarBridge
import com.xennon3.dashplay.GameKey
import com.xennon3.dashplay.History
import com.xennon3.dashplay.R
import org.json.JSONObject

/**
 * Draws a real WebView onto the car's screen.
 *
 * Android Auto hands navigation apps a bare Surface. We wrap that Surface in a private
 * VirtualDisplay, show a Presentation (a window bound to that display) on it, and put the
 * WebView inside. Taps from the car come back in surface coordinates, which are the same
 * as the Presentation's coordinates, so they can be replayed as touch events directly.
 *
 * Web pages get a browser toolbar above them. The toolbar is a second, small WebView
 * showing assets/chrome.html, so it shares its look with the rest of Dashplay.
 */
class WebSurface(private val context: Context, private val listener: Listener) {

    interface Listener {
        fun onPageChanged(url: String, title: String)
        fun onAddressRequested()
    }

    private val main = Handler(Looper.getMainLooper())
    private val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)

    private var display: VirtualDisplay? = null
    private var presentation: Presentation? = null
    private var root: FrameLayout? = null
    private var toolbar: WebView? = null
    private var lockCover: View? = null
    private var pendingUrl: String? = null
    private var width = 0
    private var height = 0

    // Read from JavaScript-interface threads.
    @Volatile private var pageUrl = ""
    private var pageProgress = 100
    private var pageLoading = false

    var webView: WebView? = null
        private set

    var locked = false
        set(value) {
            if (field == value) return
            field = value
            lockCover?.visibility = if (value) View.VISIBLE else View.GONE
            webView?.let {
                if (value) {
                    it.onPause()
                    it.pauseTimers()
                } else {
                    it.onResume()
                    it.resumeTimers()
                }
            }
        }

    fun attach(container: SurfaceContainer) {
        val surface = container.surface ?: return
        width = container.width
        height = container.height
        val existing = display
        if (existing != null) {
            // Same WebView, new surface: the page keeps its state across screen changes.
            existing.resize(container.width, container.height, container.dpi)
            existing.surface = surface
            return
        }
        val dm = context.getSystemService(DisplayManager::class.java)
        val vd = dm.createVirtualDisplay(
            "Dashplay", container.width, container.height, container.dpi, surface,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_OWN_CONTENT_ONLY,
        )
        display = vd
        // A dark theme lets web pages switch to their dark mode (see buildContent).
        presentation = Presentation(context, vd.display, R.style.Theme_Dashplay).also {
            it.setContentView(buildContent(it.context))
            it.show()
        }
        pendingUrl?.let { load(it) }
        pendingUrl = null
    }

    fun detach() {
        display?.surface = null
    }

    fun release() {
        webView?.destroy()
        toolbar?.destroy()
        presentation?.dismiss()
        display?.release()
        webView = null
        toolbar = null
        presentation = null
        display = null
        root = null
    }

    /** Keeps the page clear of the buttons Android Auto draws over the surface. */
    fun setVisibleArea(area: Rect) {
        val r = root ?: return
        if (area.isEmpty) {
            r.setPadding(0, 0, 0, 0)
        } else {
            r.setPadding(area.left, area.top, (width - area.right).coerceAtLeast(0), (height - area.bottom).coerceAtLeast(0))
        }
    }

    fun load(url: String) {
        val wv = webView
        if (wv == null) pendingUrl = url else wv.loadUrl(url)
    }

    fun nav(action: String) {
        val wv = webView ?: return
        when (action) {
            "back" -> if (wv.canGoBack()) wv.goBack()
            "forward" -> if (wv.canGoForward()) wv.goForward()
            "reload" -> wv.reload()
            "stop" -> wv.stopLoading()
        }
    }

    fun tap(x: Float, y: Float) {
        if (locked) return
        val target = presentation?.window?.decorView ?: return
        val down = SystemClock.uptimeMillis()
        listOf(MotionEvent.ACTION_DOWN to down, MotionEvent.ACTION_UP to down + 40).forEach { (action, time) ->
            val event = MotionEvent.obtain(down, time, action, x, y, 0)
            event.source = InputDevice.SOURCE_TOUCHSCREEN
            target.dispatchTouchEvent(event)
            event.recycle()
        }
    }

    /** Pan gestures scroll ordinary pages; games can also listen for the "dashplay" event. */
    fun scroll(dx: Float, dy: Float) {
        if (locked) return
        js("window.scrollBy($dx,$dy);" + event("""{type:"scroll",dx:$dx,dy:$dy}"""))
    }

    fun zoom(factor: Float) {
        if (locked) return
        js(event("""{type:"zoom",factor:$factor}"""))
        webView?.zoomBy(factor.coerceIn(0.5f, 2f))
    }

    /**
     * Sends a key as a DOM keyboard event. A window on a virtual display never gets input
     * focus, so real KeyEvents would be dropped; synthetic DOM events always arrive.
     */
    fun press(key: GameKey) {
        if (locked) return
        val init = JSONObject()
            .put("key", key.key).put("code", key.code)
            .put("keyCode", key.keyCode).put("which", key.keyCode)
            .put("bubbles", true).put("cancelable", true)
        js(
            """(function(){var t=document.activeElement||document.body||document;
            ['keydown','keyup'].forEach(function(n){t.dispatchEvent(new KeyboardEvent(n,$init));});})();"""
        )
    }

    /** Text typed on the phone goes into whatever field is focused on the car screen. */
    fun type(text: String) {
        if (locked) return
        val quoted = JSONObject.quote(text)
        js(
            """(function(){var e=document.activeElement;if(!e||!('value' in e))return;
            e.value+=$quoted;e.dispatchEvent(new Event('input',{bubbles:true}));})();"""
        )
    }

    private fun event(detail: String) = "window.dispatchEvent(new CustomEvent('dashplay',{detail:$detail}));"

    private fun js(code: String) {
        webView?.evaluateJavascript(code, null)
    }

    // Browser toolbar

    private val textZoom get() = prefs.getInt("textZoom", 100)

    /** Web pages and the error page get the toolbar; games and Dashplay's own pages don't. */
    private fun isBrowsing(url: String) = url.startsWith("http") || url.startsWith(OFFLINE_URL)

    private fun updateToolbar() {
        val bar = toolbar ?: return
        val wv = webView ?: return
        bar.visibility = if (isBrowsing(pageUrl)) View.VISIBLE else View.GONE
        if (bar.visibility != View.VISIBLE) return
        val offline = pageUrl.startsWith(OFFLINE_URL)
        // The error page stands in for the page that failed, so show that address.
        val shown = if (offline) Uri.parse(pageUrl).getQueryParameter("u").orEmpty() else pageUrl
        val state = JSONObject()
            .put("url", shown)
            .put("host", Uri.parse(shown).host.orEmpty())
            .put("title", if (offline) "Can't reach this page" else wv.title.orEmpty())
            .put("secure", shown.startsWith("https://"))
            .put("loading", pageLoading)
            .put("progress", pageProgress)
            .put("canBack", wv.canGoBack())
            .put("canForward", wv.canGoForward())
            .put("textZoom", textZoom)
        bar.evaluateJavascript("window.setState && setState($state)", null)
    }

    private fun cycleTextZoom() {
        val next = ZOOM_STEPS[(ZOOM_STEPS.indexOf(textZoom) + 1) % ZOOM_STEPS.size]
        prefs.edit().putInt("textZoom", next).apply()
        webView?.settings?.textZoom = next
        updateToolbar()
    }

    /** Buttons in chrome.html. Called on a WebView thread, so hop to the main thread. */
    private inner class ToolbarApi {
        @JavascriptInterface fun back() { main.post { nav("back") } }
        @JavascriptInterface fun forward() { main.post { nav("forward") } }
        @JavascriptInterface fun reload() { main.post { nav("reload") } }
        @JavascriptInterface fun stop() { main.post { nav("stop") } }
        @JavascriptInterface fun start() { main.post { load(CarBridge.START_URL) } }
        @JavascriptInterface fun textSize() { main.post { cycleTextZoom() } }
        @JavascriptInterface fun address() { main.post { listener.onAddressRequested() } }
    }

    /**
     * For Dashplay's own pages (the start page). Websites load into the same WebView, so
     * every call checks that the current page is one of ours before doing anything.
     */
    private inner class CarPageApi {
        private fun trusted() = pageUrl.startsWith(ASSET_ROOT)

        @JavascriptInterface
        fun openAddress() {
            if (trusted()) main.post { listener.onAddressRequested() }
        }

        @JavascriptInterface
        fun history(): String = if (trusted()) History.json(context) else "[]"

        @JavascriptInterface
        fun clearHistory() {
            if (trusted()) History.clear(context)
        }
    }

    @SuppressLint("SetJavaScriptEnabled", "JavascriptInterface")
    private fun buildContent(ctx: Context): View {
        val wv = WebView(ctx).apply {
            setBackgroundColor(Color.parseColor("#0B0D12"))
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.mediaPlaybackRequiresUserGesture = false
            settings.loadWithOverviewMode = true
            settings.useWideViewPort = true
            settings.builtInZoomControls = true
            settings.displayZoomControls = false
            settings.textZoom = textZoom
            // Sites with a dark mode use it; the rest are darkened to match the car UI.
            if (Build.VERSION.SDK_INT >= 33) settings.isAlgorithmicDarkeningAllowed = true
            addJavascriptInterface(CarPageApi(), "DashplayCar")
            webChromeClient = object : WebChromeClient() {
                override fun onProgressChanged(view: WebView, newProgress: Int) {
                    pageProgress = newProgress
                    updateToolbar()
                }

                override fun onReceivedTitle(view: WebView, title: String) {
                    updateToolbar()
                    listener.onPageChanged(view.url ?: pageUrl, title)
                }
            }
            webViewClient = object : WebViewClient() {
                override fun onPageStarted(view: WebView, url: String, favicon: android.graphics.Bitmap?) {
                    pageUrl = url
                    pageLoading = true
                    pageProgress = 10
                    updateToolbar()
                }

                override fun onPageFinished(view: WebView, url: String) {
                    pageLoading = false
                    updateToolbar()
                    val title = view.title.orEmpty()
                    History.add(context, url, title)
                    listener.onPageChanged(url, title)
                }

                override fun doUpdateVisitedHistory(view: WebView, url: String, isReload: Boolean) {
                    pageUrl = url
                    updateToolbar()
                }

                override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
                    if (!request.isForMainFrame) return
                    val failed = request.url.toString()
                    view.loadUrl(
                        Uri.parse(OFFLINE_URL).buildUpon()
                            .appendQueryParameter("u", failed)
                            .appendQueryParameter("e", error.description.toString())
                            .build().toString()
                    )
                }

                // Links to apps (tel:, intent:, market: ...) have nowhere to go on a car screen.
                override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest) =
                    request.url.scheme !in setOf("http", "https", "file", "about", "data", "blob")
            }
        }
        webView = wv

        val bar = WebView(ctx).apply {
            setBackgroundColor(Color.parseColor("#0F1218"))
            settings.javaScriptEnabled = true
            isVerticalScrollBarEnabled = false
            overScrollMode = View.OVER_SCROLL_NEVER
            visibility = View.GONE
            addJavascriptInterface(ToolbarApi(), "DashplayChrome")
            webViewClient = object : WebViewClient() {
                override fun onPageFinished(view: WebView, url: String) = updateToolbar()
            }
            loadUrl("$ASSET_ROOT/chrome.html")
        }
        toolbar = bar

        val toolbarHeight = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 60f, ctx.resources.displayMetrics).toInt()
        val column = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            addView(bar, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, toolbarHeight))
            addView(wv, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
        }
        val cover = buildLockCover(ctx)
        lockCover = cover
        return FrameLayout(ctx).apply {
            root = this
            addView(column, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
            addView(cover, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        }
    }

    /** Covers the page while the car is moving. */
    private fun buildLockCover(ctx: Context): View {
        fun text(value: String, sp: Float, color: String, bold: Boolean) = TextView(ctx).apply {
            text = value
            gravity = Gravity.CENTER
            setTextColor(Color.parseColor(color))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, sp)
            if (bold) typeface = android.graphics.Typeface.DEFAULT_BOLD
        }
        return LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setBackgroundColor(Color.parseColor("#F00B0D12"))
            visibility = if (locked) View.VISIBLE else View.GONE
            isClickable = true
            addView(text("🅿️", 64f, "#FFFFFF", false))
            addView(text(ctx.getString(R.string.lock_title), 30f, "#F2F4F8", true).apply { setPadding(0, 24, 0, 8) })
            addView(text(ctx.getString(R.string.lock_body), 18f, "#8B95A7", false))
        }
    }

    private companion object {
        const val ASSET_ROOT = "file:///android_asset"
        const val OFFLINE_URL = "$ASSET_ROOT/offline.html"
        val ZOOM_STEPS = listOf(100, 125, 150)
    }
}
