package app.murmur.data

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.provider.BlockedNumberContract
import android.provider.ContactsContract
import android.provider.Telephony
import java.util.concurrent.ConcurrentHashMap

/** Reads and writes the phone's shared SMS/MMS store (the Telephony provider). */
class SmsRepository(private val context: Context) {

    private val resolver get() = context.contentResolver
    private val contactCache = ConcurrentHashMap<String, Contact>()
    private val prefs = Prefs(context)

    // ---------- conversations ----------

    fun conversations(): List<Conversation> {
        val pinned = prefs.pinned()
        val canonical = canonicalAddresses()
        val out = ArrayList<Conversation>()
        val uri = Uri.parse("content://mms-sms/conversations?simple=true")
        val cols = arrayOf("_id", "date", "snippet", "read", "recipient_ids")
        try {
            resolver.query(uri, cols, null, null, "date DESC")?.use { c ->
                while (c.moveToNext()) {
                    val threadId = c.getLong(0)
                    val addresses = c.getString(4).orEmpty().split(' ')
                        .mapNotNull { it.toLongOrNull()?.let(canonical::get) }
                    if (addresses.isEmpty()) continue
                    out += conversation(
                        threadId, addresses, c.getString(2).orEmpty(), c.getLong(1),
                        unread = c.getInt(3) == 0, pinned = threadId in pinned,
                    )
                }
            }
        } catch (e: Exception) {
            // Some vendor builds reject the threads table; rebuild it from the SMS table.
            return conversationsFromSms(pinned)
        }
        return sort(out)
    }

    private fun conversationsFromSms(pinned: Set<Long>): List<Conversation> {
        val seen = HashMap<Long, Conversation>()
        val unread = HashSet<Long>()
        resolver.query(
            Telephony.Sms.CONTENT_URI,
            arrayOf("thread_id", "address", "body", "date", "read"),
            null, null, "date DESC",
        )?.use { c ->
            while (c.moveToNext()) {
                val t = c.getLong(0)
                if (c.getInt(4) == 0) unread += t
                if (t in seen) continue
                seen[t] = conversation(
                    t, listOf(c.getString(1).orEmpty()), c.getString(2).orEmpty(), c.getLong(3),
                    unread = false, pinned = t in pinned,
                )
            }
        }
        return sort(seen.values.map { it.copy(unread = it.threadId in unread) })
    }

    private fun sort(list: List<Conversation>) =
        list.sortedWith(compareByDescending<Conversation> { it.pinned }.thenByDescending { it.date })

    private fun conversation(
        threadId: Long, addresses: List<String>, snippet: String, date: Long,
        unread: Boolean, pinned: Boolean,
    ): Conversation {
        val contacts = addresses.map { contact(it) }
        val title = addresses.indices.joinToString(", ") { i ->
            contacts[i].name ?: Phone.pretty(addresses[i])
        }
        return Conversation(
            threadId = threadId,
            addresses = addresses,
            title = title,
            photo = if (addresses.size == 1) contacts[0].photo else null,
            snippet = snippet.ifBlank { "📷 Picture" },
            date = date,
            unread = unread,
            known = contacts.any { it.name != null },
            pinned = pinned,
        )
    }

    private fun canonicalAddresses(): Map<Long, String> {
        val map = HashMap<Long, String>()
        try {
            resolver.query(
                Uri.parse("content://mms-sms/canonical-addresses"),
                arrayOf("_id", "address"), null, null, null,
            )?.use { c -> while (c.moveToNext()) map[c.getLong(0)] = c.getString(1).orEmpty() }
        } catch (_: Exception) {
        }
        return map
    }

    /** Recipients of a thread straight from the threads table, for threads with no messages yet. */
    fun recipientsOf(threadId: Long): List<String>? = runCatching {
        val canonical = canonicalAddresses()
        resolver.query(
            Uri.parse("content://mms-sms/conversations?simple=true"), arrayOf("_id", "recipient_ids"),
            "_id = ?", arrayOf(threadId.toString()), null,
        )?.use { c ->
            if (!c.moveToFirst()) null
            else c.getString(1).orEmpty().split(' ').mapNotNull { it.toLongOrNull()?.let(canonical::get) }.ifEmpty { null }
        }
    }.getOrNull()

    fun conversation(threadId: Long): Conversation? =
        conversations().firstOrNull { it.threadId == threadId }

    fun threadIdFor(address: String): Long =
        Telephony.Threads.getOrCreateThreadId(context, address)

