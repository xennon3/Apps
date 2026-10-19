package app.murmur.brain.tools

import com.google.gson.JsonObject
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

/*
 * The phone as part of the Brain: one tool to read the phone's own sensors and status, one to
 * work its controls. The model only picks a topic or an action; Android code (PhoneSensors) does
 * the reading and returns a finished sentence. Plain Kotlin here so the lab can test tool choice.
 */

enum class PhoneTopic(val key: String, private val words: List<String>) {
    BATTERY("battery", listOf("batt", "charg", "power", "drain")),
    SPEED("speed", listOf("speed", "fast", "moving", "velocity", "mph", "km", "driving", "heading")),
    LOCATION("location", listOf("locat", "where", "address", "place", "gps", "coordinates")),
    STORAGE("storage", listOf("storage", "space", "disk", "memory full", "gb")),
    NETWORK("network", listOf("network", "wifi", "wi-fi", "signal", "internet", "data", "connect", "5g", "lte", "bars")),
    STEPS("steps", listOf("step", "walk", "distance walked")),
    SCREEN_TIME("screen_time", listOf("screen", "usage", "apps", "time on", "phone time")),
    SURROUNDINGS("surroundings", listOf("surround", "altitude", "elevation", "compass", "facing", "north", "direction", "light", "bright", "pressure")),
    DEVICE("device", listOf("device", "model", "android", "ram", "uptime", "ringer", "headphone", "volume", "dnd", "disturb"));

    companion object {
        /** Lenient: the model may say "battery level" or "how fast". */
        fun from(arg: String): PhoneTopic? {
            val a = arg.lowercase().trim()
            return entries.firstOrNull { it.key == a.replace(' ', '_') } ?: entries.firstOrNull { t -> t.words.any { a.contains(it) } }
        }
    }
}

/** Android supplies the readings. Every answer is a finished sentence. */
fun interface PhoneReader {
    fun read(topic: PhoneTopic): String
}

class PhoneStatusTool(private val reader: PhoneReader) : BrainTool {
    override val name = "phone_status"
    override val label = "Phone"
    override val description =
        "Read this phone's own sensors and status: battery (charge, drain, time left), speed (how fast you're moving), current location, " +
            "storage, network and signal, steps today, screen time and top apps, surroundings (which way you're facing, altitude, light), device info."
    override val inputSchema = schema("what" to "One of: battery, speed, location, storage, network, steps, screen_time, surroundings, device")
    override fun run(args: JsonObject): ToolResult {
        val what = args.str("what").ifBlank { args.entrySet().firstOrNull()?.value?.toString()?.trim('"').orEmpty() }
        val topic = PhoneTopic.from(what) ?: return ToolResult("Unknown phone reading \"$what\". Use one of: " + PhoneTopic.entries.joinToString { it.key } + ".")
        return ToolResult(reader.read(topic))
    }
}

enum class PhoneAction(val key: String) {
    FLASHLIGHT_ON("flashlight_on"), FLASHLIGHT_OFF("flashlight_off"), TIMER("timer"), ALARM("alarm"),
    SILENT("silent"), VIBRATE("vibrate"), SOUND_ON("sound_on"), VOLUME("volume"), OPEN_APP("open_app"), OPEN_SETTINGS("open_settings");

    companion object {
        fun from(arg: String): PhoneAction? {
            val a = arg.lowercase().trim().replace(' ', '_').replace('-', '_')
            entries.firstOrNull { it.key == a }?.let { return it }
            return when {
                ("flash" in a || "torch" in a) && ("off" in a || "stop" in a) -> FLASHLIGHT_OFF
                "flash" in a || "torch" in a -> FLASHLIGHT_ON
                "timer" in a -> TIMER
                "alarm" in a || "wake" in a -> ALARM
                "vibrat" in a -> VIBRATE
                "unmute" in a || "ring" in a || "sound" in a -> SOUND_ON
                "silen" in a || "mute" in a -> SILENT
                "volume" in a -> VOLUME
                "setting" in a -> OPEN_SETTINGS
                "open" in a || "launch" in a || "app" in a -> OPEN_APP
                else -> null
            }
        }
    }
}

fun interface PhoneController {
    fun act(action: PhoneAction, value: String): String
}

class PhoneActionTool(private val controller: PhoneController) : BrainTool {
    override val name = "phone_action"
    override val label = "Phone controls"
    override val description =
        "Control this phone when the user asks: flashlight on or off, set a timer, set an alarm, silent, vibrate or sound on, " +
            "media volume, open an app, or open a settings page."
    override val inputSchema = schema(
        "action" to "One of: flashlight_on, flashlight_off, timer, alarm, silent, vibrate, sound_on, volume, open_app, open_settings",
        "value" to "Minutes for a timer, a time for an alarm (like 7:30 am), a volume percent, or the app or settings page name. Empty if not needed",
    )
    override fun run(args: JsonObject): ToolResult {
        val a = PhoneAction.from(args.str("action")) ?: return ToolResult("Unknown phone action \"${args.str("action")}\".")
        return ToolResult(controller.act(a, args.str("value").trim()))
    }
}

// ==================================================================== plain maths, tested

object PhoneMath {

    /** A battery reading taken by Murmur's battery log. */
    data class Sample(val at: Long, val level: Int, val charging: Boolean)

    private const val HOUR = 3_600_000L

    /**
     * Percent per hour lost while unplugged, from the most recent unplugged stretch (at least
     * 40 minutes and 2 points of charge, so a single reading's rounding doesn't swing it).
     */
    fun drainPerHour(samples: List<Sample>, now: Long, window: Long = 4 * HOUR): Double? {
        val recent = samples.filter { it.at in (now - window)..now }.sortedBy { it.at }
        val run = recent.takeLastWhile { !it.charging }
        if (run.size < 2) return null
        val first = run.first(); val last = run.last()
        val hours = (last.at - first.at) / HOUR.toDouble()
        val lost = first.level - last.level
        if (hours < 0.66 || lost < 2) return if (hours >= 2 && lost in 0..1) 0.4 else null
        return lost / hours
    }

