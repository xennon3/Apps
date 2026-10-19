package app.murmur.brain

import android.content.Context
import app.murmur.brain.tools.BrainTool
import app.murmur.brain.tools.CalculateTool
import app.murmur.brain.tools.CurrencyTool
import app.murmur.brain.tools.McpServer
import app.murmur.brain.tools.McpTool
import app.murmur.brain.tools.PhoneActionTool
import app.murmur.brain.tools.PhoneStatusTool
import app.murmur.brain.tools.ReadPageTool
import app.murmur.brain.tools.SearchEngine
import app.murmur.brain.tools.TimeTool
import app.murmur.brain.tools.WeatherTool
import app.murmur.brain.tools.WebSearchTool
import app.murmur.brain.tools.WikipediaTool
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

/** Web lookups: off by default. Only the words the Brain searches for leave the phone. */
object BrainWeb {
    private fun prefs(c: Context) = c.getSharedPreferences("brain_web", Context.MODE_PRIVATE)

    fun on(c: Context) = prefs(c).getBoolean("on", false)
    fun setOn(c: Context, v: Boolean) = prefs(c).edit().putBoolean("on", v).apply()

    /** Which search engine; someone who already saved a Brave key keeps Brave. */
    fun engine(c: Context): SearchEngine = prefs(c).getString("engine", null)?.let { n -> SearchEngine.entries.firstOrNull { it.name == n } }
        ?: if (key(c, SearchEngine.BRAVE).isNotBlank()) SearchEngine.BRAVE else SearchEngine.FREE
    fun setEngine(c: Context, e: SearchEngine) = prefs(c).edit().putString("engine", e.name).apply()

    fun key(c: Context, e: SearchEngine): String = when (e) {
        SearchEngine.FREE -> ""
        SearchEngine.BRAVE -> prefs(c).getString("brave", "")!!.trim()
        SearchEngine.TAVILY -> prefs(c).getString("tavily", "")!!.trim()
    }
    fun setKey(c: Context, e: SearchEngine, v: String) { if (e != SearchEngine.FREE) prefs(c).edit().putString(e.name.lowercase(), v.trim()).apply() }

    /** The search tool to offer, or null when the chosen engine still needs a key. */
    fun searchTool(c: Context): WebSearchTool? {
        val e = engine(c)
        val k = key(c, e)
        return if (e != SearchEngine.FREE && k.isBlank()) null else WebSearchTool(e, k)
    }
}

/** Built-in skills the user switched off, and the skills they wrote. */
object SkillStore {
    private fun prefs(c: Context) = c.getSharedPreferences("brain_skills", Context.MODE_PRIVATE)

    fun enabled(c: Context, s: BrainSkill) = prefs(c).getBoolean("on_" + s.id, true)
    fun setEnabled(c: Context, s: BrainSkill, v: Boolean) = prefs(c).edit().putBoolean("on_" + s.id, v).apply()

    fun custom(c: Context): List<BrainSkill> = runCatching {
        val a = JSONArray(prefs(c).getString("custom", "[]"))
        List(a.length()) { i ->
            val o = a.getJSONObject(i)
            BrainSkill(o.getString("id"), o.getString("name"), o.getString("triggers").split(',').map { it.trim() }.filter { it.isNotEmpty() }, o.getString("text"), builtIn = false)
        }
    }.getOrDefault(emptyList())

    fun addCustom(c: Context, name: String, triggers: String, text: String) {
        val list = custom(c) + BrainSkill("u" + System.currentTimeMillis(), name.trim(), triggers.split(',').map { it.trim() }, text.trim(), false)
        save(c, list)
    }

    fun removeCustom(c: Context, s: BrainSkill) = save(c, custom(c).filter { it.id != s.id })

    private fun save(c: Context, list: List<BrainSkill>) = prefs(c).edit().putString("custom", JSONArray(list.map {
        JSONObject().put("id", it.id).put("name", it.name).put("triggers", it.triggers.joinToString(", ")).put("text", it.instructions)
    }).toString()).apply()

    fun all(c: Context): List<BrainSkill> = Skills.BUILT_IN + custom(c)

    fun active(c: Context): List<BrainSkill> = all(c).filter { enabled(c, it) }
}

/** Remote MCP servers the user added (experimental). */
object McpStore {
    data class Entry(val url: String, val token: String, val on: Boolean)

    private fun prefs(c: Context) = c.getSharedPreferences("brain_mcp", Context.MODE_PRIVATE)

    fun list(c: Context): List<Entry> = runCatching {
        val a = JSONArray(prefs(c).getString("servers", "[]"))
        List(a.length()) { i -> a.getJSONObject(i).let { Entry(it.getString("url"), it.optString("token"), it.optBoolean("on", true)) } }
    }.getOrDefault(emptyList())

    private fun save(c: Context, l: List<Entry>) = prefs(c).edit().putString("servers", JSONArray(l.map {
        JSONObject().put("url", it.url).put("token", it.token).put("on", it.on)
    }).toString()).apply()

    fun add(c: Context, url: String, token: String) = save(c, list(c).filter { it.url != url } + Entry(url.trim(), token.trim(), true))
    fun remove(c: Context, url: String) { save(c, list(c).filter { it.url != url }); McpRegistry.forget(url) }
    fun setOn(c: Context, url: String, on: Boolean) = save(c, list(c).map { if (it.url == url) it.copy(on = on) else it })
}

/** Connected MCP servers and their tools, kept for the session (connecting is a network round trip). */
object McpRegistry {
    private val tools = ConcurrentHashMap<String, List<McpTool>>()

    /** Connects (or reconnects) and returns the server's tools; throws with a readable message on failure. */
    fun connect(entry: McpStore.Entry): List<McpTool> =
        McpServer(entry.url, entry.token.ifBlank { null }).connect().also { tools[entry.url] = it }

    fun forget(url: String) { tools.remove(url) }

    /** Tools from every switched-on server, connecting lazily. A small model handles a few tools well, so cap them. */
    fun toolsFor(c: Context, max: Int = 8): List<McpTool> =
        McpStore.list(c).filter { it.on }.flatMap { e -> tools[e.url] ?: runCatching { connect(e) }.getOrDefault(emptyList()) }.take(max)
}

/** The tools offered to the Brain for a general question, based on what the user switched on. */
object BrainTools {
    fun forQuestion(c: Context): List<BrainTool> {
        val list = ArrayList<BrainTool>()
        list += CalculateTool()
        list += TimeTool()
        if (BrainWeb.on(c)) {
            BrainWeb.searchTool(c)?.let { list += it }
            list += WikipediaTool()
            list += ReadPageTool()
            list += WeatherTool(fahrenheit = Locale.getDefault().country in setOf("US", "LR", "MM", "BS", "BZ", "KY", "PW"))
            list += CurrencyTool()
        }
        // The phone's own sensors and controls; each reading still checks its own switch.
        val phoneReads = listOf(BrainAccess.Source.PHONE, BrainAccess.Source.LOCATION, BrainAccess.Source.MOTION, BrainAccess.Source.SCREEN_TIME)
        if (phoneReads.any { BrainAccess.on(c, it) }) list += PhoneStatusTool(PhoneSensors(c))
        if (BrainAccess.on(c, BrainAccess.Source.CONTROLS)) list += PhoneActionTool(PhoneControls(c))
        list += McpRegistry.toolsFor(c)
        return list
    }
}
