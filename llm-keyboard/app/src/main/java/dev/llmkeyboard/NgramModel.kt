package dev.llmkeyboard

import java.io.File

/**
 * Instant, per-keystroke word prediction that learns from what the user commits.
 * The LLM is too slow to run on every key press, so this covers word completion and next-word
 * suggestions while the LLM handles full-sentence continuation in the background.
 */
class NgramModel(private val store: File) {
    private val uni = HashMap<String, Int>()
    private val bi = HashMap<String, HashMap<String, Int>>()
    private var dirty = 0

    init {
        SEED.split(' ').forEach { uni[it] = (uni[it] ?: 0) + 1 }
        runCatching {
            store.forEachLine { line ->
                val p = line.split('\t')
                when (p.size) {
                    2 -> uni[p[0]] = p[1].toInt()
                    3 -> bi.getOrPut(p[0]) { HashMap() }[p[1]] = p[2].toInt()
                }
            }
        }
    }

    fun learn(text: String) {
        val words = WORD.findAll(text.lowercase()).map { it.value }.toList()
        words.forEachIndexed { i, w ->
            uni[w] = (uni[w] ?: 0) + 1
            if (i > 0) bi.getOrPut(words[i - 1]) { HashMap() }.merge(w, 1, Int::plus)
        }
        dirty += words.size
        if (dirty >= 50) save()
    }

    /** Words starting with [prefix], boosted when they commonly follow [prev]. */
    fun suggest(prefix: String, prev: String?, n: Int = 2): List<String> {
        val p = prefix.lowercase()
        val follow = prev?.let { bi[it.lowercase()] }
        val pool: Collection<String> = when {
            p.isEmpty() -> follow?.keys ?: return emptyList()
            else -> uni.keys.filter { it.startsWith(p) && it != p }
        }
        return pool.sortedByDescending { (follow?.get(it) ?: 0) * 20 + (uni[it] ?: 0) }.take(n)
            .map { if (prefix.firstOrNull()?.isUpperCase() == true) it.replaceFirstChar(Char::uppercase) else it }
    }

    @Synchronized
    fun save() {
        dirty = 0
        runCatching {
            store.bufferedWriter().use { w ->
                uni.forEach { (k, v) -> if (v > 1) w.write("$k\t$v\n") }
                bi.forEach { (a, m) -> m.forEach { (b, v) -> w.write("$a\t$b\t$v\n") } }
            }
        }
    }

    companion object {
        val WORD = Regex("[a-zà-ÿ']+")
        private const val SEED = "the be to of and a in that have I it for not on with he as you do at this but his by " +
            "from they we say her she or an will my one all would there their what so up out if about who get which go me " +
            "when make can like time no just him know take people into year your good some could them see other than then " +
            "now look only come its over think also back after use two how our work first well way even new want because " +
            "any these give day most us hello thanks please okay sorry today tomorrow maybe really going"
    }
}
