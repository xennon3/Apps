package app.murmur.data

import android.net.Uri

data class Contact(val name: String?, val photo: Uri?)

data class Conversation(
    val threadId: Long,
    val addresses: List<String>,
    val title: String,
    val photo: Uri?,
    val snippet: String,
    val date: Long,
    val unread: Boolean,
    val known: Boolean,
    val pinned: Boolean,
)

enum class Box { IN, OUT }

enum class Delivery { NONE, SENDING, SENT, DELIVERED, FAILED }

data class Message(
    val key: String,          // "s123" for SMS, "m45" for MMS
    val id: Long,
    val box: Box,
    val address: String,
    val body: String,
    val date: Long,
    val delivery: Delivery,
    val images: List<Uri> = emptyList(),
)

/** A message that has been written but not handed to the radio yet. */
data class Pending(
    val id: Long,
    val threadId: Long,
    val address: String,
    val body: String,
    /** When it goes out: the end of the undo window, or a scheduled time. */
    val at: Long,
    val scheduled: Boolean,
)
