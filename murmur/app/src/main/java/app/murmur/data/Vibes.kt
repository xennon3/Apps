package app.murmur.data

import java.util.Calendar

/** Everything Murmur notices about a message on the device. Nothing leaves the phone. */
object Vibes {

    // ---------- heat: how hot a draft reads ----------

    enum class Heat { CALM, WARM, HOT }

    private val hotWords = listOf(
        "hate", "stupid", "idiot", "shut up", "whatever", "ridiculous", "pathetic", "seriously?",
        "are you kidding", "done with", "sick of", "never again", "always do this", "you never",
        "you always", "screw you", "wtf", "f u", "fuck", "shit", "damn you", "liar", "useless",
        "don't care", "dont care", "leave me alone", "how dare", "unbelievable", "i'm done", "im done",
    )

    fun score(text: String): Int {
        val t = text.trim()
        val lower = t.lowercase()
        // The dreaded one-word reply with a full stop.
        if (Regex("^(k|kk|fine|ok|okay|sure|wow)\\.$").matches(lower)) return 2
        if (t.length < 4) return 0
        var s = 0
        val letters = t.filter { it.isLetter() }
        if (letters.length >= 8) {
            val caps = letters.count { it.isUpperCase() }.toFloat() / letters.length
            if (caps > 0.7f) s += 3 else if (caps > 0.4f) s += 1
        }
        s += Regex("[!?]{2,}").findAll(t).count().coerceAtMost(3)
        s += hotWords.count { lower.contains(it) } * 2
        return s
    }

    fun heat(text: String): Heat = when (score(text)) {
        in 0..1 -> Heat.CALM
        in 2..3 -> Heat.WARM
        else -> Heat.HOT
    }

    /** Between midnight and 5am. */
    fun isLateNight(now: Long = System.currentTimeMillis()): Boolean {
        val h = Calendar.getInstance().apply { timeInMillis = now }.get(Calendar.HOUR_OF_DAY)
        return h in 0..4
    }

    /** 8:00 this morning (or tomorrow if that has passed). */
    fun nextMorning(now: Long = System.currentTimeMillis()): Long {
        val c = Calendar.getInstance().apply {
            timeInMillis = now
            set(Calendar.HOUR_OF_DAY, 8); set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
        }
        if (c.timeInMillis <= now) c.add(Calendar.DAY_OF_YEAR, 1)
        return c.timeInMillis
    }

    // ---------- one-time codes ----------

    private val codeHint = Regex(
        "(code|otp|passcode|pin|verification|verify|one[- ]time|login|log in|sign[- ]in|2fa|security|confirm|token|código|code de)",
        RegexOption.IGNORE_CASE,
    )
    private val codeShape = Regex("(?<![\\d\\w$#.,])(\\d{3}[- ]\\d{3}|\\d{4,8}|[A-Z0-9]{6})(?![\\d\\w%])")

    /** The one-time code in a message, if it looks like it carries one. */
    fun code(body: String): String? {
        if (!codeHint.containsMatchIn(body)) return null
        return codeShape.findAll(body)
            .map { it.value }
            .firstOrNull { v -> v.any { it.isDigit() } && !looksLikeYear(v) }
            ?.replace(" ", "")?.replace("-", "")
    }

    private fun looksLikeYear(v: String) = v.length == 4 && v.toIntOrNull()?.let { it in 1990..2099 } == true

    // ---------- SMS segments ----------

    private const val GSM =
        "@£\$¥èéùìòÇ\nØø\rÅåΔ_ΦΓΛΩΠΨΣΘΞÆæßÉ !\"#¤%&'()*+,-./0123456789:;<=>?¡ABCDEFGHIJKLMNOPQRSTUVWXYZÄÖÑÜ§¿abcdefghijklmnopqrstuvwxyzäöñüà"
    private const val GSM_EXT = "^{}\\[~]|€\u000C"

    /** (segments, characters left in the current segment). */
    fun segments(text: String): Pair<Int, Int> {
        if (text.isEmpty()) return 0 to 160
        val gsm = text.all { it in GSM || it in GSM_EXT }
        val units = if (gsm) text.sumOf { if (it in GSM_EXT) 2L else 1L }.toInt() else text.length
        val single = if (gsm) 160 else 70
        val multi = if (gsm) 153 else 67
        if (units <= single) return 1 to (single - units)
        val n = (units + multi - 1) / multi
        return n to (n * multi - units)
    }

    // ---------- avatars ----------

    private val palette = listOf(
        0xFFFF8A65, 0xFFB388FF, 0xFF4DD0E1, 0xFFAED581, 0xFFFFD54F,
        0xFFF06292, 0xFF7986CB, 0xFF4DB6AC, 0xFFFFB74D, 0xFF9575CD,
    )

    fun color(seed: String): Long = palette[(seed.hashCode() and 0x7fffffff) % palette.size]

    fun initials(title: String): String {
        if (title.firstOrNull()?.let { it.isDigit() || it == '+' || it == '(' } == true) return "#"
        return title.split(' ', ',').filter { it.isNotBlank() }.take(2)
            .joinToString("") { it.first().uppercase() }.ifEmpty { "?" }
    }
}
