package com.xennon3.dashplay.car

import androidx.car.app.CarContext
import androidx.car.app.Screen
import androidx.car.app.model.Action
import androidx.car.app.model.ItemList
import androidx.car.app.model.ListTemplate
import androidx.car.app.model.Row
import androidx.car.app.model.SectionedItemList
import androidx.car.app.model.Template
import com.xennon3.dashplay.BOOKMARKS
import com.xennon3.dashplay.CarBridge
import com.xennon3.dashplay.loadGames

/** Games and bookmarks as a car-friendly list. Picking one returns to the page. */
class LibraryScreen(carContext: CarContext) : Screen(carContext) {

    override fun onGetTemplate(): Template {
        val games = ItemList.Builder()
        loadGames(carContext).forEach { games.addItem(row("${it.emoji}  ${it.title}", it.blurb, it.url)) }

        val web = ItemList.Builder()
        BOOKMARKS.forEach { (title, url) -> web.addItem(row(title, url, url)) }

        return ListTemplate.Builder()
            .setTitle("Library")
            .setHeaderAction(Action.BACK)
            .addSectionedList(SectionedItemList.create(games.build(), "Games"))
            .addSectionedList(SectionedItemList.create(web.build(), "Web"))
            .build()
    }

    private fun row(title: String, subtitle: String, url: String) =
        Row.Builder()
            .setTitle(title)
            .addText(subtitle)
            .setOnClickListener {
                CarBridge.load(url)
                screenManager.pop()
            }
            .build()
}
