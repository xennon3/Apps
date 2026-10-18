package app.murmur.data

import java.util.Calendar

/**
 * How a scheduled message repeats. Stored as text: "daily", "weekdays", "weekly",
 * "monthly:31" (the day of month it was set for, so Jan 31 → Feb 28 → Mar 31) or "yearly".
 */
object Repeat {
    enum class Kind(val label: String) {
        DAILY("Every day"), WEEKDAYS("Weekdays"), WEEKLY("Every week"), MONTHLY("Every month"), YEARLY("Every year")
    }

    fun encode(kind: Kind, firstAt: Long): String = when (kind) {
        Kind.MONTHLY -> "monthly:" + cal(firstAt).get(Calendar.DAY_OF_MONTH)
        else -> kind.name.lowercase()
    }

    fun kind(rule: String?): Kind? = when (rule?.substringBefore(':')) {
        "daily" -> Kind.DAILY
        "weekdays" -> Kind.WEEKDAYS
        "weekly" -> Kind.WEEKLY
        "monthly" -> Kind.MONTHLY
        "yearly" -> Kind.YEARLY
        else -> null
    }

    /** The first occurrence after [after], stepping from [at] (which keeps its time of day). */
    fun next(rule: String, at: Long, after: Long = at): Long {
        val k = kind(rule) ?: return at
        var t = at
        var guard = 0
        do {
            t = step(rule, k, t)
        } while (t <= after && ++guard < 10_000)
        return t
    }

    private fun step(rule: String, k: Kind, from: Long): Long {
        val c = cal(from)
        when (k) {
            Kind.DAILY -> c.add(Calendar.DAY_OF_YEAR, 1)
            Kind.WEEKDAYS -> do { c.add(Calendar.DAY_OF_YEAR, 1) } while (c.get(Calendar.DAY_OF_WEEK) in setOf(Calendar.SATURDAY, Calendar.SUNDAY))
            Kind.WEEKLY -> c.add(Calendar.WEEK_OF_YEAR, 1)
            Kind.MONTHLY -> {
                val want = rule.substringAfter(':', "").toIntOrNull() ?: c.get(Calendar.DAY_OF_MONTH)
                c.set(Calendar.DAY_OF_MONTH, 1)
                c.add(Calendar.MONTH, 1)
                c.set(Calendar.DAY_OF_MONTH, minOf(want, c.getActualMaximum(Calendar.DAY_OF_MONTH)))
            }
            Kind.YEARLY -> c.add(Calendar.YEAR, 1)
        }
        return c.timeInMillis
    }

    /** "Every weekday", "Every month on the 31st"… */
    fun describe(rule: String?): String? {
        val k = kind(rule) ?: return null
        return when (k) {
            Kind.MONTHLY -> rule!!.substringAfter(':', "").toIntOrNull()?.let { "Every month on the ${ordinal(it)}" } ?: k.label
            Kind.WEEKDAYS -> "Every weekday"
            else -> k.label
        }
    }

    private fun ordinal(n: Int) = n.toString() + when {
        n % 100 in 11..13 -> "th"
        n % 10 == 1 -> "st"
        n % 10 == 2 -> "nd"
        n % 10 == 3 -> "rd"
        else -> "th"
    }

    private fun cal(t: Long) = Calendar.getInstance().apply { timeInMillis = t }
}
