package com.xennon3.dashplay.car

import android.annotation.SuppressLint
import android.app.Presentation
import android.content.Context
import android.graphics.Color
import android.graphics.Rect
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.os.SystemClock
import android.util.TypedValue
import android.view.Gravity
import android.view.InputDevice
import android.view.MotionEvent
import android.view.View
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.widget.TextView
import androidx.car.app.SurfaceContainer
import com.xennon3.dashplay.GameKey
import org.json.JSONObject

/**
 * Draws a real WebView onto the car's screen.
 *
 * Android Auto hands navigation apps a bare Surface. We wrap that Surface in a private
 * VirtualDisplay, show a Presentation (a window bound to that display) on it, and put the
 * WebView inside. Taps from the car come back in surface coordinates, which are the same
 * as the Presentation's coordinates, so they can be replayed as touch events directly.
 */
class WebSurface(private val context: Context, private val onPageChanged: (String) -> Unit) {

    private var display: VirtualDisplay? = null
    private var presentation: Presentation? = null
    private var root: FrameLayout? = null
    private var lockCover: View? = null
    private var pendingUrl: String? = null
    private var width = 0
    private var height = 0

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
        presentation = Presentation(context, vd.display).also {
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
        presentation?.dismiss()
        display?.release()
        webView = null
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

    fun back(): Boolean {
        val wv = webView ?: return false
        if (!wv.canGoBack()) return false
        wv.goBack()
        return true
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

    @SuppressLint("SetJavaScriptEnabled")
    private fun buildContent(ctx: Context): View {
        val wv = WebView(ctx).apply {
            setBackgroundColor(Color.parseColor("#0E1116"))
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.mediaPlaybackRequiresUserGesture = false
            settings.loadWithOverviewMode = true
            settings.useWideViewPort = true
            settings.builtInZoomControls = true
            settings.displayZoomControls = false
            webChromeClient = WebChromeClient()
            webViewClient = object : WebViewClient() {
                override fun onPageFinished(view: WebView, url: String) = onPageChanged(url)
            }
        }
        webView = wv
        val cover = TextView(ctx).apply {
            text = "Paused while driving\nPark to keep playing"
            textAlignment = View.TEXT_ALIGNMENT_CENTER
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 28f)
            setBackgroundColor(Color.parseColor("#E60E1116"))
            visibility = if (locked) View.VISIBLE else View.GONE
            isClickable = true
        }
        lockCover = cover
        return FrameLayout(ctx).apply {
            root = this
            addView(wv, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
            addView(cover, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        }
    }
}
