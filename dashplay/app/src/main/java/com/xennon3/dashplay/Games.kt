package com.xennon3.dashplay

import android.content.Context
import org.json.JSONArray

/** A game bundled in assets/games. Each one is a single web page. */
data class Game(val id: String, val title: String, val emoji: String, val blurb: String) {
    val url get() = "file:///android_asset/games/$id.html"
}

/** Reads the shared list in assets/kit/games.js, the same one the web pages use. */
fun loadGames(context: Context): List<Game> {
    val source = context.assets.open("kit/games.js").bufferedReader().use { it.readText() }
    val json = JSONArray(source.substring(source.indexOf('=') + 1).trim().removeSuffix(";"))
    return (0 until json.length()).map {
        val g = json.getJSONObject(it)
        Game(g.getString("id"), g.getString("title"), g.getString("emoji"), g.getString("blurb"))
    }
}

/** Quick links shown on the car's library screen. */
val BOOKMARKS = listOf(
    "Wikipedia" to "https://en.m.wikipedia.org",
    "Weather" to "https://wttr.in",
    "Hacker News" to "https://news.ycombinator.com",
)