    fun threadIdFor(addresses: Set<String>): Long =
        Telephony.Threads.getOrCreateThreadId(context, addresses)

    // ---------- messages ----------

    fun messages(threadId: Long, limit: Int = 600): List<Message> {
        val out = ArrayList<Message>()
        resolver.query(
            Telephony.Sms.CONTENT_URI,
            arrayOf("_id", "address", "body", "date", "type", "status", "sub_id"),
            "thread_id = ?", arrayOf(threadId.toString()), "date DESC LIMIT $limit",
        )?.use { c ->
            while (c.moveToNext()) {
                val type = c.getInt(4)
                val status = c.getInt(5)
                val inbound = type == Telephony.Sms.MESSAGE_TYPE_INBOX
                out += Message(
                    key = "s" + c.getLong(0),
                    id = c.getLong(0),
                    box = if (inbound) Box.IN else Box.OUT,
                    address = c.getString(1).orEmpty(),
                    body = c.getString(2).orEmpty(),
                    date = c.getLong(3),
                    delivery = when {
                        inbound -> Delivery.NONE
                        type == Telephony.Sms.MESSAGE_TYPE_FAILED -> Delivery.FAILED
                        type == Telephony.Sms.MESSAGE_TYPE_OUTBOX ||
                            type == Telephony.Sms.MESSAGE_TYPE_QUEUED -> Delivery.SENDING
                        status == Telephony.Sms.STATUS_COMPLETE -> Delivery.DELIVERED
                        status == Telephony.Sms.STATUS_FAILED -> Delivery.FAILED
                        else -> Delivery.SENT
                    },
                    subId = c.getInt(6),
                )
            }
        }
        out += mms(threadId, limit)
        return out.sortedBy { it.date }.takeLast(limit)
    }

    private fun mms(threadId: Long, limit: Int): List<Message> {
        val out = ArrayList<Message>()
        try {
            resolver.query(
                Telephony.Mms.CONTENT_URI,
                arrayOf("_id", "date", "msg_box", "sub_id"),
                "thread_id = ?", arrayOf(threadId.toString()), "date DESC LIMIT $limit",
            )?.use { c ->
                while (c.moveToNext()) {
                    val id = c.getLong(0)
                    val box = c.getInt(2)
                    val inbound = box == Telephony.Mms.MESSAGE_BOX_INBOX
                    val text = StringBuilder()
                    val images = ArrayList<Uri>()
                    var audio: Uri? = null
                    resolver.query(
                        Uri.parse("content://mms/part"),
                        arrayOf("_id", "ct", "text"), "mid = ?", arrayOf(id.toString()), null,
                    )?.use { p ->
                        while (p.moveToNext()) {
                            val ct = p.getString(1).orEmpty()
                            when {
                                ct == "text/plain" -> {
                                    if (text.isNotEmpty()) text.append('\n')
                                    text.append(p.getString(2).orEmpty())
                                }
                                ct.startsWith("image/") ->
                                    images += Uri.parse("content://mms/part/" + p.getLong(0))
                                ct.startsWith("audio/") || ct == "application/ogg" ->
                                    audio = Uri.parse("content://mms/part/" + p.getLong(0))
                            }
                        }
                    }
                    out += Message(
                        key = "m$id", id = id,
                        box = if (inbound) Box.IN else Box.OUT,
                        address = if (inbound) mmsSender(id) else "",
                        body = text.toString(),
                        date = c.getLong(1) * 1000, // MMS dates are in seconds
                        delivery = when {
                            inbound -> Delivery.NONE
                            box == Telephony.Mms.MESSAGE_BOX_FAILED -> Delivery.FAILED
                            box == Telephony.Mms.MESSAGE_BOX_OUTBOX -> Delivery.SENDING
                            else -> Delivery.SENT
                        },
                        images = images,
                        subId = c.getInt(3),
                        audio = audio,
                    )
                }
            }
        } catch (_: Exception) {
        }
        return out
    }

    private fun mmsSender(id: Long): String {
        resolver.query(
            Uri.parse("content://mms/$id/addr"), arrayOf("address"),
            "type = 137", null, null, // 137 = PduHeaders.FROM
        )?.use { c -> if (c.moveToFirst()) return c.getString(0).orEmpty() }
        return ""
    }

