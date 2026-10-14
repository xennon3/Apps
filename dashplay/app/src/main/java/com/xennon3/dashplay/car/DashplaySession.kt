package com.xennon3.dashplay.car

import android.content.Intent
import android.graphics.Rect
import androidx.car.app.AppManager
import androidx.car.app.CarContext
import androidx.car.app.Screen
import androidx.car.app.ScreenManager
import androidx.car.app.Session
import androidx.car.app.SurfaceCallback
import androidx.car.app.SurfaceContainer
import androidx.car.app.hardware.CarHardwareManager
import androidx.car.app.hardware.common.CarValue
import androidx.car.app.hardware.common.OnCarDataAvailableListener
import androidx.car.app.hardware.info.CarSensors
import androidx.car.app.hardware.info.Compass
import androidx.car.app.hardware.info.Speed
import androidx.core.content.ContextCompat
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import com.xennon3.dashplay.CarBridge
import com.xennon3.dashplay.GameKey
import com.xennon3.dashplay.Store

class DashplaySession : Session(), CarBridge.CarScreen, WebSurface.Listener {

    private lateinit var surface: WebSurface
    private var speedListener: OnCarDataAvailableListener<Speed>? = null
    private var compassListener: OnCarDataAvailableListener<Compass>? = null

    override fun onCreateScreen(intent: Intent): Screen {
        surface = WebSurface(carContext, this)
        surface.load(CarBridge.currentUrl)
        CarBridge.init(carContext)
        CarBridge.onDriveStarted()
        carContext.getCarService(AppManager::class.java).setSurfaceCallback(surfaceCallback)
        watchSpeed()
        watchCompass()
        CarBridge.car = this
        Store.observe(onStoreChanged)

        lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onDestroy(owner: LifecycleOwner) {
                if (CarBridge.car === this@DashplaySession) CarBridge.car = null
                CarBridge.moving = false
                stopWatchingSpeed()
                stopWatchingCompass()
                Store.stopObserving(onStoreChanged)
                surface.release()
            }
        })
        return PlayerScreen(carContext)
    }

    override fun load(url: String) = surface.load(url)
    override fun press(key: GameKey) = surface.press(key)
    override fun type(text: String) = surface.type(text)
    override fun nav(action: String) = surface.nav(action)
    override fun onDrivingLockChanged() = applyLock()
    override fun onScreenScaleChanged() = surface.rescale()

    override fun onPageChanged(url: String, title: String) = CarBridge.setPage(url, title)

    /** The home screen lists installed tools, so show a new install or removal right away. */
    private val onStoreChanged: () -> Unit = {
        if (CarBridge.currentUrl.startsWith(CarBridge.HOME_URL)) surface.nav("reload")
    }

    override fun onAddressRequested() {
        val screens = carContext.getCarService(ScreenManager::class.java)
        if (screens.top !is AddressScreen) screens.push(AddressScreen(carContext))
    }

    private fun applyLock() {
        surface.locked = CarBridge.moving && CarBridge.drivingLock
    }

    private val surfaceCallback = object : SurfaceCallback {
        override fun onSurfaceAvailable(container: SurfaceContainer) = surface.attach(container)
        override fun onSurfaceDestroyed(container: SurfaceContainer) = surface.detach()
        override fun onVisibleAreaChanged(visibleArea: Rect) = surface.setVisibleArea(visibleArea)
        override fun onClick(x: Float, y: Float) = surface.tap(x, y)
        override fun onScroll(distanceX: Float, distanceY: Float) = surface.scroll(distanceX, distanceY)
        override fun onScale(focusX: Float, focusY: Float, scaleFactor: Float) = surface.zoom(scaleFactor)
    }

    /**
     * Pauses the page whenever the car reports it is moving. If the car never reports a
     * speed (the desktop head unit, some older cars) the app stays unlocked.
     */
    private fun watchSpeed() {
        val listener = OnCarDataAvailableListener<Speed> { speed ->
            val value = speed.displaySpeedMetersPerSecond
            val metersPerSecond = value.value
            if (value.status != CarValue.STATUS_SUCCESS || metersPerSecond == null) return@OnCarDataAvailableListener
            // Two thresholds so crawling in traffic doesn't flicker the lock on and off.
            CarBridge.moving = when {
                metersPerSecond > MOVING_MPS -> true
                metersPerSecond < PARKED_MPS -> false
                else -> CarBridge.moving
            }
            applyLock()
        }
        try {
            carContext.getCarService(CarHardwareManager::class.java).carInfo
                .addSpeedListener(ContextCompat.getMainExecutor(carContext), listener)
            speedListener = listener
        } catch (e: Exception) {
            // No hardware access on this host; the lock simply never engages.
        }
    }

    /** Feeds the car's compass to the Compass tool (and any Dashplay page that listens). */
    private fun watchCompass() {
        val listener = OnCarDataAvailableListener<Compass> { compass ->
            val value = compass.orientations
            val azimuth = value.value?.firstOrNull()
            if (value.status == CarValue.STATUS_SUCCESS && azimuth != null) surface.heading(azimuth)
        }
        try {
            carContext.getCarService(CarHardwareManager::class.java).carSensors
                .addCompassListener(CarSensors.UPDATE_RATE_UI, ContextCompat.getMainExecutor(carContext), listener)
            compassListener = listener
        } catch (e: Exception) {
            // No compass on this car; the Compass tool falls back to the phone's sensors.
        }
    }

    private fun stopWatchingCompass() {
        val listener = compassListener ?: return
        try {
            carContext.getCarService(CarHardwareManager::class.java).carSensors.removeCompassListener(listener)
        } catch (e: Exception) {
        }
        compassListener = null
    }

    private fun stopWatchingSpeed() {
        val listener = speedListener ?: return
        try {
            carContext.getCarService(CarHardwareManager::class.java).carInfo.removeSpeedListener(listener)
        } catch (e: Exception) {
        }
        speedListener = null
    }

    private companion object {
        const val MOVING_MPS = 2.0f // about 7 km/h
        const val PARKED_MPS = 0.5f
    }
}

internal fun CarContext.icon(res: Int) =
    androidx.car.app.model.CarIcon.Builder(
        androidx.core.graphics.drawable.IconCompat.createWithResource(this, res)
    ).build()
