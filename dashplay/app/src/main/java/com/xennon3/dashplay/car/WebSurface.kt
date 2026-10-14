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
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.car.app.SurfaceContainer
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import com.xennon3.dashplay.CarBridge
import com.xennon3.dashplay.GameKey
import com.xennon3.dashplay.History
import com.xennon3.dashplay.Local
import com.xennon3.dashplay.R
import com.xennon3.dashplay.Store
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
        /** The player tapped a text box on a page; [field] describes it (see kit/keys.js). */
        fun onTextFieldTapped(field: JSONObject)
    }

    private val main = Handler(Looper.getMainLooper())
    private val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)
    private val assets = Local.loader(context)

    private var display: VirtualDisplay? = null
    private var presentation: Presentation? = null
    private var root: FrameLayout? = null
    private var toolbar: WebView? = null
    private var toolbarHandle: View? = null
    private var lockCover: View? = null
    private var fullscreenView: View? = null
    private var fullscreenDone: WebChromeClient.CustomViewCallback? = null
    private var visibleArea: Rect? = null
    private var containerDpi = 160
    private var appliedDpi = 0
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
        containerDpi = container.dpi
        val dpi = displayDpi()
        val existing = display
        if (existing != null) {
            // Same WebView, new surface: the page keeps its state across screen changes,
            // unless the new size needs a different scale.
            existing.resize(width, height, dpi)
            existing.surface = surface
            if (dpi != appliedDpi) rebuild()
            return
        }
        val dm = context.getSystemService(DisplayManager::class.java)
        display = dm.createVirtualDisplay(
            "Dashplay", width, height, dpi, surface,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_OWN_CONTENT_ONLY,
        )
        rebuild()
        pendingUrl?.let { load(it) }
        pendingUrl = null
    }

    /**
     * The density pages are drawn at. Car screens report a high density for a short screen,
     * which would leave pages only a few hundred CSS pixels tall; lowering it gives every page
     * at least [MIN_PAGE_HEIGHT] CSS pixels of height. The phone's "Car screen size" setting
     * nudges it either way.
     */
    private fun displayDpi(): Int {
        val fit = minOf(containerDpi, height * 160 / MIN_PAGE_HEIGHT)
        val nudge = when (CarBridge.screenScale) { -1 -> 0.85f; 1 -> 1.2f; else -> 1f }
        return (fit * nudge).toInt().coerceIn(96, maxOf(containerDpi, 96) * 2)
    }

    /** Call after the "Car screen size" setting changes. */
    fun rescale() {
        val vd = display ?: return
        val dpi = displayDpi()
        if (dpi == appliedDpi) return
        vd.resize(width, height, dpi)
        rebuild()
    }

    /**
     * Shows fresh content on the virtual display: a new Presentation picks up the display's
     * current density. Reloads the current page, if there is one.
     */
    private fun rebuild() {
        val vd = display ?: return
        val url = pageUrl
        exitFullscreen()
        webView?.destroy()
        toolbar?.destroy()
        presentation?.dismiss()
        appliedDpi = displayDpi()
        // A dark theme lets web pages switch to their dark mode (see buildContent).
        presentation = Presentation(context, vd.display, R.style.Theme_Dashplay).also {
            it.setContentView(buildContent(it.context))
            it.show()
        }
        visibleArea?.let { setVisibleArea(it) }
        if (locked) webView?.onPause()
        if (url.isNotEmpty()) load(url)
    }

    fun detach() {
        display?.surface = null
    }

    fun release() {
        exitFullscreen()
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
        visibleArea = area
        val r = root ?: return
        if (area.isEmpty) {
            r.setPadding(0, 0, 0, 0)
        } else {
            r.setPadding(area.left, area.top, (width - area.right).coerceAtLeast(0), (height - area.bottom).coerceAtLeast(0))
        }
    }

    fun load(url: String) {
        val wv = webView
        if (wv == null) {
            pendingUrl = url
            return
        }
        exitFullscreen()
        wv.loadUrl(url)
    }

    fun nav(action: String) {
        val wv = webView ?: return
        // Back first leaves full-screen video, like a phone's back button.
        if (action == "back" && exitFullscreen()) return
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

    /**
     * The car's compass heading, in degrees. Only Dashplay's own pages get it: a website has
     * no business knowing which way the car is pointing.
     */
    fun heading(degrees: Float) {
        if (!Local.isLocal(pageUrl)) return
        js(event("""{type:"heading",degrees:$degrees}"""))
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

    /**
     * Types into the text box the player last tapped, replacing what's there, and presses
     * Enter if [submit]. The text comes from the car keyboard screen (TypeScreen).
     */
    fun fillField(text: String, submit: Boolean) {
        js("window.__dashplayFill && __dashplayFill(${JSONObject.quote(text)}, $submit)")
    }

    private fun event(detail: String) = "window.dispatchEvent(new CustomEvent('dashplay',{detail:$detail}));"

    private fun js(code: String) {
        webView?.evaluateJavascript(code, null)
    }

    /**
     * A page crashed or ran out of memory, which took its WebView down. Build fresh ones and
     * go back to where we were, rather than letting Android close the whole app.
     */
    private fun recover(): Boolean {
        main.post {
            if (pageUrl.isEmpty()) pageUrl = CarBridge.HOME_URL
            rebuild()
        }
        return true
    }

    // Full-screen video (a page's own full-screen button, e.g. YouTube's)

    private fun enterFullscreen(view: View, done: WebChromeClient.CustomViewCallback) {
        val r = root
        if (r == null || fullscreenView != null) {
            done.onCustomViewHidden()
            return
        }
        fullscreenView = view
        fullscreenDone = done
        view.setBackgroundColor(Color.BLACK)
        // Above the page and toolbar, below the driving-lock cover.
        r.addView(view, r.indexOfChild(lockCover), FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
    }

    private fun exitFullscreen(): Boolean {
        val view = fullscreenView ?: return false
        root?.removeView(view)
        fullscreenView = null
        fullscreenDone?.onCustomViewHidden()
        fullscreenDone = null
        return true
    }

    // Browser toolbar

    private val textZoom get() = prefs.getInt("textZoom", 100)

    /** Web pages and the error page get the toolbar; games and Dashplay's own pages don't. */
    private fun isBrowsing(url: String) = Local.isWeb(url) || url.startsWith(OFFLINE_URL)

    private val toolbarHidden get() = prefs.getBoolean("toolbarHidden", false)

    private fun setToolbarHidden(hidden: Boolean) {
        prefs.edit().putBoolean("toolbarHidden", hidden).apply()
        updateToolbar()
    }

    private fun updateToolbar() {
        val bar = toolbar ?: return
        val wv = webView ?: return
        val browsing = isBrowsing(pageUrl)
        bar.visibility = if (browsing && !toolbarHidden) View.VISIBLE else View.GONE
        // A small tab brings a hidden toolbar back.
        toolbarHandle?.visibility = if (browsing && toolbarHidden) View.VISIBLE else View.GONE
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
        if (Local.isWeb(pageUrl)) webView?.settings?.textZoom = next
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
        @JavascriptInterface fun hide() { main.post { setToolbarHidden(true) } }
    }

    /** kit/keys.js, injected into every page so tapped text boxes can use the car keyboard. */
    private val keysScript by lazy { context.assets.open("kit/keys.js").bufferedReader().use { it.readText() } }

    /** Called by kit/keys.js. Any page may call it; all it can do is open the keyboard screen. */
    private inner class KeysApi {
        @JavascriptInterface
        fun open(json: String) {
            val field = runCatching { JSONObject(json) }.getOrNull() ?: return
            main.post { if (!locked) listener.onTextFieldTapped(field) }
        }
    }

    /**
     * For Dashplay's own pages (the start page). Websites load into the same WebView, so
     * every call checks that the current page is one of ours before doing anything.
     */
    private inner class CarPageApi {
        // Bundled pages only; tools downloaded from the store don't get these calls.
        private fun trusted() = pageUrl.startsWith("${Local.ASSETS}/")

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
            // Sites with a dark mode use it; the rest are darkened to match the car UI.
            if (Build.VERSION.SDK_INT >= 33) settings.isAlgorithmicDarkeningAllowed = true
            addJavascriptInterface(CarPageApi(), "DashplayCar")
            addJavascriptInterface(KeysApi(), "DashplayKeys")
            // Run keys.js before each page's own scripts where the WebView supports it;
            // otherwise onPageFinished adds it.
            if (WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) {
                WebViewCompat.addDocumentStartJavaScript(this, keysScript, setOf("*"))
            }
            addJavascriptInterface(Store.Api(context, { pageUrl }, { js -> webView?.evaluateJavascript(js, null) }), "DashplayStore")
            webChromeClient = object : WebChromeClient() {
                override fun onProgressChanged(view: WebView, newProgress: Int) {
                    pageProgress = newProgress
                    updateToolbar()
                }

                override fun onReceivedTitle(view: WebView, title: String) {
                    updateToolbar()
                    listener.onPageChanged(view.url ?: pageUrl, title)
                }

                override fun onShowCustomView(view: View, callback: CustomViewCallback) = enterFullscreen(view, callback)

                override fun onHideCustomView() {
                    exitFullscreen()
                }
            }
            webViewClient = object : WebViewClient() {
                override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest) =
                    assets.shouldInterceptRequest(request.url)

                override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail) = recover()

                override fun onPageStarted(view: WebView, url: String, favicon: android.graphics.Bitmap?) {
                    pageUrl = url
                    // The Aa text size is for websites; games and Dashplay's pages lay out their own text.
                    view.settings.textZoom = if (Local.isWeb(url)) textZoom else 100
                    pageLoading = true
                    pageProgress = 10
                    updateToolbar()
                }

                override fun onPageFinished(view: WebView, url: String) {
                    view.evaluateJavascript(keysScript, null)
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
                    request.url.scheme !in setOf("http", "https", "about", "data", "blob")
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
                override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest) =
                    assets.shouldInterceptRequest(request.url)

                override fun onPageFinished(view: WebView, url: String) = updateToolbar()

                override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail) = recover()
            }
            loadUrl("${Local.ASSETS}/chrome.html")
        }
        toolbar = bar

        val dp = { v: Float -> TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, ctx.resources.displayMetrics).toInt() }
        val toolbarHeight = dp(46f)
        val column = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            addView(bar, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, toolbarHeight))
            addView(wv, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
        }
        val handle = TextView(ctx).apply {
            text = "⌄  Toolbar"
            setTextColor(Color.parseColor("#F2F4F8"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            gravity = Gravity.CENTER
            setPadding(dp(16f), 0, dp(16f), 0)
            background = android.graphics.drawable.GradientDrawable().apply {
                setColor(Color.parseColor("#CC1E2430"))
                cornerRadii = floatArrayOf(0f, 0f, 0f, 0f, 99f, 99f, 99f, 99f)
            }
            visibility = View.GONE
            setOnClickListener { setToolbarHidden(false) }
        }
        toolbarHandle = handle
        val cover = buildLockCover(ctx)
        lockCover = cover
        return FrameLayout(ctx).apply {
            root = this
            addView(column, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
            addView(handle, FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT, dp(28f), Gravity.TOP or Gravity.CENTER_HORIZONTAL))
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
        const val OFFLINE_URL = "${Local.ASSETS}/offline.html"
        val ZOOM_STEPS = listOf(100, 125, 150)

        /** Pages always get at least this many CSS pixels of height. */
        const val MIN_PAGE_HEIGHT = 440
    }
}