    /** Unread incoming texts and picture messages in a thread, oldest first, for the notification. */
    fun unreadIncoming(threadId: Long, limit: Int = 8): List<Triple<String, String, Long>> {
        val out = ArrayList<Triple<String, String, Long>>()
        runCatching {
            resolver.query(
                Telephony.Sms.CONTENT_URI, arrayOf("address", "body", "date"),
                "thread_id = ? AND read = 0 AND type = 1", arrayOf(threadId.toString()), "date DESC LIMIT $limit",
            )?.use { c -> while (c.moveToNext()) out += Triple(c.getString(0).orEmpty(), c.getString(1).orEmpty(), c.getLong(2)) }
        }
        runCatching {
            resolver.query(
                Telephony.Mms.CONTENT_URI, arrayOf("_id", "date"),
                "thread_id = ? AND read = 0 AND msg_box = 1", arrayOf(threadId.toString()), "date DESC LIMIT $limit",
            )?.use { c ->
                while (c.moveToNext()) {
                    val id = c.getLong(0)
                    val (text, pictures, voice) = mmsSummary(id)
                    val body = listOfNotNull(
                        when {
                            voice -> "🎙 Voice note"
                            pictures == 1 -> "📷 Photo"
                            pictures > 1 -> "📷 $pictures photos"
                            else -> null
                        },
                        text.ifBlank { null },
                    ).joinToString("  ").ifBlank { "Picture message" }
                    out += Triple(mmsSender(id), body, c.getLong(1) * 1000)
                }
            }
        }
        return out.sortedBy { it.third }.takeLast(limit)
    }

    private fun mmsSummary(id: Long): Triple<String, Int, Boolean> {
        val text = StringBuilder()
        var pictures = 0
        var voice = false
        resolver.query(
            Uri.parse("content://mms/part"), arrayOf("ct", "text"), "mid = ?", arrayOf(id.toString()), null,
        )?.use { p ->
            while (p.moveToNext()) {
                val ct = p.getString(0).orEmpty()
                if (ct == "text/plain") text.append(p.getString(1).orEmpty())
                if (ct.startsWith("image/")) pictures++
                if (ct.startsWith("audio/")) voice = true
            }
        }
        return Triple(text.toString(), pictures, voice)
    }

    data class Hit(val id: Long, val threadId: Long, val address: String, val body: String, val date: Long)

    fun searchMessages(query: String, limit: Int = 40): List<Hit> {
        val out = ArrayList<Hit>()
        val like = "%" + query.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%"
        runCatching {
            resolver.query(
                Telephony.Sms.CONTENT_URI, arrayOf("_id", "thread_id", "address", "body", "date"),
                "body LIKE ? ESCAPE '\\'", arrayOf(like), "date DESC LIMIT $limit",
            )?.use { c ->
                while (c.moveToNext()) out += Hit(c.getLong(0), c.getLong(1), c.getString(2).orEmpty(), c.getString(3).orEmpty(), c.getLong(4))
            }
        }
        return out
    }

    fun markRead(threadId: Long) {
        val values = ContentValues().apply { put("read", 1); put("seen", 1) }
        val where = "thread_id = ? AND read = 0"
        val args = arrayOf(threadId.toString())
        runCatching { resolver.update(Telephony.Sms.CONTENT_URI, values, where, args) }
        runCatching { resolver.update(Telephony.Mms.CONTENT_URI, values, where, args) }
    }

    /** Adds a number to the phone's own block list (the default SMS app is allowed to). */
    fun block(address: String): Boolean = runCatching {
        resolver.insert(
            BlockedNumberContract.BlockedNumbers.CONTENT_URI,
            ContentValues().apply { put(BlockedNumberContract.BlockedNumbers.COLUMN_ORIGINAL_NUMBER, address) },
        ) != null
    }.getOrDefault(false)

    fun deleteThread(threadId: Long) {
        runCatching {
            resolver.delete(Uri.parse("content://mms-sms/conversations/$threadId"), null, null)
        }
        prefs.setPinned(threadId, false)
        prefs.setDraft(threadId, "")
    }

    fun deleteMessage(m: Message) {
        val base = if (m.key.startsWith("m")) Telephony.Mms.CONTENT_URI else Telephony.Sms.CONTENT_URI
        runCatching { resolver.delete(Uri.withAppendedPath(base, m.id.toString()), null, null) }
    }

    // ---------- writing (only allowed for the default SMS app) ----------

    fun insertInbox(address: String, body: String, sentAt: Long, subId: Int): Uri? {
        val values = ContentValues().apply {
            put("address", address)
            put("body", body)
            put("date", System.currentTimeMillis())
            put("date_sent", sentAt)
            put("read", 0)
            put("seen", 0)
            put("sub_id", subId)
        }
        return runCatching { resolver.insert(Telephony.Sms.Inbox.CONTENT_URI, values) }.getOrNull()
    }

