package app.murmur.sms

import android.app.Activity
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.IBinder
import android.provider.Telephony
import android.telephony.SmsMessage
import app.murmur.MurmurApp
import app.murmur.data.SmsRepository
import app.murmur.notify.Notifier
import kotlinx.coroutines.launch

/** An SMS has arrived. As the default app it's our job to store it and tell the user. */
class SmsReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Telephony.Sms.Intents.SMS_DELIVER_ACTION) return
        val parts: Array<SmsMessage> = Telephony.Sms.Intents.getMessagesFromIntent(intent) ?: return
        if (parts.isEmpty()) return
        val address = parts[0].displayOriginatingAddress ?: parts[0].originatingAddress ?: return
        val body = parts.joinToString("") { it.displayMessageBody ?: it.messageBody ?: "" }
        val subId = intent.getIntExtra("subscription", -1)
        val pending = goAsync()
        MurmurApp.scope.launch {
            try {
                val repo = SmsRepository(context)
                val uri = repo.insertInbox(address, body, parts[0].timestampMillis, subId)
                val threadId = uri?.let(repo::threadIdOf) ?: repo.threadIdFor(address)
                Notifier.incoming(context, threadId)
            } finally {
                pending.finish()
            }
        }
    }
}

/**
 * MMS download needs the carrier's MMSC settings and a PDU parser, which Murmur doesn't
 * ship yet. This receiver exists so Android will let Murmur hold the SMS role; it tells
 * the user a picture message arrived so it isn't silently lost.
 */
class MmsReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Telephony.Sms.Intents.WAP_PUSH_DELIVER_ACTION) return
        Notifier.mmsArrived(context)
    }
}

/** Sent / delivered reports from the radio. */
class SendStatusReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val uri = intent.data ?: return
        val repo = SmsRepository(context)
        when (intent.action) {
            SENT -> {
                val ok = resultCode == Activity.RESULT_OK
                val last = intent.getIntExtra("part", 0) == intent.getIntExtra("parts", 1) - 1
                if (!ok) {
                    repo.setType(uri, Telephony.Sms.MESSAGE_TYPE_FAILED)
                    repo.threadIdOf(uri)?.let { Notifier.failed(context, it) }
                } else if (last && repo.typeOf(uri) != Telephony.Sms.MESSAGE_TYPE_FAILED) {
                    // A long text goes in parts; one failed part means the whole text failed.
                    repo.setType(uri, Telephony.Sms.MESSAGE_TYPE_SENT)
                }
            }
            DELIVERED -> {
                val status = runCatching {
                    SmsMessage.createFromPdu(intent.getByteArrayExtra("pdu"), intent.getStringExtra("format"))?.status
                }.getOrNull()
                // Per 3GPP TS 23.040: 0x00-0x1F delivered, 0x40+ permanent failure.
                val stored = when {
                    status == null -> Telephony.Sms.STATUS_COMPLETE
                    status < 0x20 -> Telephony.Sms.STATUS_COMPLETE
                    status >= 0x40 -> Telephony.Sms.STATUS_FAILED
                    else -> Telephony.Sms.STATUS_PENDING
                }
                repo.setStatus(uri, stored)
            }
        }
    }

    companion object {
        const val SENT = "app.murmur.SENT"
        const val DELIVERED = "app.murmur.DELIVERED"
    }
}

class ScheduledSendReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val id = intent.getLongExtra("id", -1)
        if (id >= 0) Outbox.fire(context, id)
    }
}

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED &&
            intent.action != "android.app.action.SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED"
        ) return
        Outbox.rearmAll(context)
        Notifier.rearmReminders(context)
    }
}

/** "Respond via message" when declining a call. Sends straight away, no undo window. */
class HeadlessSmsSendService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val number = intent?.data?.schemeSpecificPart?.substringBefore('?')
        val text = intent?.getStringExtra(Intent.EXTRA_TEXT)
        if (!number.isNullOrBlank() && !text.isNullOrBlank()) {
            val threadId = SmsRepository(this).threadIdFor(number)
            SmsSender.send(this, threadId, number, text)
        }
        stopSelf(startId)
        return START_NOT_STICKY
    }
}
