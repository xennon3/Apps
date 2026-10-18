package app.murmur.mms

import android.app.Activity
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.net.Uri
import android.os.Build
import android.provider.Telephony
import android.telephony.SmsManager
import android.telephony.SubscriptionManager
import androidx.core.content.FileProvider
import androidx.exifinterface.media.ExifInterface
import app.murmur.MurmurApp
import app.murmur.data.PendingStore
import app.murmur.data.Sims
import app.murmur.data.SmsRepository
import app.murmur.notify.Notifier
import app.murmur.voice.Voice
import kotlinx.coroutines.launch
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.UUID

/** Picture and group messages: sending, downloading and storing them. */
object Mms {

    private const val AUTHORITY = "app.murmur.mms"
    private val SYSTEM_MMS_PACKAGES = listOf("com.android.phone", "com.android.mms.service")

    fun smsManager(context: Context, subId: Int): SmsManager {
        val sub = if (subId >= 0) subId else Sims.defaultSms()
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val base = context.getSystemService(SmsManager::class.java)
            if (sub >= 0) base.createForSubscriptionId(sub) else base
        } else {
            @Suppress("DEPRECATION")
            if (sub >= 0) SmsManager.getSmsManagerForSubscriptionId(sub) else SmsManager.getDefault()
        }
    }

    /** The largest MMS this carrier accepts, with room for headers. */
    fun maxBytes(context: Context, subId: Int): Int {
        val carrier = runCatching {
            smsManager(context, subId).carrierConfigValues.getInt(SmsManager.MMS_CONFIG_MAX_MESSAGE_SIZE, 0)
        }.getOrDefault(0)
        val limit = if (carrier > 0) carrier else 300 * 1024
        return (limit - 12 * 1024).coerceIn(40 * 1024, 1024 * 1024)
    }

    private fun pduDir(context: Context) = File(context.cacheDir, "mms").apply { mkdirs() }

    private fun shareable(context: Context, file: File, write: Boolean): Uri {
        val uri = FileProvider.getUriForFile(context, AUTHORITY, file)
        val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or (if (write) Intent.FLAG_GRANT_WRITE_URI_PERMISSION else 0)
        for (pkg in SYSTEM_MMS_PACKAGES) runCatching { context.grantUriPermission(pkg, uri, flags) }
        return uri
    }

    // ================================================================ pictures

    /**
     * Copies a picked picture into Murmur's storage, turned upright and shrunk to fit an MMS.
     * Returns null if it can't be read.
     */
    fun preparePicture(context: Context, source: Uri, subId: Int): File? = runCatching {
        val budget = maxBytes(context, subId)
        val resolver = context.contentResolver
        val type = resolver.getType(source).orEmpty()
        val name = UUID.randomUUID().toString()

        // Small GIFs go as they are so they keep moving; anything else becomes a JPEG.
        if (type == "image/gif") {
            val bytes = resolver.openInputStream(source)!!.use { it.readBytes() }
            if (bytes.size <= budget) return@runCatching File(PendingStore.outgoingDir(context), "$name.gif").apply { writeBytes(bytes) }
        }
        val out = File(PendingStore.outgoingDir(context), "$name.jpg")

        val rotation = resolver.openInputStream(source)?.use { ExifInterface(it).rotationDegrees } ?: 0
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        resolver.openInputStream(source)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        var bmp: Bitmap? = null
        var maxSide = 1600
        var bytes: ByteArray
        while (true) {
            var sample = 1
            while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= maxSide) sample *= 2
            val decoded = resolver.openInputStream(source)!!.use {
                BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample })
            } ?: return@runCatching null
            val scale = maxSide.toFloat() / maxOf(decoded.width, decoded.height)
            val m = Matrix().apply {
                if (scale < 1f) postScale(scale, scale)
                if (rotation != 0) postRotate(rotation.toFloat())
            }
            bmp?.recycle()
            bmp = Bitmap.createBitmap(decoded, 0, 0, decoded.width, decoded.height, m, true)
            if (bmp != decoded) decoded.recycle()

            var quality = 85
            do {
                bytes = ByteArrayOutputStream().also { bmp.compress(Bitmap.CompressFormat.JPEG, quality, it) }.toByteArray()
                quality -= 10
            } while (bytes.size > budget && quality >= 35)
            if (bytes.size <= budget || maxSide <= 320) break
            maxSide = (maxSide * 0.7f).toInt()
        }
        bmp.recycle()
        out.writeBytes(bytes)
        out
    }.getOrNull()

    // ================================================================ sending

    fun send(context: Context, threadId: Long, recipients: List<String>, body: String, attachment: File?, subId: Int) {
        val parts = ArrayList<Pdu.Part>()
        val voice = Voice.isVoice(attachment?.path)
        val mediaName = attachment?.let {
            when {
                voice -> "voice.amr"
                it.name.endsWith(".gif") -> "image.gif"
                else -> "image.jpg"
            }
        }
        val textName = if (body.isNotBlank()) "text.txt" else null
        val smil = if (voice) Pdu.smil(null, textName, audioName = mediaName) else Pdu.smil(mediaName, textName)
        parts += Pdu.Part("application/smil", smil.toByteArray(), contentId = "<smil>", contentLocation = "smil.xml", charset = Pdu.UTF8)
        if (attachment != null && attachment.exists()) {
            val type = when (mediaName) { "voice.amr" -> Voice.MIME; "image.gif" -> "image/gif"; else -> "image/jpeg" }
            parts += Pdu.Part(type, attachment.readBytes(), name = mediaName, contentId = if (voice) "<audio>" else "<image>", contentLocation = mediaName)
        }
        if (textName != null) parts += Pdu.Part("text/plain", body.toByteArray(), contentId = "<text>", contentLocation = textName, charset = Pdu.UTF8)

        val transactionId = "T" + System.currentTimeMillis().toString(16) + (0..999).random()
        val msgUri = Store.insert(context, threadId, recipients, null, parts, outgoing = true, transactionId = transactionId, subId = subId)
        val pduFile = File(pduDir(context), "send-${UUID.randomUUID()}.pdu")
        pduFile.writeBytes(Pdu.sendReq(transactionId, recipients, parts))

        val sent = PendingIntent.getBroadcast(
            context, pduFile.name.hashCode(),
            Intent(context, MmsSentReceiver::class.java).setData(msgUri)
                .putExtra("pdu", pduFile.path).putExtra("attachment", attachment?.path).putExtra("thread", threadId),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE, // mutable: the system adds the carrier's reply
        )
        try {
            smsManager(context, subId).sendMultimediaMessage(context, shareable(context, pduFile, write = false), null, null, sent)
        } catch (e: Exception) {
            msgUri?.let { Store.setBox(context, it, Telephony.Mms.MESSAGE_BOX_FAILED) }
            pduFile.delete()
            Notifier.failed(context, threadId)
        }
    }

    /** Copy a picture out of the shared store so a failed message can be sent again. */
    fun copyToOutgoing(context: Context, part: Uri): File? = runCatching {
        val out = File(PendingStore.outgoingDir(context), UUID.randomUUID().toString() + ".jpg")
        context.contentResolver.openInputStream(part)!!.use { input -> out.outputStream().use { input.copyTo(it) } }
        out
    }.getOrNull()

    // ================================================================ receiving

    fun download(context: Context, location: String, subId: Int, from: String?) {
        val file = File(pduDir(context), "dl-${UUID.randomUUID()}.pdu")
        file.createNewFile()
        val done = PendingIntent.getBroadcast(
            context, file.name.hashCode(),
            Intent(context, MmsDownloadedReceiver::class.java)
                .putExtra("file", file.path).putExtra("location", location)
                .putExtra("sub", subId).putExtra("from", from),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE,
        )
        try {
            smsManager(context, subId).downloadMultimediaMessage(context, location, shareable(context, file, write = true), null, done)
        } catch (e: Exception) {
            file.delete()
            Notifier.mmsFailed(context, location, subId, from)
        }
    }

    /** Who a received message is between: everyone on it except this phone. */
    fun conversationMembers(context: Context, m: Pdu.Message): List<String> {
        val own = Sims.ownNumbers(context)
        val everyone = buildList {
            m.from?.let(::add)
            addAll(m.to); addAll(m.cc)
        }.filter { it.isNotBlank() && !it.equals("insert-address-token", true) }

        var others = everyone.filter { Sims.key(it) !in own }
        // Don't know our own number? On a one-to-one picture message the single "to" is us.
        if (own.isEmpty() && m.to.size == 1 && m.cc.isEmpty()) others = listOfNotNull(m.from)
        return others.distinctBy { Sims.key(it).ifEmpty { it } }.ifEmpty { listOfNotNull(m.from) }
    }

    // ================================================================ the shared MMS store

    object Store {
        fun insert(
            context: Context, threadId: Long, to: List<String>, from: String?, parts: List<Pdu.Part>,
            outgoing: Boolean, transactionId: String? = null, subId: Int = -1,
            messageId: String? = null, subject: String? = null, sentAt: Long = 0, location: String? = null,
            cc: List<String> = emptyList(),
        ): Uri? = runCatching {
            val resolver = context.contentResolver
            val now = System.currentTimeMillis() / 1000
            val values = ContentValues().apply {
                put("thread_id", threadId)
                put("date", now)
                put("date_sent", if (sentAt > 0) sentAt else now)
                put("msg_box", if (outgoing) Telephony.Mms.MESSAGE_BOX_OUTBOX else Telephony.Mms.MESSAGE_BOX_INBOX)
                put("read", if (outgoing) 1 else 0)
                put("seen", if (outgoing) 1 else 0)
                put("m_type", if (outgoing) Pdu.SEND_REQ else Pdu.RETRIEVE_CONF)
                put("v", 0x12)
                put("ct_t", "application/vnd.wap.multipart.related")
                put("m_cls", "personal")
                put("pri", 0x81)
                transactionId?.let { put("tr_id", it) }
                messageId?.let { put("m_id", it) }
                location?.let { put("ct_l", it) }
                if (!subject.isNullOrBlank()) { put("sub", subject); put("sub_cs", Pdu.UTF8) }
                if (subId >= 0) put("sub_id", subId)
                put("text_only", if (parts.none { it.contentType.startsWith("image/") || it.contentType.startsWith("audio/") }) 1 else 0)
                put("m_size", parts.sumOf { it.data.size })
            }
            val msg = resolver.insert(Telephony.Mms.CONTENT_URI, values) ?: return@runCatching null
            val id = msg.lastPathSegment ?: return@runCatching null

            parts.forEachIndexed { seq, p ->
                val pv = ContentValues().apply {
                    put("mid", id.toLong())
                    put("seq", if (p.contentType == "application/smil") -1 else seq)
                    put("ct", p.contentType)
                    p.contentId?.let { put("cid", it) }
                    p.contentLocation?.let { put("cl", it) }
                    p.name?.let { put("name", it) }
                    if (p.charset != 0) put("chset", p.charset)
                    if (p.contentType.startsWith("text/") || p.contentType == "application/smil") {
                        put("text", String(p.data, Pdu.charsetOf(p.charset)))
                    }
                }
                val partUri = resolver.insert(Uri.parse("content://mms/$id/part"), pv)
                if (partUri != null && !p.contentType.startsWith("text/") && p.contentType != "application/smil") {
                    // Binary parts (pictures, audio) are written to the file the store just made for them.
                    resolver.openOutputStream(partUri)?.use { it.write(p.data) }
                }
            }

            fun addr(a: String, type: Int) = resolver.insert(
                Uri.parse("content://mms/$id/addr"),
                ContentValues().apply { put("address", a); put("type", type); put("charset", Pdu.UTF8) },
            )
            addr(from ?: "insert-address-token", 137) // FROM
            to.forEach { addr(it, 151) }               // TO
            cc.forEach { addr(it, 130) }               // CC
            msg
        }.getOrNull()

        fun setBox(context: Context, uri: Uri, box: Int, messageId: String? = null) {
            runCatching {
                context.contentResolver.update(uri, ContentValues().apply {
                    put("msg_box", box)
                    messageId?.let { put("m_id", it) }
                }, null, null)
            }
        }

        fun alreadyHave(context: Context, location: String): Boolean = runCatching {
            context.contentResolver.query(Telephony.Mms.CONTENT_URI, arrayOf("_id"), "ct_l = ?", arrayOf(location), null)
                ?.use { it.count > 0 } ?: false
        }.getOrDefault(false)
    }
}