    fun insertOutbox(address: String, body: String, threadId: Long, subId: Int = -1): Uri? {
        val values = ContentValues().apply {
            if (subId >= 0) put("sub_id", subId)
            put("address", address)
            put("body", body)
            put("date", System.currentTimeMillis())
            put("read", 1)
            put("seen", 1)
            put("thread_id", threadId)
            put("type", Telephony.Sms.MESSAGE_TYPE_OUTBOX)
        }
        return runCatching { resolver.insert(Telephony.Sms.CONTENT_URI, values) }.getOrNull()
    }

    fun setType(uri: Uri, type: Int) {
        runCatching { resolver.update(uri, ContentValues().apply { put("type", type) }, null, null) }
    }

    fun setStatus(uri: Uri, status: Int) {
        runCatching { resolver.update(uri, ContentValues().apply { put("status", status) }, null, null) }
    }

    fun typeOf(uri: Uri): Int? =
        runCatching {
            resolver.query(uri, arrayOf("type"), null, null, null)?.use { c ->
                if (c.moveToFirst()) c.getInt(0) else null
            }
        }.getOrNull()

    fun threadIdOf(uri: Uri): Long? =
        runCatching {
            resolver.query(uri, arrayOf("thread_id"), null, null, null)?.use { c ->
                if (c.moveToFirst()) c.getLong(0) else null
            }
        }.getOrNull()

    // ---------- contacts ----------

    fun contact(address: String): Contact {
        if (address.isBlank()) return Contact(null, null)
        contactCache[address]?.let { return it }
        val found = runCatching {
            resolver.query(
                Uri.withAppendedPath(ContactsContract.PhoneLookup.CONTENT_FILTER_URI, Uri.encode(address)),
                arrayOf(ContactsContract.PhoneLookup.DISPLAY_NAME, ContactsContract.PhoneLookup.PHOTO_THUMBNAIL_URI),
                null, null, null,
            )?.use { c ->
                if (c.moveToFirst()) Contact(c.getString(0), c.getString(1)?.let(Uri::parse)) else null
            }
        }.getOrNull() ?: Contact(null, null)
        contactCache[address] = found
        return found
    }

    data class PhoneEntry(val name: String, val number: String, val label: String, val photo: Uri?)

    fun searchContacts(query: String): List<PhoneEntry> {
        val uri = if (query.isBlank()) ContactsContract.CommonDataKinds.Phone.CONTENT_URI
        else Uri.withAppendedPath(ContactsContract.CommonDataKinds.Phone.CONTENT_FILTER_URI, Uri.encode(query))
        val out = ArrayList<PhoneEntry>()
        val seen = HashSet<String>()
        runCatching {
            resolver.query(
                uri,
                arrayOf(
                    ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
                    ContactsContract.CommonDataKinds.Phone.NUMBER,
                    ContactsContract.CommonDataKinds.Phone.TYPE,
                    ContactsContract.CommonDataKinds.Phone.LABEL,
                    ContactsContract.CommonDataKinds.Phone.PHOTO_THUMBNAIL_URI,
                ),
                null, null, ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME + " ASC",
            )?.use { c ->
                while (c.moveToNext() && out.size < 80) {
                    val number = c.getString(1) ?: continue
                    if (!seen.add(c.getString(0) + Phone.digits(number))) continue
                    out += PhoneEntry(
                        name = c.getString(0) ?: number,
                        number = number,
                        label = ContactsContract.CommonDataKinds.Phone.getTypeLabel(
                            context.resources, c.getInt(2), c.getString(3),
                        ).toString(),
                        photo = c.getString(4)?.let(Uri::parse),
                    )
                }
            }
        }
        return out
    }

    fun clearContactCache() = contactCache.clear()
}

object Phone {
    fun digits(s: String) = s.filter { it.isDigit() || it == '+' }

    /** Light formatting for display; never used for sending. */
    fun pretty(raw: String): String {
        val d = digits(raw)
        return when {
            d.length == 10 && !d.startsWith("+") -> "(${d.take(3)}) ${d.substring(3, 6)}-${d.takeLast(4)}"
            d.length == 12 && d.startsWith("+1") -> "(${d.substring(2, 5)}) ${d.substring(5, 8)}-${d.takeLast(4)}"
            d.isEmpty() -> raw
            else -> raw
        }
    }
}
