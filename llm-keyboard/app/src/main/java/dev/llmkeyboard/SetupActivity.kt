package dev.llmkeyboard

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import kotlin.concurrent.thread

class SetupActivity : Activity() {
    private lateinit var status: TextView

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
        setContentView(col)
        refreshStatus()
    }

    private fun refreshStatus() {
        status.text = if (ModelStore.exists(this)) "Model loaded (${ModelStore.file(this).length() shr 20} MB). All processing stays on this device."
        else "No model yet. Download an open-weights model (see README) and pick it below."
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(req: Int, res: Int, data: Intent?) {
        super.onActivityResult(req, res, data)
        val uri: Uri = data?.data ?: return
        status.text = "Copying model…"
        thread {
            runCatching {
                contentResolver.openInputStream(uri)!!.use { i -> ModelStore.file(this).outputStream().use { o -> i.copyTo(o) } }
            }.onFailure { ModelStore.file(this).delete() }
            runOnUiThread { refreshStatus() }
        }
    }
}
