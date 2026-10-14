package com.xennon3.dashplay.car

import androidx.car.app.CarContext
import androidx.car.app.Screen
import androidx.car.app.model.Action
import androidx.car.app.model.ItemList
import androidx.car.app.model.ListTemplate
import androidx.car.app.model.Row
import androidx.car.app.model.SectionedItemList
import androidx.car.app.model.Template
import com.xennon3.dashplay.CarBridge
import com.xennon3.dashplay.Store
import com.xennon3.dashplay.loadGames
import com.xennon3.dashplay.loadSites

/** Games and websites as a car-friendly list. Picking one returns to the page. */
class LibraryScreen(carContext: CarContext) : Screen(carContext) {

    override fun onGetTemplate(): Template {
        val games = ItemList.Builder()
        loadGames(carContext).forEach { games.addItem(row("${it.emoji}  ${it.title}", it.blurb, it.url)) }

        val tools = ItemList.Builder()
        Store.installed(carContext).forEach { tools.addItem(row("${it.emoji}  ${it.title}", it.blurb, it.url)) }
        tools.addItem(row("🛍️  Store", "Get more tools and games", CarBridge.STORE_URL))

        val web = ItemList.Builder()
        web.addItem(row("🌐  Start page", "Search, quick access and recent pages", CarBridge.START_URL))
        loadSites(carContext).forEach { web.addItem(row(it.name, it.url.removePrefix("https://"), it.url)) }

        return ListTemplate.Builder()
            .setTitle("Library")
            .setHeaderAction(Action.BACK)
            .addSectionedList(SectionedItemList.create(tools.build(), "Tools"))
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
