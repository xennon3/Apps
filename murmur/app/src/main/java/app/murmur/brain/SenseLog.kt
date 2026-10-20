package app.murmur.brain

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt

/**
 * Ambient mode's sensor diary: a reading every few seconds, kept by plain code with timestamps.
 * The model never reads the raw rows (thousands of them would swamp it); [condense] turns them
 * into a short timeline with real times and durations, so it knows the drive was 8 minutes,
 * not "over an hour". If nothing changed, there's nothing to think about.
 */
object SenseLog {
    data class Sample(
        val at: Long,
        /** still, walking or vehicle. */
        val motion: String,
        val speed: Float = 0f,
        val lat: Double? = null,
        val lon: Double? = null,
        val screen: Boolean = false,
        val charging: Boolean = false,
        val level: Int = -1,
        val place: String? = null,
    )

    private fun time(t: Long) = SimpleDateFormat("h:mm a", Locale.US).format(Date(t))
    private fun mins(ms: Long) = (ms / 60_000.0).roundToInt().let { if (it < 60) "$it min" else "${it / 60} h ${it % 60} min" }

    data class Segment(val from: Long, val to: Long, val motion: String, val miles: Double, val place: String?)

    /**
     * Runs of the same motion, with brief blips (under a minute: a stop at a light, a few steps)
     * folded into their neighbours.
     */
    fun segments(samples: List<Sample>): List<Segment> {
        if (samples.isEmpty()) return emptyList()
        val raw = ArrayList<MutableList<Sample>>()
        for (s in samples.sortedBy { it.at }) {
            if (raw.isEmpty() || raw.last().last().motion != s.motion) raw += mutableListOf(s) else raw.last() += s
        }
        // Fold short runs into the previous one.
        val merged = ArrayList<MutableList<Sample>>()
        for (run in raw) {
            val long = run.last().at - run.first().at >= 60_000L || run === raw.last()
            if (merged.isNotEmpty() && (!long || merged.last().first().motion == run.first().motion)) merged.last().addAll(run)
            else merged += run.toMutableList()
        }
        return merged.mapIndexed { i, run ->
            val end = merged.getOrNull(i + 1)?.first()?.at ?: run.last().at
            var meters = 0.0
            run.zipWithNext().forEach { (a, b) -> if (a.lat != null && b.lat != null) meters += Places.meters(a.lat, a.lon!!, b.lat, b.lon!!) }
            Segment(run.first().at, end, run.groupingBy { it.motion }.eachCount().maxBy { it.value }.key, meters / 1609.34, run.last().place)
        }
    }

    /** "9:44-9:52 AM driving, 8 min, about 3.1 miles" lines, plus screen and charging changes. */
    fun condense(samples: List<Sample>, now: Long): List<String> {
        if (samples.isEmpty()) return emptyList()
        val lines = ArrayList<String>()
        val segs = segments(samples)
        for ((i, s) in segs.withIndex()) {
            val last = i == segs.lastIndex
            val span = if (last) "${time(s.from)}-now" else "${time(s.from)}-${time(s.to)}"
            val dur = mins((if (last) now else s.to) - s.from)
            val what = when (s.motion) { "vehicle" -> "driving or riding"; "walking" -> "walking"; else -> "still" }
            val dist = if (s.motion != "still" && s.miles >= 0.1) ", about ${"%.1f".format(Locale.US, s.miles)} miles" else ""
            val where = s.place?.let { " at $it" } ?: ""
            lines += "$span $what, $dur$dist$where"
        }
        val sorted = samples.sortedBy { it.at }
        sorted.zipWithNext().forEach { (a, b) ->
            if (!a.charging && b.charging) lines += "${time(b.at)} plugged in at ${b.level}%"
            if (a.charging && !b.charging) lines += "${time(b.at)} unplugged at ${b.level}%"
        }
        val unlocks = sorted.zipWithNext().count { (a, b) -> !a.screen && b.screen }
        if (unlocks > 0) lines += "Screen turned on $unlocks time${if (unlocks > 1) "s" else ""}"
        return lines
    }

    /**
     * Did anything worth a thought happen? A regular check-in with nothing new (same motion, same
     * place, no charging change, battery within a few percent) doesn't wake the model.
     */
    fun changed(samples: List<Sample>): Boolean {
        if (samples.size < 2) return false
        val s = samples.sortedBy { it.at }
        val first = s.first(); val last = s.last()
        return segments(s).size > 1 || first.place != last.place || first.charging != last.charging ||
            (first.level >= 0 && last.level >= 0 && first.level - last.level >= 5)
    }
}
