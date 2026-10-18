package app.murmur.data

import android.content.Context

/** Small per-device settings: pins, drafts and the user's toggles. */
class Prefs(context: Context) {
    private val sp = context.getSharedPreferences("murmur", Context.MODE_PRIVATE)

    fun pinned(): Set<Long> =
        sp.getStringSet("pinned", emptySet())!!.mapNotNull { it.toLongOrNull() }.toSet()

    fun setPinned(threadId: Long, on: Boolean) {
        val now = pinned().map { it.toString() }.toMutableSet()
        if (on) now += threadId.toString() else now -= threadId.toString()
        sp.edit().putStringSet("pinned", now).apply()
    }

    fun draft(threadId: Long): String = sp.getString("draft_$threadId", "")!!

    fun setDraft(threadId: Long, text: String) {
        sp.edit().apply { if (text.isBlank()) remove("draft_$threadId") else putString("draft_$threadId", text) }.apply()
    }

    /** The SIM chosen for a conversation on a dual-SIM phone, or null to follow the phone's default. */
    fun sim(threadId: Long): Int? = sp.getInt("sim_$threadId", -1).takeIf { it >= 0 }

    fun setSim(threadId: Long, subId: Int) = sp.edit().putInt("sim_$threadId", subId).apply()

    /** Send texts to several people as one group chat (MMS) instead of separate texts. */
    var groupMms: Boolean
        get() = sp.getBoolean("group_mms", true)
        set(v) = sp.edit().putBoolean("group_mms", v).apply()

    /** Seconds a sent message waits before it really leaves, so it can be pulled back. 0 = off. */
    var undoSeconds: Int
        get() = sp.getInt("undo_seconds", 5)
        set(v) = sp.edit().putInt("undo_seconds", v).apply()

    /** Offer to hold heated messages written late at night until the morning. */
    var sleepOnIt: Boolean
        get() = sp.getBoolean("sleep_on_it", true)
        set(v) = sp.edit().putBoolean("sleep_on_it", v).apply()

    /** Show the tone meter on the send button. */
    var heatMeter: Boolean
        get() = sp.getBoolean("heat_meter", true)
        set(v) = sp.edit().putBoolean("heat_meter", v).apply()
}
