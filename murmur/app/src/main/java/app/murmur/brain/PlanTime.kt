package app.murmur.brain

import java.util.Calendar

/**
 * Turns the "when" the model copied out of a text ("friday at 7", "Oct 24 at 2:15 PM",
 * "tomorrow morning") into a real date, counted from when the text was sent. Small models
 * are bad at calendar arithmetic, so this part is plain code.
 */
object PlanTime {

    data class When(val start: Long, val allDay: Boolean)

    private val days = listOf("sunday", "monday", "tuesday", "wednesday", "thursday", "friday", "saturday")
    private val dayShort = mapOf("sun" to 0, "mon" to 1, "tue" to 2, "tues" to 2, "wed" to 3, "thu" to 4, "thur" to 4, "thurs" to 4, "fri" to 5, "sat" to 6)
    private val months = listOf("jan", "feb", "mar", "apr", "may", "jun", "jul", "aug", "sep", "oct", "nov", "dec")

    fun resolve(phrase: String, sentAt: Long, context: String = ""): When? {
        val p = " " + phrase.lowercase().replace(Regex("[,.!?]"), " ").replace(Regex("\\s+"), " ").trim() + " "
        if (Regex(" (yesterday|last (night|week|weekend)|ago) ").containsMatchIn(p)) return null

        val c = Calendar.getInstance().apply { timeInMillis = sentAt; set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0) }
        var dayFound = false

        // ---- the day
        when {
            p.contains(" day after tomorrow ") -> { c.add(Calendar.DAY_OF_YEAR, 2); dayFound = true }
            Regex(" (tomorrow|tmrw|tmr|tomorow) ").containsMatchIn(p) -> { c.add(Calendar.DAY_OF_YEAR, 1); dayFound = true }
            Regex(" (today|tonight|tonite|this (morning|afternoon|evening)) ").containsMatchIn(p) -> dayFound = true
        }
        if (!dayFound) {
            // "oct 24", "october 24th", "24 oct" (before weekdays: "Saturday, November 8" means that date)
            val m = Regex(" (${months.joinToString("|")})[a-z]* (\\d{1,2})(st|nd|rd|th)? ").find(p)
                ?: Regex(" (\\d{1,2})(st|nd|rd|th)? (${months.joinToString("|")})[a-z]* ").find(p)?.let { r ->
                    Regex(" (${r.groupValues[3]}) (${r.groupValues[1]}) ").find(" ${r.groupValues[3]} ${r.groupValues[1]} ")
                }
            val numeric = Regex(" (\\d{1,2})/(\\d{1,2})(/\\d{2,4})? ").find(p)
            val (month, day) = when {
                m != null -> months.indexOf(m.groupValues[1].take(3)) to m.groupValues[2].toInt()
                numeric != null -> numeric.groupValues[1].toInt() - 1 to numeric.groupValues[2].toInt()
                else -> -1 to -1
            }
            if (month in 0..11 && day in 1..31) {
                val sent = c.timeInMillis
                c.set(Calendar.MONTH, month); c.set(Calendar.DAY_OF_MONTH, day)
                if (c.timeInMillis < sent - 86_400_000L) c.add(Calendar.YEAR, 1)
                dayFound = true
            }
        }
        if (!dayFound) {
            val weekday = days.indexOfFirst { p.contains(" $it ") || p.contains(" ${it}s ") }.takeIf { it >= 0 }
                ?: dayShort.entries.firstOrNull { p.contains(" ${it.key} ") }?.value
            if (weekday != null) {
                val today = c.get(Calendar.DAY_OF_WEEK) - 1
                // The coming one; "next friday" said on a Friday means a week out.
                var ahead = (weekday - today + 7) % 7
                if (ahead == 0 && p.contains(" next ")) ahead = 7
                c.add(Calendar.DAY_OF_YEAR, ahead)
                dayFound = true
            }
        }

        // ---- the time
        var hour = -1
        var minute = 0
        val clock = Regex("(?<= )(?:at |@ ?)?(\\d{1,2})(?::(\\d{2}))? ?(am|pm|a m|p m|a|p)?(?= )").findAll(p)
            .firstOrNull { r ->
                val h = r.groupValues[1].toInt()
                val hasMarker = r.groupValues[3].isNotEmpty() || r.groupValues[2].isNotEmpty() || r.value.contains("at ") || r.value.contains("@")
                h in 1..12 && hasMarker || (h in 13..23 && r.groupValues[2].isNotEmpty())
            }
        if (clock != null) {
            hour = clock.groupValues[1].toInt()
            minute = clock.groupValues[2].toIntOrNull() ?: 0
            val ap = clock.groupValues[3].replace(" ", "")
            when {
                ap.startsWith("p") && hour < 12 -> hour += 12
                ap.startsWith("a") && hour == 12 -> hour = 0
                ap.isEmpty() && hour in 1..11 -> {
                    // No am/pm: evenings for plans like dinner or "tonight"; small hours are afternoons.
                    val evening = Regex("tonight|tonite|evening|dinner|drinks|movie|party|night").containsMatchIn(p + " " + context.lowercase())
                    val morning = Regex("morning|breakfast|brunch").containsMatchIn(p + " " + context.lowercase())
                    if (!morning && (evening || hour <= 7)) hour += 12
                }
            }
        } else {
            when {
                p.contains(" noon ") || p.contains(" lunch ") -> hour = 12
                p.contains(" midnight ") -> hour = 0
                p.contains(" morning ") -> hour = 9
                p.contains(" afternoon ") -> hour = 15
                p.contains(" evening ") || p.contains(" tonight ") || p.contains(" tonite ") -> hour = 19
            }
        }

        if (!dayFound && hour < 0) return null
        if (hour < 0) {
            c.set(Calendar.HOUR_OF_DAY, 0); c.set(Calendar.MINUTE, 0)
            return When(c.timeInMillis, allDay = true)
        }
        c.set(Calendar.HOUR_OF_DAY, hour); c.set(Calendar.MINUTE, minute)
        // A time with no day ("at 7") means the next time it comes round.
        if (!dayFound && c.timeInMillis <= sentAt) c.add(Calendar.DAY_OF_YEAR, 1)
        return When(c.timeInMillis, allDay = false)
    }

    /** Cheap pre-check so the model is only asked about texts that mention a day or a time. */
    private val mentionsWhen = Regex(
        "(?i)\\b(today|tonight|tonite|tomorrow|tmrw|tmr|mon|tues?|wed|thu(rs)?|fri|sat|sun)(day)?s?\\b|" +
            "\\b(jan|feb|mar|apr|may|jun|jul|aug|sep|oct|nov|dec)[a-z]*\\.? \\d|\\b\\d{1,2}(:\\d{2})? ?(am|pm)\\b|\\bat \\d|\\b\\d{1,2}/\\d{1,2}\\b|\\bnoon\\b",
    )

    fun mightHavePlan(text: String) = mentionsWhen.containsMatchIn(text)
}
