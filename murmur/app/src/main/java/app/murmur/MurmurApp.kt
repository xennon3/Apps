package app.murmur

import android.app.Application
import app.murmur.brain.Brain
import app.murmur.notify.Notifier
import android.util.Log
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

class MurmurApp : Application() {
    override fun onCreate() {
        super.onCreate()
        CrashReport.install(this)
        CrashReport.collect(this) // before Brain.refresh resets its record of the last session
        Notifier.createChannels(this)
        Brain.refresh(this)
        app.murmur.brain.BatteryLog.schedule(this)
        app.murmur.brain.Meaning.refresh(this)
    }

    companion object {
        /** Work that must outlive a screen, like a message counting down its undo window. */
        val scope = CoroutineScope(
            SupervisorJob() + Dispatchers.IO +
                // A failure in background work (a download check, a send) is logged, never a crash.
                CoroutineExceptionHandler { _, e -> Log.e("Murmur", "background task failed", e) },
        )
    }
}
