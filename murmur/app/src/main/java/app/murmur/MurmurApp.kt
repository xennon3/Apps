package app.murmur

import android.app.Application
import app.murmur.notify.Notifier
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

class MurmurApp : Application() {
    override fun onCreate() {
        super.onCreate()
        Notifier.createChannels(this)
    }

    companion object {
        /** Work that must outlive a screen, like a message counting down its undo window. */
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    }
}
