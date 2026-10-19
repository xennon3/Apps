package app.murmur.brain

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationManager
import android.os.Build
import android.provider.CalendarContract
import android.provider.CallLog
import android.provider.ContactsContract
import android.text.format.DateFormat
import android.text.format.DateUtils
import androidx.core.content.ContextCompat
import app.murmur.data.Phone
import app.murmur.data.SmsRepository
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import kotlin.coroutines.resume

/**
 * What Murmur Brain may look at besides texts. Each source is off until the user turns it on
 * in Settings, and also needs Android's permission. Nothing here leaves the phone; it's only
 * read into the question the on-device model answers.
 */
object BrainAccess {
    enum class Source(val label: String, val detail: String, val permissions: List<String>) {
        CALENDAR("Calendar", "Answer \"am I free Friday night?\" from your calendar", listOf(Manifest.permission.READ_CALENDAR)),
        CONTACTS("Contact details", "Phone numbers, emails and birthdays of the people you ask about", listOf(Manifest.permission.READ_CONTACTS)),
        CALLS("Call history", "Answer \"did Mom call today?\" from your recent calls", listOf(Manifest.permission.READ_CALL_LOG)),
        LOCATION("Location", "Your location and speed (GPS), and sending where you are when you say \"text Jake where I am\"", listOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)),
        PHONE("Phone status", "Battery, storage, network, sensors and device info, for \"how's my battery?\" or \"am I on Wi-Fi?\"", emptyList()),
        MOTION("Steps", "Today's steps from the phone's step counter", if (Build.VERSION.SDK_INT >= 29) listOf(Manifest.permission.ACTIVITY_RECOGNITION) else emptyList()),
        SCREEN_TIME("Screen time", "Which apps you used today and for how long. Android asks you to allow Usage access for Murmur", emptyList()),
        CONTROLS("Phone controls", "Flashlight, timers, alarms, ringer, volume, and opening apps or settings when you ask", emptyList()),
    }

    private fun prefs(c: Context) = c.getSharedPreferences("brain_access", Context.MODE_PRIVATE)

    fun wanted(c: Context, s: Source) = prefs(c).getBoolean(s.name, false)

    fun set(c: Context, s: Source, on: Boolean) = prefs(c).edit().putBoolean(s.name, on).apply()

    fun granted(c: Context, s: Source) =
        if (s == Source.SCREEN_TIME) PhoneSensors.usageAccess(c)
        else s.permissions.take(1).all { ContextCompat.checkSelfPermission(c, it) == PackageManager.PERMISSION_GRANTED }

    /** On in Settings and allowed by Android. */
    fun on(c: Context, s: Source) = wanted(c, s) && granted(c, s)
}

/** Things the user told Murmur Brain to remember, and how they'd like it to talk. */
object BrainMemory {
    private fun prefs(c: Context) = c.getSharedPreferences("brain_memory", Context.MODE_PRIVATE)

    fun facts(c: Context): List<String> = runCatching {
        val a = JSONArray(prefs(c).getString("facts", "[]"))
        List(a.length()) { a.getString(it) }
    }.getOrDefault(emptyList())

    private fun save(c: Context, list: List<String>) =
        prefs(c).edit().putString("facts", JSONArray(list.takeLast(40)).toString()).apply()

    fun add(c: Context, fact: String) {
        val now = facts(c).filterNot { it.equals(fact, ignoreCase = true) }
        save(c, now + fact)
    }

    fun remove(c: Context, fact: String) = save(c, facts(c) - fact)

    /** Removes every fact that contains all the given words; returns what was forgotten. */
    fun forget(c: Context, words: String): List<String> {
        val ws = words.lowercase().split(Regex("\\W+")).filter { it.length >= 3 && it !in setOf("that", "about", "the") }
        if (ws.isEmpty()) return emptyList()
        val gone = facts(c).filter { f -> ws.all { f.lowercase().contains(it) } }
        save(c, facts(c) - gone.toSet())
        return gone
    }

    /** How the user would like it to talk ("short and funny", "always in Spanish"). */
    fun persona(c: Context): String = prefs(c).getString("persona", "")!!

    fun setPersona(c: Context, v: String) = prefs(c).edit().putString("persona", v.trim()).apply()
}

/** Reads the phone data the user allowed, formatted for the model. */
object PhoneData {

    private fun allowed(c: Context, p: String) = ContextCompat.checkSelfPermission(c, p) == PackageManager.PERMISSION_GRANTED

    /** Calendar entries from now through the next [days] days. */
    fun calendar(c: Context, days: Int = 8): String {
        if (!BrainAccess.on(c, BrainAccess.Source.CALENDAR)) return ""
        val now = System.currentTimeMillis()
        val uri = CalendarContract.Instances.CONTENT_URI.buildUpon().also {
            android.content.ContentUris.appendId(it, now - 3 * 3_600_000L)
            android.content.ContentUris.appendId(it, now + days * 86_400_000L)
        }.build()
        val lines = ArrayList<String>()
        runCatching {
            c.contentResolver.query(
                uri,
                arrayOf(CalendarContract.Instances.TITLE, CalendarContract.Instances.BEGIN, CalendarContract.Instances.END,
                    CalendarContract.Instances.EVENT_LOCATION, CalendarContract.Instances.ALL_DAY),
                null, null, CalendarContract.Instances.BEGIN + " ASC",
            )?.use { cur ->
                while (cur.moveToNext() && lines.size < 25) {
                    val title = cur.getString(0) ?: "(no title)"
                    val begin = cur.getLong(1); val end = cur.getLong(2)
                    val where = cur.getString(3)?.takeIf { it.isNotBlank() }?.let { " at $it" } ?: ""
                    val whenText = if (cur.getInt(4) == 1) DateFormat.format("EEE MMM d", begin).toString() + ", all day"
                    else DateFormat.format("EEE MMM d, h:mm a", begin).toString() + " to " + DateFormat.format("h:mm a", end)
                    lines += "- $whenText: $title$where"
                }
            }
        }
        val today = DateFormat.format("EEEE MMM d, h:mm a", now)
        return "It is now $today. The user's calendar for the next $days days:\n" +
            (if (lines.isEmpty()) "- Nothing scheduled." else lines.joinToString("\n"))
    }

