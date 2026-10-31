package dev.llmkeyboard

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.os.Handler
import android.os.Looper
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView

/** Whole keyboard built in code: suggestion strip, AI action row, QWERTY / symbols. */
class KeyboardUi(
    private val ctx: Context,
    private val onText: (String) -> Unit,
    private val onBackspace: () -> Unit,
    private val onEnter: () -> Unit,
    private val onWord: (String) -> Unit,
    private val onSentence: () -> Unit,
    private val onTranslate: () -> Unit,
    private val onLanguage: () -> Unit,
    private val onSimplify: () -> Unit,
    private val onFix: () -> Unit,
) {
    val root = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(BG) }
    private val strip = row()
    private val words = List(2) { chip(ACCENT_NONE) { w -> if (w.isNotEmpty()) onWord(w) } }
    private val sentence = chip(ACCENT) { onSentence() }
    private val actions = row()
    private val langChip: TextView
    private val keys = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
    private var shift = false
    private var symbols = false
    private val handler = Handler(Looper.getMainLooper())
    var azerty = true
        set(v) { if (field != v) { field = v; render() } }

    init {
        words.forEach { strip.addView(it, lp(1f)) }
        strip.addView(sentence, lp(2f))
        root.addView(strip)

        actions.addView(action("Translate") { onTranslate() }, lp(1f))
        langChip = action("EN") { onLanguage() }
        actions.addView(langChip, lp(0.5f))
        actions.addView(action("Simplify") { onSimplify() }, lp(1f))
        actions.addView(action("Fix") { onFix() }, lp(0.7f))
        root.addView(actions)
        root.addView(keys)
        render()
    }

    fun setWords(list: List<String>) = words.forEachIndexed { i, v -> v.text = list.getOrElse(i) { "" } }
    fun setSentence(text: String) { sentence.text = if (text.isEmpty()) "" else "✨ $text" }
    fun setLanguage(name: String) { langChip.text = name.take(2).uppercase() }
    fun status(text: String) { sentence.text = text }

    /** Never rebuild the keys from inside a touch handler: the pressed view is being dispatched to. */
    private fun rerender() { handler.post { render() } }

    private fun render() {
        keys.removeAllViews()
        val rows = if (symbols) listOf("1234567890", "@#$%&-+()/", "*\"':;!?")
        else if (azerty) listOf("azertyuiop", "qsdfghjklm", "wxcvbn")
        else listOf("qwertyuiop", "asdfghjkl", "zxcvbnm")
        rows.forEachIndexed { i, r ->
            val line = row()
            if (i == 2 && !symbols) line.addView(key(if (shift) "⬆" else "⇧", 1.4f) { shift = !shift; rerender() })
            r.forEach { c ->
                val s = if (shift && c.isLetter()) c.uppercaseChar().toString() else c.toString()
                line.addView(key(s, 1f) { onText(s); if (shift) { shift = false; rerender() } })
            }
            if (i == 2) line.addView(key("⌫", 1.4f, repeat = true) { onBackspace() })
            keys.addView(line)
        }
        val bottom = row()
        bottom.addView(key(if (symbols) "ABC" else "?123", 1.4f) { symbols = !symbols; rerender() })
        bottom.addView(key(",", 1f) { onText(",") })
        bottom.addView(key("space", 5f) { onText(" ") })
        bottom.addView(key(".", 1f) { onText(".") })
        bottom.addView(key("↵", 1.4f) { onEnter() })
        keys.addView(bottom)
    }

    private fun dp(v: Int) = (v * ctx.resources.displayMetrics.density).toInt()
    private fun row() = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL }
    private fun lp(w: Float) = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, w)
        .apply { setMargins(dp(2), dp(2), dp(2), dp(2)) }

    private fun pill(color: Int) = GradientDrawable().apply { setColor(color); cornerRadius = dp(8).toFloat() }

    private fun label(text: String, color: Int, size: Float) = TextView(ctx).apply {
        this.text = text; textSize = size; setTextColor(Color.WHITE); gravity = Gravity.CENTER
        background = pill(color); isClickable = true; maxLines = 1
        ellipsize = android.text.TextUtils.TruncateAt.END
    }

    /** Fires on finger-DOWN (not on release) so fast typing never drops keys. */
    private fun View.onPress(repeat: Boolean = false, tap: () -> Unit) {
        val again = object : Runnable { override fun run() { tap(); handler.postDelayed(this, 55) } }
        setOnTouchListener { v, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    v.alpha = 0.6f; v.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP); tap()
                    if (repeat) handler.postDelayed(again, 380)
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> { v.alpha = 1f; handler.removeCallbacks(again) }
            }
            true
        }
    }

    private fun key(text: String, weight: Float, repeat: Boolean = false, tap: () -> Unit): View =
        label(text, KEY, 18f).apply { minHeight = dp(46); setPadding(0, dp(10), 0, dp(10)); onPress(repeat, tap) }
            .also { it.layoutParams = lp(weight) }

    private fun chip(color: Int, tap: (String) -> Unit): TextView =
        label("", color, 15f).apply {
            minHeight = dp(40); setPadding(dp(6), dp(8), dp(6), dp(8))
            onPress { tap(text.toString().removePrefix("✨ ")) }
            if (color == ACCENT) setTypeface(typeface, Typeface.ITALIC)
        }

    private fun action(text: String, tap: () -> Unit): TextView =
        label(text, ACTION, 13f).apply { setPadding(0, dp(6), 0, dp(6)); onPress { tap() } }

    private companion object {
        val BG = Color.parseColor("#1B1B1F"); val KEY = Color.parseColor("#34343A")
        val ACTION = Color.parseColor("#2B3A55"); val ACCENT = Color.parseColor("#4A3A8C")
        val ACCENT_NONE = Color.parseColor("#26262B")
    }
}
