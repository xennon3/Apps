package com.xennon3.dashplay.car

import androidx.car.app.CarContext
import androidx.car.app.Screen
import androidx.car.app.model.Action
import androidx.car.app.model.ActionStrip
import androidx.car.app.model.Template
import androidx.car.app.navigation.model.NavigationTemplate
import com.xennon3.dashplay.CarBridge
import com.xennon3.dashplay.R

/** The full-screen web page, with a few buttons floating over it. */
class PlayerScreen(carContext: CarContext, private val surface: WebSurface) : Screen(carContext) {

    override fun onGetTemplate(): Template {
        val actions = ActionStrip.Builder()
            .addAction(button(R.drawable.ic_library) { screenManager.push(LibraryScreen(carContext)) })
            .addAction(button(R.drawable.ic_search) { screenManager.push(AddressScreen(carContext)) })
            .addAction(button(R.drawable.ic_back) { surface.back() })
            .addAction(button(R.drawable.ic_home) { CarBridge.load(CarBridge.HOME_URL) })
            .build()
        // Pan mode makes the host forward drag gestures, which scroll the page.
        val mapActions = ActionStrip.Builder().addAction(Action.PAN).build()
        return NavigationTemplate.Builder()
            .setActionStrip(actions)
            .setMapActionStrip(mapActions)
            .build()
    }

    private fun button(icon: Int, onClick: () -> Unit) =
        Action.Builder()
            .setIcon(carContext.icon(icon))
            .setOnClickListener { onClick() }
            .build()
}
