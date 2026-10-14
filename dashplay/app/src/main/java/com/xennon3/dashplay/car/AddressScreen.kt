package com.xennon3.dashplay.car

import androidx.car.app.CarContext
import androidx.car.app.Screen
import androidx.car.app.model.Action
import androidx.car.app.model.ItemList
import androidx.car.app.model.Row
import androidx.car.app.model.SearchTemplate
import androidx.car.app.model.Template
import com.xennon3.dashplay.CarBridge

/** Type an address or a search on the car's keyboard. Cars hide the keyboard while driving. */
class AddressScreen(carContext: CarContext) : Screen(carContext) {

    private var typed = ""

    override fun onGetTemplate(): Template {
        val suggestions = ItemList.Builder()
        if (typed.isNotBlank()) {
            val url = CarBridge.normalizeUrl(typed)
            suggestions.addItem(
                Row.Builder()
                    .setTitle(typed)
                    .addText(url)
                    .setOnClickListener { open(url) }
                    .build()
            )
        }
        return SearchTemplate.Builder(object : SearchTemplate.SearchCallback {
            override fun onSearchTextChanged(searchText: String) {
                typed = searchText
                invalidate()
            }

            override fun onSearchSubmitted(searchText: String) = open(CarBridge.normalizeUrl(searchText))
        })
            .setHeaderAction(Action.BACK)
            .setSearchHint("Web address or search")
            .setShowKeyboardByDefault(true)
            .setItemList(suggestions.build())
            .build()
    }

    private fun open(url: String) {
        CarBridge.load(url)
        screenManager.pop()
    }
}
