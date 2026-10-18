package app.murmur.notify

import android.Manifest
import android.annotation.SuppressLint
import android.app.Notification
import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.BitmapFactory
import android.os.Build
import android.provider.Telephony
import android.widget.Toast
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.Person
import androidx.core.app.RemoteInput
import androidx.core.content.ContextCompat
import androidx.core.graphics.drawable.IconCompat
import app.murmur.MainActivity
import app.murmur.R
import app.murmur.data.Phone
import app.murmur.data.SmsRepository
import app.murmur.data.Vibes
import app.murmur.sms.Outbox

object Notifier {
    const val MESSAGES = "messages"
    const val CODES = "codes"
    const val NUDGES = "nudges"
    const val PROBLEMS = "problems"
    const val KEY_REPLY = "reply"

    fun createChannels(context: Context) {
        val nm = context.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannels(
            listOf(
                NotificationChannel(MESSAGES, "Messages", NotificationManager.IMPORTANCE_HIGH),
                NotificationChannel(CODES, "One-time codes", NotificationManager.IMPORTANCE_HIGH).apply {
                    description = "Login and verification codes, with a one-tap Copy button"
                },
                NotificationChannel(NUDGES, "Reply reminders", NotificationManager.IMPORTANCE_DEFAULT).apply {
                    description = "When you asked Murmur to remind you to reply"
                },
                NotificationChannel(PROBLEMS, "Send problems", NotificationManager.IMPORTANCE_HIGH),
            ),
        )
    }

    private fun canPost(context: Context) =
        Build.VERSION.SDK_INT < 33 ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED

    /** Every post goes through here; callers have already checked [canPost]. */
    @SuppressLint("MissingPermission")
    private fun post(context: Context, id: Int, n: Notification) {
        if (!canPost(context)) return
        try {
            NotificationManagerCompat.from(context).notify(id, n)
        } catch (_: SecurityException) {
        }
    }

