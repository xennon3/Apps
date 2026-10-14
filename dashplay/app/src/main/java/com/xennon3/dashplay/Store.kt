package com.xennon3.dashplay

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.webkit.JavascriptInterface
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors

/**
 * Tools and games downloaded from the online store (github.com/xennon3/dashplay-store).
 *
 * The catalog is `catalog.json` at [BASE_URL]; each item's files live under `items/<id>/`.
 * Installing copies those files into app storage, where [Local] serves them at
 * `/store/<id>/`, so they keep working without a signal. The installed list is written as
 * `/store/installed.js` so any Dashplay page can read it with a plain <script> tag.
 */
object Store {
    const val BASE_URL = "https://raw.githubusercontent.com/xennon3/dashplay-store/main/"
    private const val MAX_ITEM_BYTES = 8 * 1024 * 1024

    private val worker = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())
    private val listeners = mutableSetOf<() -> Unit>()

    /** Called on the main thread after anything is installed or removed. */
    fun observe(listener: () -> Unit) = listeners.add(listener)
    fun stopObserving(listener: () -> Unit) = listeners.remove(listener)

    data class Item(val id: String, val kind: String, val title: String, val emoji: String, val blurb: String, val entry: String) {
        val url get() = "${Local.STORE}/$id/$entry"
    }

    fun installed(context: Context): List<Item> {
        val array = installedJson(context)
        return (0 until array.length()).map {
            val o = array.getJSONObject(it)
            Item(o.getString("id"), o.optString("kind", "tool"), o.getString("title"), o.optString("emoji", "📦"),
                o.optString("blurb"), o.optString("entry", "index.html"))
        }
    }

    private fun installedJson(context: Context): JSONArray =
        runCatching { JSONArray(File(Local.storeDir(context), "installed.json").readText()) }.getOrDefault(JSONArray())

    /**
     * Downloads every file of a catalog item, then swaps it into place. A failed download
     * leaves the previous version untouched.
     */
    fun install(context: Context, item: JSONObject, done: (String?) -> Unit) = worker.execute {
        val error = runCatching { download(context, item) }.exceptionOrNull()?.let { it.message ?: it.javaClass.simpleName }
        finish(done, error)
    }

    fun remove(context: Context, id: String, done: (String?) -> Unit) = worker.execute {
        val error = runCatching {
            safeId(id)
            File(Local.storeDir(context), id).deleteRecursively()
            writeRegistry(context, installedList(context).filterNot { it.getString("id") == id })
        }.exceptionOrNull()?.message
        finish(done, error)
    }

    private fun finish(done: (String?) -> Unit, error: String?) = main.post {
        done(error)
        if (error == null) listeners.toList().forEach { it() }
    }

    private fun download(context: Context, item: JSONObject) {
        val id = safeId(item.getString("id"))
        val files = item.getJSONArray("files")
        val root = Local.storeDir(context)
        val staging = File(root, ".$id.part").apply { deleteRecursively(); mkdirs() }
        var total = 0
        for (i in 0 until files.length()) {
            val name = files.getString(i)
            require(!name.startsWith("/") && name.split('/').none { it == ".." || it.isEmpty() }) { "Bad file name: $name" }
            val bytes = fetch("${BASE_URL}items/$id/$name")
            total += bytes.size
            require(total <= MAX_ITEM_BYTES) { "Too big to install" }
            File(staging, name).apply { parentFile?.mkdirs() }.writeBytes(bytes)
        }
        val target = File(root, id)
        target.deleteRecursively()
        check(staging.renameTo(target)) { "Couldn't save $id" }

        val entry = JSONObject()
        listOf("id", "kind", "title", "emoji", "color", "blurb", "version", "entry").forEach { key ->
            if (item.has(key)) entry.put(key, item.get(key))
        }
        writeRegistry(context, installedList(context).filterNot { it.getString("id") == id } + entry)
    }

    private fun installedList(context: Context): List<JSONObject> {
        val array = installedJson(context)
        return (0 until array.length()).map { array.getJSONObject(it) }
    }

    private fun writeRegistry(context: Context, items: List<JSONObject>) {
        val json = JSONArray(items).toString()
        val dir = Local.storeDir(context)
        File(dir, "installed.json").writeText(json)
        File(dir, "installed.js").writeText("window.DASHPLAY_INSTALLED = $json;\n")
    }

    private fun fetch(url: String): ByteArray {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.connectTimeout = 15_000
        conn.readTimeout = 20_000
        try {
            check(conn.responseCode == 200) { "Download failed (${conn.responseCode})" }
            return conn.inputStream.use { it.readBytes() }
        } finally {
            conn.disconnect()
        }
    }

    /** Ids become folder names, so keep them to simple characters. */
    private fun safeId(id: String) = id.also { require(Regex("[a-z0-9-]{1,40}").matches(it)) { "Bad item id" } }

    /**
     * `DashplayStore` for store.html. Only Dashplay's bundled pages may use it, never a
     * website or a downloaded tool. [currentUrl] is read on the WebView's binder thread,
     * so it must be a value the page's WebViewClient keeps up to date.
     */
    class Api(
        private val context: Context,
        private val currentUrl: () -> String,
        private val reply: (String) -> Unit,
    ) {
        private fun trusted() = currentUrl().startsWith("${Local.ASSETS}/")

        @JavascriptInterface
        fun catalogUrl(): String = if (trusted()) "${BASE_URL}catalog.json" else ""

        @JavascriptInterface
        fun installed(): String = if (trusted()) installedJson(context).toString() else "[]"

        @JavascriptInterface
        fun install(itemJson: String) {
            if (!trusted()) return
            val item = JSONObject(itemJson)
            install(context, item) { error -> answer("install", item.optString("id"), error) }
        }

        @JavascriptInterface
        fun remove(id: String) {
            if (trusted()) remove(context, id) { error -> answer("remove", id, error) }
        }

        private fun answer(action: String, id: String, error: String?) {
            val result = JSONObject().put("action", action).put("id", id).put("ok", error == null)
            if (error != null) result.put("error", error)
            reply("window.onStoreResult && onStoreResult($result)")
        }
    }
}
