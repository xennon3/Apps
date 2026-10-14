package com.xennon3.dashplay

import android.content.Context
import org.json.JSONArray

/** A game bundled in assets/games. Each one is a single web page. */
data class Game(val id: String, val title: String, val emoji: String, val blurb: String) {
    val url get() = "file:///android_asset/games/$id.html"
}

/** A quick-access site from the browser start page. */
data class Site(val name: String, val url: String)

/** Reads the shared list in assets/kit/games.js, the same one the web pages use. */
fun loadGames(context: Context): List<Game> = readAssetList(context, "kit/games.js").map {
    Game(it.getString("id"), it.getString("title"), it.getString("emoji"), it.getString("blurb"))
}

/** Reads assets/kit/sites.js. */
fun loadSites(context: Context): List<Site> = readAssetList(context, "kit/sites.js").map {
    Site(it.getString("name"), it.getString("url"))
}

/** The kit files are `window.X = [ ...JSON... ];`, so everything after the "=" is JSON. */
private fun readAssetList(context: Context, path: String): List<org.json.JSONObject> {
    val source = context.assets.open(path).bufferedReader().use { it.readText() }
    val json = JSONArray(source.substring(source.indexOf('=') + 1).trim().removeSuffix(";"))
    return (0 until json.length()).map { json.getJSONObject(it) }
}