    private fun openThread(context: Context, threadId: Long): PendingIntent =
        PendingIntent.getActivity(
            context, threadId.toInt(),
            Intent(context, MainActivity::class.java)
                .setAction(MainActivity.OPEN_THREAD)
                .putExtra("thread", threadId)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

    private fun action(context: Context, act: String, threadId: Long, extra: String? = null, mutable: Boolean = false) =
        PendingIntent.getBroadcast(
            context, (act.hashCode() * 31 + threadId.toInt()),
            Intent(context, NotificationActionReceiver::class.java).setAction(act)
                .putExtra("thread", threadId).putExtra("extra", extra),
            PendingIntent.FLAG_UPDATE_CURRENT or
                (if (mutable) PendingIntent.FLAG_MUTABLE else PendingIntent.FLAG_IMMUTABLE),
        )

    private fun person(context: Context, repo: SmsRepository, address: String): Person {
        val c = repo.contact(address)
        val b = Person.Builder().setName(c.name ?: Phone.pretty(address)).setKey(address)
        c.photo?.let { uri ->
            runCatching {
                context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it) }
            }.getOrNull()?.let { b.setIcon(IconCompat.createWithAdaptiveBitmap(it)) }
        }
        return b.build()
    }

    /** Post (or refresh) the notification for a thread with all its unread messages. */
    fun incoming(context: Context, threadId: Long) {
        if (!canPost(context)) return
        val repo = SmsRepository(context)
        val unread = ArrayList<Triple<String, String, Long>>()
        context.contentResolver.query(
            Telephony.Sms.CONTENT_URI, arrayOf("address", "body", "date"),
            "thread_id = ? AND read = 0 AND type = 1", arrayOf(threadId.toString()), "date DESC LIMIT 8",
        )?.use { c -> while (c.moveToNext()) unread += Triple(c.getString(0).orEmpty(), c.getString(1).orEmpty(), c.getLong(2)) }
        if (unread.isEmpty()) return
        unread.reverse()

        val me = Person.Builder().setName("You").build()
        val style = NotificationCompat.MessagingStyle(me)
        val people = HashMap<String, Person>()
        for ((addr, body, date) in unread) {
            style.addMessage(body, date, people.getOrPut(addr) { person(context, repo, addr) })
        }
        if (people.size > 1) {
            style.conversationTitle = people.values.joinToString(", ") { it.name.toString() }
            style.isGroupConversation = true
        }

        val latest = unread.last().second
        val code = Vibes.code(latest)
        val reply = NotificationCompat.Action.Builder(
            0, "Reply", action(context, NotificationActionReceiver.REPLY, threadId, mutable = true),
        ).addRemoteInput(RemoteInput.Builder(KEY_REPLY).setLabel("Message").build())
            .setSemanticAction(NotificationCompat.Action.SEMANTIC_ACTION_REPLY)
            .setAllowGeneratedReplies(true)
            .build()

        val n = NotificationCompat.Builder(context, if (code != null) CODES else MESSAGES)
            .setSmallIcon(R.drawable.ic_stat_murmur)
            .setColor(0xFF7C4DFF.toInt())
            .setStyle(style)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setContentIntent(openThread(context, threadId))
            .setAutoCancel(true)
            .apply {
                if (code != null) addAction(0, "Copy $code", action(context, NotificationActionReceiver.COPY, threadId, code))
                addAction(reply)
                addAction(0, "Read", action(context, NotificationActionReceiver.MARK_READ, threadId))
                if (code == null) addAction(0, "Remind me in 1h", action(context, NotificationActionReceiver.REMIND, threadId, "60"))
            }
            .build()
        post(context, threadId.toInt(), n)
    }

    fun cancel(context: Context, threadId: Long) =
        NotificationManagerCompat.from(context).cancel(threadId.toInt())

    fun failed(context: Context, threadId: Long) {
        if (!canPost(context)) return
        val title = SmsRepository(context).conversation(threadId)?.title ?: "your message"
        val n = NotificationCompat.Builder(context, PROBLEMS)
            .setSmallIcon(R.drawable.ic_stat_murmur)
            .setContentTitle("Couldn't send to $title")
            .setContentText("Tap to open the conversation and try again.")
            .setContentIntent(openThread(context, threadId))
            .setAutoCancel(true)
            .build()
        post(context, -threadId.toInt(), n)
    }

    fun mmsArrived(context: Context) {
        if (!canPost(context)) return
        val n = NotificationCompat.Builder(context, MESSAGES)
            .setSmallIcon(R.drawable.ic_stat_murmur)
            .setContentTitle("A picture or group message arrived")
            .setContentText("Murmur can't download MMS yet. Open your phone's original Messages app to see it.")
            .setStyle(NotificationCompat.BigTextStyle().bigText(
                "Murmur can't download MMS yet. Open your phone's original Messages app to see it, " +
                    "or switch the default back in Murmur's settings.",
            ))
            .setAutoCancel(true)
            .build()
        post(context, 7_000_001, n)
    }

    // ---------- "remind me to reply" ----------

    private fun reminders(context: Context) = context.getSharedPreferences("reminders", Context.MODE_PRIVATE)

    fun remindAt(context: Context, threadId: Long, at: Long) {
        reminders(context).edit().putLong(threadId.toString(), at).apply()
        arm(context, threadId, at)
    }

    fun reminderFor(context: Context, threadId: Long): Long? =
        reminders(context).getLong(threadId.toString(), 0L).takeIf { it > System.currentTimeMillis() }

    fun clearReminder(context: Context, threadId: Long) {
        reminders(context).edit().remove(threadId.toString()).apply()
        context.getSystemService(AlarmManager::class.java).cancel(reminderIntent(context, threadId))
    }

    fun rearmReminders(context: Context) {
        for ((k, v) in reminders(context).all) {
            val t = k.toLongOrNull() ?: continue
            arm(context, t, (v as? Long ?: continue).coerceAtLeast(System.currentTimeMillis() + 5_000))
        }
    }

    private fun reminderIntent(context: Context, threadId: Long) =
        action(context, NotificationActionReceiver.REMIND_FIRE, threadId)

    private fun arm(context: Context, threadId: Long, at: Long) {
        context.getSystemService(AlarmManager::class.java)
            .setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, reminderIntent(context, threadId))
    }

    fun nudge(context: Context, threadId: Long) {
        reminders(context).edit().remove(threadId.toString()).apply()
        if (!canPost(context)) return
        val convo = SmsRepository(context).conversation(threadId) ?: return
        val n = NotificationCompat.Builder(context, NUDGES)
            .setSmallIcon(R.drawable.ic_stat_murmur)
            .setColor(0xFFFF8A65.toInt())
            .setContentTitle("You meant to reply to ${convo.title}")
            .setContentText(convo.snippet)
            .setContentIntent(openThread(context, threadId))
            .setAutoCancel(true)
            .build()
        post(context, threadId.toInt(), n)
    }
}

class NotificationActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val threadId = intent.getLongExtra("thread", -1)
        if (threadId < 0) return
        val repo = SmsRepository(context)
        when (intent.action) {
            REPLY -> {
                val text = RemoteInput.getResultsFromIntent(intent)?.getCharSequence(Notifier.KEY_REPLY)?.toString()
                if (!text.isNullOrBlank()) {
                    val addresses = repo.conversation(threadId)?.addresses.orEmpty()
                    if (addresses.isNotEmpty()) Outbox.sendImmediately(context, threadId, addresses.joinToString(" "), text)
                }
                repo.markRead(threadId)
                Notifier.cancel(context, threadId)
            }
            MARK_READ -> {
                repo.markRead(threadId)
                Notifier.cancel(context, threadId)
            }
            COPY -> {
                val code = intent.getStringExtra("extra") ?: return
                context.getSystemService(ClipboardManager::class.java)
                    .setPrimaryClip(ClipData.newPlainText("Code", code))
                if (Build.VERSION.SDK_INT < 33) Toast.makeText(context, "Copied $code", Toast.LENGTH_SHORT).show()
                repo.markRead(threadId)
                Notifier.cancel(context, threadId)
            }
            REMIND -> {
                val minutes = intent.getStringExtra("extra")?.toLongOrNull() ?: 60
                Notifier.remindAt(context, threadId, System.currentTimeMillis() + minutes * 60_000)
                Notifier.cancel(context, threadId)
            }
            REMIND_FIRE -> Notifier.nudge(context, threadId)
        }
    }

    companion object {
        const val REPLY = "app.murmur.REPLY"
        const val MARK_READ = "app.murmur.MARK_READ"
        const val COPY = "app.murmur.COPY"
        const val REMIND = "app.murmur.REMIND"
        const val REMIND_FIRE = "app.murmur.REMIND_FIRE"
    }
}