    /** Recent calls, written as plain sentences (small models misread terse lists). */
    @SuppressLint("MissingPermission")
    fun calls(c: Context, limit: Int = 15): String {
        if (!BrainAccess.on(c, BrainAccess.Source.CALLS) || !allowed(c, Manifest.permission.READ_CALL_LOG)) return ""
        val repo = SmsRepository(c)
        val lines = ArrayList<String>()
        runCatching {
            c.contentResolver.query(
                CallLog.Calls.CONTENT_URI,
                arrayOf(CallLog.Calls.NUMBER, CallLog.Calls.TYPE, CallLog.Calls.DATE, CallLog.Calls.DURATION),
                null, null, CallLog.Calls.DATE + " DESC LIMIT $limit",
            )?.use { cur ->
                while (cur.moveToNext()) {
                    val number = cur.getString(0).orEmpty()
                    val who = repo.contact(number).name ?: Phone.pretty(number)
                    val at = cur.getLong(2)
                    val day = when {
                        DateUtils.isToday(at) -> "Today"
                        DateUtils.isToday(at + 86_400_000L) -> "Yesterday"
                        else -> DateFormat.format("EEE MMM d", at).toString()
                    } + " at " + DateFormat.format("h:mm a", at)
                    val mins = (cur.getLong(3) + 59) / 60
                    lines += "- " + when (cur.getInt(1)) {
                        CallLog.Calls.INCOMING_TYPE -> "$day, $who called the user. They talked for $mins min."
                        CallLog.Calls.OUTGOING_TYPE -> if (mins > 0) "$day, the user called $who. They talked for $mins min." else "$day, the user called $who. No answer."
                        CallLog.Calls.MISSED_TYPE -> "$day, $who called the user. The user missed it."
                        CallLog.Calls.REJECTED_TYPE -> "$day, $who called the user. The user declined it."
                        else -> "$day, a call with $who."
                    }
                }
            }
        }
        return if (lines.isEmpty()) "" else "The user's recent phone calls, newest first:\n" + lines.joinToString("\n")
    }

    /** Numbers, emails and birthdays of contacts named in the question. */
    fun contacts(c: Context, question: String, names: Collection<String>): String {
        if (!BrainAccess.on(c, BrainAccess.Source.CONTACTS)) return ""
        val q = question.lowercase()
        val wanted = names.filter { n -> n.lowercase().split(' ').any { it.length >= 3 && q.contains(it) } }.take(3)
        if (wanted.isEmpty()) return ""
        val out = ArrayList<String>()
        for (name in wanted) {
            val entry = SmsRepository(c).searchContacts(name).firstOrNull() ?: continue
            val details = ArrayList<String>()
            details += "phone ${entry.number}"
            runCatching {
                c.contentResolver.query(
                    ContactsContract.Data.CONTENT_URI,
                    arrayOf(ContactsContract.Data.MIMETYPE, ContactsContract.Data.DATA1, ContactsContract.Data.DATA2),
                    ContactsContract.Data.DISPLAY_NAME + " = ?", arrayOf(entry.name), null,
                )?.use { cur ->
                    while (cur.moveToNext()) {
                        when (cur.getString(0)) {
                            ContactsContract.CommonDataKinds.Email.CONTENT_ITEM_TYPE -> details += "email ${cur.getString(1)}"
                            ContactsContract.CommonDataKinds.Event.CONTENT_ITEM_TYPE ->
                                if (cur.getInt(2) == ContactsContract.CommonDataKinds.Event.TYPE_BIRTHDAY) details += "birthday ${cur.getString(1)}"
                        }
                    }
                }
            }
            out += "- ${entry.name}: " + details.distinct().joinToString(", ")
        }
        return if (out.isEmpty()) "" else "From the user's contacts:\n" + out.joinToString("\n")
    }

    /** Where the phone is, as a map link, or null if location is off or unavailable. */
    @SuppressLint("MissingPermission")
    suspend fun locationLink(c: Context): String? {
        if (!BrainAccess.on(c, BrainAccess.Source.LOCATION)) return null
        val lm = c.getSystemService(LocationManager::class.java)
        val provider = when {
            allowed(c, Manifest.permission.ACCESS_FINE_LOCATION) && lm.isProviderEnabled(LocationManager.GPS_PROVIDER) -> LocationManager.GPS_PROVIDER
            lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER) -> LocationManager.NETWORK_PROVIDER
            else -> return null
        }
        val fresh: Location? = if (Build.VERSION.SDK_INT >= 30) withTimeoutOrNull(12_000) {
            suspendCancellableCoroutine { cont ->
                runCatching {
                    lm.getCurrentLocation(provider, null, c.mainExecutor) { loc -> if (cont.isActive) cont.resume(loc) }
                }.onFailure { if (cont.isActive) cont.resume(null) }
            }
        } else null
        val loc = fresh ?: runCatching { lm.getLastKnownLocation(provider) }.getOrNull() ?: return null
        return "https://maps.google.com/?q=%.5f,%.5f".format(java.util.Locale.US, loc.latitude, loc.longitude)
    }
}