    /** The usual unplugged drain over the last week: the median of each unplugged stretch's rate. */
    fun typicalDrain(samples: List<Sample>, now: Long): Double? {
        val week = samples.filter { it.at > now - 7 * 24 * HOUR }.sortedBy { it.at }
        val rates = ArrayList<Double>()
        var start: Sample? = null
        var prev: Sample? = null
        for (s in week + Sample(Long.MAX_VALUE, 0, true)) {
            if (s.charging || (prev != null && s.at - prev.at > 3 * HOUR)) {
                val a = start; val b = prev
                if (a != null && b != null && b.at - a.at >= HOUR && a.level - b.level >= 3) rates += (a.level - b.level) / ((b.at - a.at) / HOUR.toDouble())
                start = if (s.charging) null else s
            } else if (start == null) start = s
            prev = s
        }
        if (rates.size < 2) return null
        return rates.sorted()[rates.size / 2]
    }

    /** Hour of day (0-23) the phone usually gets plugged in, from the last week's samples. */
    fun usualChargeHour(samples: List<Sample>, now: Long, hourOf: (Long) -> Int): Int? {
        val week = samples.filter { it.at > now - 7 * 24 * HOUR }.sortedBy { it.at }
        val starts = week.zipWithNext().filter { (a, b) -> !a.charging && b.charging }.map { hourOf(it.second.at) }
        if (starts.size < 3) return null
        // Evenings and nights wrap past midnight, so shift early hours by a day before the median.
        val shifted = starts.map { if (it < 5) it + 24 else it }.sorted()
        return shifted[shifted.size / 2] % 24
    }

    fun hoursLeft(level: Int, drain: Double?): Double? = drain?.takeIf { it > 0.3 }?.let { level / it }

    fun duration(hours: Double): String {
        val m = (hours * 60).roundToInt()
        return when {
            m < 60 -> "$m minutes"
            m < 600 -> "${m / 60} h ${m % 60} min"
            else -> "about ${(hours).roundToInt()} hours"
        }
    }

    /** Speed in the user's units, with what it likely means. */
    fun describeSpeed(metersPerSecond: Float, metric: Boolean): String {
        val v = if (metric) metersPerSecond * 3.6f else metersPerSecond * 2.23694f
        val unit = if (metric) "km/h" else "mph"
        val kmh = metersPerSecond * 3.6f
        val what = when {
            kmh < 1.5f -> "basically standing still"
            kmh < 7f -> "about walking pace"
            kmh < 16f -> "about running or cycling pace"
            kmh < 130f -> "vehicle speed"
            kmh < 300f -> "train speed"
            else -> "plane speed"
        }
        return "${if (v < 10) "%.1f".format(Locale.US, v) else v.roundToInt().toString()} $unit ($what)"
    }

    fun compass(degrees: Float): String {
        val names = listOf("north", "northeast", "east", "southeast", "south", "southwest", "west", "northwest")
        return names[(((degrees % 360) + 360) % 360 / 45f).roundToInt() % 8]
    }

    /** Altitude from air pressure (the standard atmosphere), in metres. */
    fun altitude(hPa: Float, seaLevel: Float = 1013.25f): Float = 44330f * (1f - Math.pow((hPa / seaLevel).toDouble(), 1 / 5.255).toFloat())

    fun bytes(n: Long): String = when {
        n >= 1_000_000_000L -> "%.1f GB".format(Locale.US, n / 1e9)
        else -> "${n / 1_000_000} MB"
    }

    fun signalWord(level: Int) = listOf("no signal", "poor", "moderate", "good", "great").getOrElse(level) { "unknown" }

    fun lightWord(lux: Float) = when {
        lux < 10 -> "dark"
        lux < 200 -> "dim, like indoors at night"
        lux < 1_000 -> "normal indoor light"
        lux < 10_000 -> "bright, like near a window or overcast daylight"
        else -> "full daylight"
    }

    /** Parses "7:30 am", "7am", "19:05" or "7.30 pm" to (hour, minute). */
    fun parseClock(s: String): Pair<Int, Int>? {
        val m = Regex("(?i)(\\d{1,2})(?:[:.](\\d{2}))?\\s*(a\\.?m\\.?|p\\.?m\\.?)?").find(s) ?: return null
        var h = m.groupValues[1].toInt()
        val min = m.groupValues[2].ifEmpty { "0" }.toInt()
        val ap = m.groupValues[3].lowercase().replace(".", "")
        if (ap == "pm" && h < 12) h += 12
        if (ap == "am" && h == 12) h = 0
        if (h > 23 || min > 59) return null
        return h to min
    }

    /** Parses "10", "10 minutes", "1 hour 30 min", "90 sec" into seconds. */
    fun parseTimer(s: String): Int? {
        val t = s.lowercase()
        var total = 0
        Regex("(\\d+(?:\\.\\d+)?)\\s*(h|hour|hr|m|min|minute|s|sec|second)?").findAll(t).forEach { m ->
            val n = m.groupValues[1].toDouble()
            total += when (m.groupValues[2].take(1)) {
                "h" -> (n * 3600).toInt()
                "s" -> n.toInt()
                else -> (n * 60).toInt() // plain numbers are minutes
            }
        }
        return total.takeIf { it in 1..24 * 3600 }
    }

    fun approxEqual(a: Double, b: Double, tol: Double) = abs(a - b) <= tol
}
