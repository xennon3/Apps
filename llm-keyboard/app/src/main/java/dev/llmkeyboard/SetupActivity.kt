package dev.llmkeyboard

import android.app.Activity
import android.app.ActivityManager
import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import java.io.File
import android.os.Bundle
import android.provider.Settings
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import kotlin.concurrent.thread

class SetupActivity : Activity() {
    private lateinit var status: TextView
    private lateinit var diag: TextView

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        val pad = (20 * resources.displayMetrics.density).toInt()
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(pad, pad, pad, pad) }
        status = TextView(this).apply { textSize = 15f }
        col.addView(TextView(this).apply { text = "LLM Keyboard"; textSize = 24f })
        col.addView(status)
        fun btn(t: String, f: () -> Unit) = col.addView(Button(this).apply { text = t; setOnClickListener { f() } })
        btn("1. Enable keyboard") { startActivity(Intent(Settings.ACTION_INPUT_METHOD_SETTINGS)) }
        btn("2. Load model file (.task)") {
            startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE); type = "*/*"
            }, 1)
        }
        btn("3. Switch to this keyboard") {
            (getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager).showInputMethodPicker()
        }
        val prefs = getSharedPreferences("prefs", MODE_PRIVATE)
        val layout = Button(this)
        fun label() { layout.text = "Layout: " + if (prefs.getBoolean("azerty", true)) "AZERTY (tap for QWERTY)" else "QWERTY (tap for AZERTY)" }
        layout.setOnClickListener { prefs.edit().putBoolean("azerty", !prefs.getBoolean("azerty", true)).apply(); label() }
        label(); col.addView(layout)
        diag = TextView(this).apply { textSize = 11f; setTextIsSelectable(true) }
        col.addView(diag)
        setContentView(android.widget.ScrollView(this).apply { addView(col) })
        refreshStatus()
    }

    override fun onResume() { super.onResume(); if (::diag.isInitialized) showDiag() }

    /** Everything needed to debug a keyboard problem, selectable so it can be copied. */
    private fun showDiag() {
        val am = getSystemService(ACTIVITY_SERVICE) as ActivityManager
        val exits = if (android.os.Build.VERSION.SDK_INT >= 30)
            am.getHistoricalProcessExitReasons(packageName, 0, 3).joinToString("\n") { "exit: reason=${it.reason} ${it.description}" } else ""
        diag.text = "--- diagnostics ---\n" +  (if (ModelStore.exists(this)) "model.task = ${ModelStore.file(this).length()} bytes\n" else "") + runCatching { ModelStore.log(this).readText() }.getOrDefault("(no log yet)") + exits
    }

    private fun refreshStatus() {
        status.text = if (ModelStore.exists(this)) "Model loaded (${ModelStore.file(this).length() shr 20} MB). All processing stays on this device."
        else "No model yet. Download an open-weights model (see README) and pick it below."
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(req: Int, res: Int, data: Intent?) {
        super.onActivityResult(req, res, data)
        val uri: Uri = data?.data ?: return
        status.text = "Copying model… keep this screen open"
        thread {
            var name = ""; var size = -1L
            contentResolver.query(uri, null, null, null, null)?.use { c ->
                if (c.moveToFirst()) {
                    c.getColumnIndex(OpenableColumns.DISPLAY_NAME).takeIf { it >= 0 }?.let { name = c.getString(it) }
                    c.getColumnIndex(OpenableColumns.SIZE).takeIf { it >= 0 }?.let { size = c.getLong(it) }
                }
            }
            val tmp = File(filesDir, "model.tmp")
            val problem: String? = try {
                contentResolver.openInputStream(uri)!!.use { i -> tmp.outputStream().use { o -> i.copyTo(o) } }
                when {
                    size > 0 && tmp.length() != size -> "Copy incomplete (${tmp.length()} of $size bytes). Try again."
                    name.endsWith(".task") && !isZip(tmp) ->
                        "\"$name\" is corrupt or its download was cut short (${tmp.length()} bytes). Delete it and download again."
                    else -> null
                }
            } catch (e: Exception) { "Copy failed: ${e.message}" }
            if (problem == null) { ModelStore.file(this).delete(); tmp.renameTo(ModelStore.file(this)) } else tmp.delete()
            runOnUiThread { refreshStatus(); if (problem != null) status.text = problem }
        }
    }

    private fun isZip(f: File) = try { java.util.zip.ZipFile(f).close(); true } catch (e: Exception) { false }
}
