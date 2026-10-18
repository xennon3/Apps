package app.murmur.sms

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.provider.Telephony
import app.murmur.data.SmsRepository
import app.murmur.mms.Mms

/** Hands a text to the radio and records it in the shared SMS store. */
object SmsSender {

    fun send(context: Context, threadId: Long, address: String, body: String, subId: Int = -1) {
        val repo = SmsRepository(context)
        // Several recipients without group chat: text each one individually.
        for (to in address.split(' ', ';').filter { it.isNotBlank() }) {
            val uri = repo.insertOutbox(to, body, threadId, subId)
            val sms = Mms.smsManager(context, subId)
            val parts = sms.divideMessage(body)
            fun intent(action: String, i: Int) = PendingIntent.getBroadcast(
                context, (uri?.lastPathSegment?.toIntOrNull() ?: 0) * 64 + i + if (action == SendStatusReceiver.SENT) 0 else 32,
                Intent(context, SendStatusReceiver::class.java).setAction(action).setData(uri)
                    .putExtra("part", i).putExtra("parts", parts.size),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
            val sent = ArrayList(parts.indices.map { intent(SendStatusReceiver.SENT, it) })
            val delivered = ArrayList(parts.indices.map { intent(SendStatusReceiver.DELIVERED, it) })
            try {
                sms.sendMultipartTextMessage(to, null, parts, sent, delivered)
            } catch (e: Exception) {
                uri?.let { repo.setType(it, Telephony.Sms.MESSAGE_TYPE_FAILED) }
            }
        }
    }
}
