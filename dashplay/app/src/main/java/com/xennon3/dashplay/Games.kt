package com.xennon3.dashplay

/** Games bundled in assets/games. Each one is a single web page. */
data class Game(val title: String, val blurb: String, val file: String) {
    val url get() = "file:///android_asset/games/$file"
}

val GAMES = listOf(
    Game("Bubble Pop", "Tap bubbles before they float away", "bubble-pop.html"),
    Game("Turn Snake", "Tap left or right to steer", "turn-snake.html"),
    Game("Memory Lane", "Find the matching road signs", "memory-lane.html"),
)

/** Quick links shown on the car's library screen. */
val BOOKMARKS = listOf(
    "Wikipedia" to "https://en.m.wikipedia.org",
    "Weather" to "https://wttr.in",
    "Hacker News" to "https://news.ycombinator.com",
)
