package dev.llmkeyboard

import android.app.Activity
import android.app.ActivityManager
import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import android.text.InputType
import android.view.WindowManager
import android.widget.EditText
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
    private lateinit var dlBtn: Button
    @Volatile private var downloading = false
    @Volatile private var cancel = false
    private class Fatal(msg: String) : Exception(msg)

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

        val token = EditText(this).apply {
            hint = "Hugging Face token (hf_…)"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            setText(prefs.getString("hf_token", ""))
        }
        dlBtn = Button(this).apply {
            text = DL_LABEL
            setOnClickListener {
                val t = token.text.toString().trim()
                prefs.edit().putString("hf_token", t).apply()
                if (downloading) cancel = true else startDownload(t)
            }
        }
        col.addView(TextView(this).apply { text = "Or download in-app (needs a free Hugging Face Read token, and you must have accepted the Gemma license on the model page):"; textSize = 12f })
        col.addView(token); col.addView(dlBtn)
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
        diag.text = "--- diagnostics ---\n" +  (if (ModelStore.exists(this)) "model.task = ${ModelStore.file(this).length()} bytes, header ${header(ModelStore.file(this))}\n" else "") + runCatching { ModelStore.log(this).readText() }.getOrDefault("(no log yet)") + exits
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
                        "\"$name\" is incomplete: no zip end record (${tmp.length()} bytes, header ${header(tmp)}). Download it again."
                    else -> null
                }
            } catch (e: Exception) { "Copy failed: ${e.message}" }
            if (problem == null) { ModelStore.file(this).delete(); tmp.renameTo(ModelStore.file(this)) } else tmp.delete()
            runOnUiThread { refreshStatus(); if (problem != null) status.text = problem }
        }
    }

    private fun header(f: File): String = runCatching {
        f.inputStream().use { i -> ByteArray(16).also { i.read(it) }.joinToString(" ") { "%02x".format(it) } }
    }.getOrDefault("?")

    /**
     * A zip is valid when its end-of-central-directory record ("PK\u0005\u0006") sits in the last 64 KB.
     * Model bundles can have data before the first "PK", so the start of the file proves nothing,
     * while a truncated download has no such record.
     */
    private fun isZip(f: File): Boolean = runCatching {
        java.io.RandomAccessFile(f, "r").use { r ->
            val n = minOf(r.length(), 70_000L).toInt()
            val tail = ByteArray(n); r.seek(r.length() - n); r.readFully(tail)
            (0..n - 4).any { tail[it] == 0x50.toByte() && tail[it + 1] == 0x4b.toByte() && tail[it + 2] == 5.toByte() && tail[it + 3] == 6.toByte() }
        }
    }.getOrDefault(false)

    private fun startDownload(token: String) {
        downloading = true; cancel = false
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        dlBtn.text = "Cancel download"
        val part = File(filesDir, "model.part")
        thread {
            var result: String? = null
            while (!cancel) {
                try { fetch(token, part); result = ""; break }
                catch (e: Fatal) { result = e.message; break }
                catch (e: IOException) {
                    runOnUiThread { status.text = "Connection dropped, retrying… (${part.length() shr 20} MB saved)" }
                    Thread.sleep(3000)
                }
            }
            if (result == "") {
                result = if (!isZip(part)) { part.delete(); "Download finished but the file end is missing (${part.length()} bytes, header ${header(part)})." }
                else { ModelStore.file(this).delete(); part.renameTo(ModelStore.file(this)); null }
            }
            downloading = false
            runOnUiThread {
                window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                dlBtn.text = DL_LABEL
                refreshStatus()
                if (cancel) status.text = "Paused at ${part.length() shr 20} MB. Tap download to resume."
                else if (result != null) status.text = result
            }
        }
    }

    /** Resumable download: follows redirects by hand so the token only goes to huggingface.co. */
    private fun fetch(token: String, part: File) {
        var have = part.length()
        var url = URL(MODEL_URL)
        var conn: HttpURLConnection
        var hops = 0
        while (true) {
            conn = url.openConnection() as HttpURLConnection
            conn.instanceFollowRedirects = false
            conn.connectTimeout = 15000; conn.readTimeout = 30000
            if (token.isNotEmpty() && url.host.endsWith("huggingface.co")) conn.setRequestProperty("Authorization", "Bearer $token")
            if (have > 0) conn.setRequestProperty("Range", "bytes=$have-")
            val code = conn.responseCode
            if (code in 301..308 && hops++ < 6) { url = URL(url, conn.getHeaderField("Location")); conn.disconnect(); continue }
            when (code) {
                401, 403 -> throw Fatal("Access denied ($code). Check your token, and accept the Gemma license on the model page.")
                404 -> throw Fatal("Model file not found (404).")
                416 -> { conn.disconnect(); return }                       // already complete
                200 -> { part.delete(); have = 0 }                         // server ignored Range: start over
                206 -> {}
                else -> throw IOException("HTTP $code")
            }
            break
        }
        val total = if (conn.responseCode == 206) conn.getHeaderField("Content-Range").substringAfter('/').toLong()
                    else conn.contentLengthLong
        var last = 0L
        conn.inputStream.use { input ->
            java.io.FileOutputStream(part, have > 0).use { out ->
                val buf = ByteArray(64 * 1024)
                var done = have
                while (!cancel) {
                    val n = input.read(buf); if (n < 0) break
                    out.write(buf, 0, n); done += n
                    val now = System.currentTimeMillis()
                    if (now - last > 500) { last = now; runOnUiThread { status.text = "Downloading ${done shr 20} / ${total shr 20} MB" } }
                }
            }
        }
        if (cancel) return
        if (part.length() != total) throw IOException("short read")
    }

    private companion object {
        const val MODEL_URL = "https://huggingface.co/litert-community/Gemma3-1B-IT/resolve/main/gemma3-1b-it-int4.task"
        const val DL_LABEL = "Download Gemma 3 1B (resumes if it drops)"
    }
}