/** WAP push: the carrier says a picture or group message is waiting. Fetch it. */
class MmsReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Telephony.Sms.Intents.WAP_PUSH_DELIVER_ACTION) return
        val data = intent.getByteArrayExtra("data") ?: return
        val subId = intent.getIntExtra(SubscriptionManager.EXTRA_SUBSCRIPTION_INDEX, intent.getIntExtra("subscription", -1))
        val n = Pdu.parse(data) ?: return
        if (n.type != Pdu.NOTIFICATION_IND) return
        val location = n.contentLocation ?: return
        val pending = goAsync()
        MurmurApp.scope.launch {
            try {
                if (!Mms.Store.alreadyHave(context, location)) Mms.download(context, location, subId, n.from)
            } finally {
                pending.finish()
            }
        }
    }
}

class MmsDownloadedReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val file = File(intent.getStringExtra("file") ?: return)
        val location = intent.getStringExtra("location").orEmpty()
        val subId = intent.getIntExtra("sub", -1)
        val from = intent.getStringExtra("from")
        val ok = resultCode == Activity.RESULT_OK
        val pending = goAsync()
        MurmurApp.scope.launch {
            try {
                val m = if (ok && file.exists()) Pdu.parse(file.readBytes()) else null
                if (m == null || m.parts.isEmpty()) {
                    Notifier.mmsFailed(context, location, subId, from)
                    return@launch
                }
                val repo = SmsRepository(context)
                val members = Mms.conversationMembers(context, m)
                val threadId = if (members.size == 1) repo.threadIdFor(members[0]) else repo.threadIdFor(members.toSet())
                Mms.Store.insert(
                    context, threadId, to = m.to, from = m.from, parts = m.parts, outgoing = false,
                    transactionId = m.transactionId, subId = subId, messageId = m.messageId,
                    subject = m.subject, sentAt = m.date, location = location, cc = m.cc,
                )
                Notifier.clearMmsFailure(context, location)
                Notifier.incoming(context, threadId)
            } finally {
                file.delete()
                pending.finish()
            }
        }
    }
}

class MmsSentReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val msgUri = intent.data
        val conf = intent.getByteArrayExtra(SmsManager.EXTRA_MMS_DATA)?.let(Pdu::parse)
        // 0x80 = Ok. Some carriers send no reply body at all; trust the result code then.
        val ok = resultCode == Activity.RESULT_OK && (conf == null || conf.responseStatus == 0 || conf.responseStatus == 0x80)
        if (msgUri != null) {
            Mms.Store.setBox(
                context, msgUri,
                if (ok) Telephony.Mms.MESSAGE_BOX_SENT else Telephony.Mms.MESSAGE_BOX_FAILED,
                conf?.messageId,
            )
        }
        if (!ok) Notifier.failed(context, intent.getLongExtra("thread", -1))
        // The picture is in the shared store now (retry reads it from there), so drop our copy.
        intent.getStringExtra("pdu")?.let { File(it).delete() }
        intent.getStringExtra("attachment")?.let { File(it).delete() }
    }
}
