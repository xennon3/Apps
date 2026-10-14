package com.xennon3.dashplay

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/** Web pages visited on the car, newest first. Shown on the start page and the phone. */
object History {
    private const val PREFS = "history"
    private const val KEY = "pages"
    private const val MAX = 24

    fun add(context: Context, url: String, title: String) {
        if (!url.startsWith("http")) return
        val pages = read(context).filterNot { it.getString("url") == url }.toMutableList()
        pages.add(0, JSONObject().put("url", url).put("title", title.ifBlank { url }))
        write(context, pages.take(MAX))
    }

    fun json(context: Context): String = JSONArray(read(context)).toString()

    fun clear(context: Context) = write(context, emptyList())

    private fun read(context: Context): List<JSONObject> {
        val raw = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, "[]")
        val array = runCatching { JSONArray(raw) }.getOrDefault(JSONArray())
        return (0 until array.length()).map { array.getJSONObject(it) }
    }

    private fun write(context: Context, pages: List<JSONObject>) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(KEY, JSONArray(pages).toString()).apply()
    }
}
