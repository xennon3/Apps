package app.murmur.brain

import java.util.Calendar
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Where the phone spends its time, learned from location fixes: a stay of 15 minutes or more
 * within 150 m becomes a place, and places get names from when they're visited (most nights:
 * home; most weekday daytimes: work). Plain Kotlin, kept on the phone.
 */
class Places(var places: MutableList<Place> = ArrayList()) {
    data class Place(val id: Int, var lat: Double, var lon: Double, var visits: Int = 0, var nightMinutes: Int = 0, var workMinutes: Int = 0, var name: String? = null)

    /** Where we are now: a candidate spot, and whether it has become a stay at a place. */
    var anchorLat = 0.0; var anchorLon = 0.0; var anchorSince = 0L; var hasAnchor = false
    var current: Place? = null; var arrivedAt = 0L
    private var lastAt = 0L
    /** Network locations jump around; leaving takes two readings in a row away from the place. */
    private var away = 0

    sealed interface Change {
        data class Arrived(val place: Place, val label: String) : Change
        data class Left(val place: Place, val label: String, val stayedMinutes: Long) : Change
    }

    fun label(p: Place): String = p.name ?: when {
        p == home() -> "home"
        p == work() -> "work"
        p.visits >= 3 -> "a place you've been ${p.visits} times"
        else -> "a new place"
    }

    fun home(): Place? = places.filter { it.nightMinutes >= 180 * 3 }.maxByOrNull { it.nightMinutes }

    fun work(): Place? = places.filter { it.workMinutes >= 120 * 3 && it != home() }.maxByOrNull { it.workMinutes }

    /** Feed a location fix; returns what changed, if anything. */
    fun fix(lat: Double, lon: Double, at: Long, calendar: (Long) -> Calendar = { t -> Calendar.getInstance().apply { timeInMillis = t } }): Change? {
        val minutes = if (lastAt == 0L) 0 else ((at - lastAt) / 60_000L).toInt().coerceIn(0, 60)
        lastAt = at
        current?.let { here ->
            if (meters(lat, lon, here.lat, here.lon) <= 250 || ++away < 2) {
                if (meters(lat, lon, here.lat, here.lon) <= 250) away = 0
                // Still here: count the time toward "night" or "weekday daytime" to learn home and work.
                val c = calendar(at)
                val h = c.get(Calendar.HOUR_OF_DAY); val d = c.get(Calendar.DAY_OF_WEEK)
                if (h >= 22 || h < 6) here.nightMinutes += minutes
                else if (d in Calendar.MONDAY..Calendar.FRIDAY && h in 9..16) here.workMinutes += minutes
                return null
            }
            current = null; hasAnchor = false; away = 0
            return Change.Left(here, label(here), (at - arrivedAt) / 60_000L)
        }
        if (!hasAnchor || meters(lat, lon, anchorLat, anchorLon) > 150) {
            anchorLat = lat; anchorLon = lon; anchorSince = at; hasAnchor = true
            return null
        }
        if (at - anchorSince < 15 * 60_000L) return null
        // A stay: an existing place within 150 m, or a new one.
        val place = places.minByOrNull { meters(lat, lon, it.lat, it.lon) }?.takeIf { meters(lat, lon, it.lat, it.lon) <= 150 }
            ?: Place((places.maxOfOrNull { it.id } ?: 0) + 1, lat, lon).also { places += it }
        place.visits++
        current = place; arrivedAt = anchorSince
        return Change.Arrived(place, label(place))
    }

    companion object {
        fun meters(aLat: Double, aLon: Double, bLat: Double, bLon: Double): Double {
            val r = 6_371_000.0
            val dLat = Math.toRadians(bLat - aLat); val dLon = Math.toRadians(bLon - aLon)
            val h = sin(dLat / 2).pow(2) + cos(Math.toRadians(aLat)) * cos(Math.toRadians(bLat)) * sin(dLon / 2).pow(2)
            return 2 * r * asin(sqrt(h))
        }
    }
}
