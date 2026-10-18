package app.murmur.sms

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.Telephony
import android.telephony.SmsManager
import app.murmur.data.SmsRepository

/** Hands a message to the radio and records it in the shared SMS store. */
object SmsSender {

    fun send(context: Context, threadId: Long, address: String, body: String) {
        val repo = SmsRepository(context)
        // Group threads keep their recipients space-separated; text each one individually.
        for (to in address.split(' ', ';').filter { it.isNotBlank() }) {
            val uri = repo.insertOutbox(to, body, threadId)
            val sms = smsManager(context)
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

    @Suppress("DEPRECATION")
    private fun smsManager(context: Context): SmsManager =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) context.getSystemService(SmsManager::class.java)
        else SmsManager.getDefault()
}
