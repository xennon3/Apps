package com.xennon3.dashplay.car

import androidx.car.app.CarContext
import androidx.car.app.Screen
import androidx.car.app.model.Action
import androidx.car.app.model.ItemList
import androidx.car.app.model.Row
import androidx.car.app.model.SearchTemplate
import androidx.car.app.model.Template

/**
 * The car's own keyboard, for a text box the player tapped on a web page (YouTube search,
 * for example). What's typed goes into the page as it's typed; Search or Enter submits it.
 * Android Auto hides the keyboard while driving, whatever Dashplay's driving lock says.
 */
class TypeScreen(
    carContext: CarContext,
    private val label: String,
    initial: String,
    private val password: Boolean,
    private val fill: (text: String, submit: Boolean) -> Unit,
) : Screen(carContext) {

    private var text = initial

    override fun onGetTemplate(): Template {
        val rows = ItemList.Builder()
        if (text.isNotBlank()) {
            rows.addItem(
                Row.Builder()
                    .setTitle(if (password) "Enter password" else "Enter “$text”")
                    .addText(label.ifBlank { "Send to the page" })
                    .setOnClickListener { submit(text) }
                    .build()
            )
        }
        return SearchTemplate.Builder(object : SearchTemplate.SearchCallback {
            override fun onSearchTextChanged(searchText: String) {
                text = searchText
                fill(searchText, false)
                invalidate()
            }

            override fun onSearchSubmitted(searchText: String) = submit(searchText)
        })
            .setHeaderAction(Action.BACK)
            .setSearchHint(label.ifBlank { "Type here" })
            .setInitialSearchText(text)
            .setShowKeyboardByDefault(true)
            .setItemList(rows.build())
            .build()
    }

    private fun submit(value: String) {
        fill(value, true)
        screenManager.pop()
    }
}
