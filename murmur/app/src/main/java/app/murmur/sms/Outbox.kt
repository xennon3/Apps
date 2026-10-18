package app.murmur.sms

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import app.murmur.MurmurApp
import app.murmur.data.Pending
import app.murmur.data.PendingStore
import app.murmur.data.Prefs
import app.murmur.mms.Mms
import java.io.File
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * The one way out for every message. Each one waits in [PendingStore] first:
 * a few seconds for "undo", or until its scheduled time. An alarm guarantees it goes
 * even if Murmur is closed; a coroutine makes the short undo wait exact while it's open.
 */
object Outbox {

    fun send(context: Context, threadId: Long, address: String, body: String, attachment: String? = null, subId: Int = -1): Pending {
        val wait = Prefs(context).undoSeconds * 1000L
        return enqueue(context, threadId, address, body, System.currentTimeMillis() + wait, false, attachment, subId)
    }

    /** For notification replies, where there's nowhere to show an undo button. */
    fun sendImmediately(context: Context, threadId: Long, address: String, body: String, attachment: String? = null, subId: Int = -1): Pending =
        enqueue(context, threadId, address, body, System.currentTimeMillis(), false, attachment, subId)

    fun schedule(context: Context, threadId: Long, address: String, body: String, at: Long, attachment: String? = null, subId: Int = -1): Pending =
        enqueue(context, threadId, address, body, at, true, attachment, subId)

    private fun enqueue(
        context: Context, threadId: Long, address: String, body: String, at: Long, scheduled: Boolean,
        attachment: String?, subId: Int,
    ): Pending {
        val p = PendingStore.get(context).add(threadId, address, body, at, scheduled, attachment, subId)
        if (!scheduled && at - System.currentTimeMillis() <= 0) {
            fire(context, p.id)
        } else {
            arm(context, p)
            if (!scheduled) {
                MurmurApp.scope.launch {
                    delay(at - System.currentTimeMillis())
                    fire(context, p.id)
                }
            }
        }
        return p
    }

    /** Pull a message back. Returns its text so the composer can restore it, or null if it already left. */
    fun undo(context: Context, id: Long): String? {
        val p = PendingStore.get(context).take(id) ?: return null
        disarm(context, id)
        return p.body
    }

    /** Like [undo], but also hands back the picture so the composer can show it again. */
    fun undoWithAttachment(context: Context, id: Long): Pending? {
        val p = PendingStore.get(context).take(id) ?: return null
        disarm(context, id)
        return p
    }

    /** Skip the wait and send right now. */
    fun sendNow(context: Context, id: Long) {
        disarm(context, id)
        fire(context, id)
    }

    fun reschedule(context: Context, id: Long, at: Long) {
        val store = PendingStore.get(context)
        store.reschedule(id, at)
        store.all.value.firstOrNull { it.id == id }?.let { arm(context, it) }
    }

    /**
     * Called by the alarm, the undo timer or "send now". Whoever takes the row sends it.
     * Work happens off the main thread; [done] runs afterwards (a receiver's goAsync finish).
     */
    fun fire(context: Context, id: Long, done: (() -> Unit)? = null) {
        MurmurApp.scope.launch {
            try {
                val p = PendingStore.get(context).take(id) ?: return@launch
                disarm(context, id)
                val recipients = p.address.split(' ', ';').filter { it.isNotBlank() }
                val group = recipients.size > 1 && Prefs(context).groupMms
                if (p.attachment != null || group) {
                    Mms.send(context, p.threadId, recipients, p.body, p.attachment?.let(::File), p.subId)
                } else {
                    SmsSender.send(context, p.threadId, p.address, p.body, p.subId)
                }
            } finally {
                done?.invoke()
            }
        }
    }

    /** Re-arm everything after a reboot, and send anything that came due while the phone was off. */
    fun rearmAll(context: Context) {
        val now = System.currentTimeMillis()
        for (p in PendingStore.get(context).all.value) {
            if (p.at <= now) fire(context, p.id) else arm(context, p)
        }
    }

    private fun alarmIntent(context: Context, id: Long): PendingIntent =
        PendingIntent.getBroadcast(
            context, id.toInt(),
            Intent(context, ScheduledSendReceiver::class.java).putExtra("id", id),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

    private fun arm(context: Context, p: Pending) {
        val am = context.getSystemService(AlarmManager::class.java)
        val pi = alarmIntent(context, p.id)
        val exact = Build.VERSION.SDK_INT < Build.VERSION_CODES.S || am.canScheduleExactAlarms()
        if (exact) am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, p.at, pi)
        else am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, p.at, pi)
    }

    private fun disarm(context: Context, id: Long) {
        context.getSystemService(AlarmManager::class.java).cancel(alarmIntent(context, id))
    }
}
