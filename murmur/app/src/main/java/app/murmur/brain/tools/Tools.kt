package app.murmur.brain.tools

import app.murmur.brain.Calc
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.net.HttpURLConnection
import java.net.URI
import java.net.URLEncoder
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

/*
 * Murmur Brain's tools. Plain Kotlin (no Android code) so the exact same tools can be run
 * against the model on a computer before they ship. Each tool has the shape of an MCP tool:
 * a name, a description, a JSON-Schema for its inputs, and code that returns plain text.
 *
 * Lesson from testing: tools must return finished sentences, never raw data. Given raw JSON,
 * the model once "converted" $50 to €2,221 by applying the rate twice.
 */

/** A web page the answer came from, shown as a tappable chip. */
data class WebSource(val title: String, val url: String)

class ToolResult(val text: String, val sources: List<WebSource> = emptyList())

interface BrainTool {
    val name: String
    val description: String
    /** JSON Schema for the inputs, as MCP and LiteRT-LM both expect. */
    val inputSchema: JsonObject
    /** What the user sees under an answer, e.g. "Web search". */
    val label: String
    fun run(args: JsonObject): ToolResult
}

/** Builds a simple all-strings input schema. */
fun schema(vararg params: Pair<String, String>): JsonObject = JsonObject().apply {
    addProperty("type", "object")
    add("properties", JsonObject().apply {
        params.forEach { (k, d) -> add(k, JsonObject().apply { addProperty("type", "string"); addProperty("description", d) }) }
    })
    add("required", JsonArray().apply { params.forEach { add(it.first) } })
}

fun JsonObject.str(k: String): String = get(k)?.let { if (it.isJsonPrimitive) it.asString else it.toString() }.orEmpty()

object Http {
    const val AGENT = "Murmur/1.10 (Android texting app; on-device assistant)"

    fun get(url: String, headers: Map<String, String> = emptyMap(), maxBytes: Int = 1_000_000): String {
        val c = URI(url).toURL().openConnection() as HttpURLConnection
        c.connectTimeout = 15_000; c.readTimeout = 15_000
        c.setRequestProperty("User-Agent", AGENT)
        headers.forEach { (k, v) -> c.setRequestProperty(k, v) }
        val code = c.responseCode
        if (code !in 200..299) throw IllegalStateException("HTTP $code")
        return c.inputStream.use { s -> String(readUpTo(s, maxBytes), Charsets.UTF_8) }
    }

    /** DuckDuckGo's plain page turns away unknown apps, so it gets an ordinary phone browser's name. */
    const val BROWSER = "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0 Mobile Safari/537.36"

    fun post(url: String, body: String, headers: Map<String, String> = emptyMap(), maxBytes: Int = 1_000_000): String {
        val c = URI(url).toURL().openConnection() as HttpURLConnection
        c.connectTimeout = 15_000; c.readTimeout = 20_000
        c.requestMethod = "POST"; c.doOutput = true
        c.setRequestProperty("User-Agent", AGENT)
        headers.forEach { (k, v) -> c.setRequestProperty(k, v) }
        c.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
        val code = c.responseCode
        if (code == 202) throw IllegalStateException("search is busy, try again in a minute") // DuckDuckGo's polite "slow down"
        if (code !in 200..299) throw IllegalStateException("HTTP $code")
        return c.inputStream.use { s -> String(readUpTo(s, maxBytes), Charsets.UTF_8) }
    }

    fun enc(s: String): String = URLEncoder.encode(s, "UTF-8")

    /** InputStream.readNBytes only exists on Android 13+. */
    fun readUpTo(s: java.io.InputStream, max: Int): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        val buf = ByteArray(16_384)
        while (out.size() < max) {
            val n = s.read(buf, 0, minOf(buf.size, max - out.size()))
            if (n < 0) break
            out.write(buf, 0, n)
        }
        return out.toByteArray()
    }
}

// ==================================================================== always available

class CalculateTool : BrainTool {
    override val name = "calculate"
    override val label = "Calculator"
    override val description = "Do arithmetic exactly. Use for any math, percentages, tips or splitting a bill."
    override val inputSchema = schema("expression" to "The math, like 85 * 0.2 or 15% of 80")
    override fun run(args: JsonObject): ToolResult {
        val e = args.str("expression")
        return ToolResult(Calc.eval(e)?.let { "$e = ${Calc.format(it)}" } ?: "Couldn't calculate \"$e\".")
    }
}

class TimeTool(private val zone: () -> ZoneId = { ZoneId.systemDefault() }) : BrainTool {
    override val name = "current_time"
    override val label = "Clock"
    override val description = "The current date and time, here or in another city's time zone."
    override val inputSchema = schema("timezone" to "IANA zone like Asia/Tokyo, or 'local'")
    override fun run(args: JsonObject): ToolResult {
        val z = runCatching { ZoneId.of(args.str("timezone")) }.getOrElse { zone() }
        val now = ZonedDateTime.now(z)
        return ToolResult("It is " + now.format(DateTimeFormatter.ofPattern("EEEE, MMMM d, h:mm a", Locale.US)) + " in " + z.id.replace('_', ' ') + ".")
    }
}

// ==================================================================== web (only when the user turns web lookups on)

/** Brave Search: reliable general web search; needs the user's own key. */
/** Where general web searches go. FREE needs no key (DuckDuckGo's plain HTML page); the others use the user's own key. */
enum class SearchEngine(val label: String, val signup: String?) {
    FREE("Free (DuckDuckGo, no key)", null),
    TAVILY("Tavily (free key, 1,000 searches a month)", "https://app.tavily.com/sign-in"),
    BRAVE("Brave Search (your key)", "https://api-dashboard.search.brave.com/register"),
}

class WebSearchTool(private val engine: SearchEngine, private val key: String = "") : BrainTool {
    override val name = "web_search"
    override val label = "Web search"
    override val description = "Search the web for recent or specific information: news, scores, opening hours, prices, events, products."
    override val inputSchema = schema("query" to "What to search for")
    override fun run(args: JsonObject): ToolResult {
        val q = args.str("query")
        return when (engine) {
            SearchEngine.BRAVE -> braveResults(q, Http.get(
                "https://api.search.brave.com/res/v1/web/search?q=${Http.enc(q)}&count=5&extra_snippets=true",
                mapOf("X-Subscription-Token" to key, "Accept" to "application/json"),
            ))
            SearchEngine.TAVILY -> tavilyResults(q, Http.post(
                "https://api.tavily.com/search",
                JsonObject().apply { addProperty("query", q); addProperty("max_results", 5); addProperty("include_answer", "basic") }.toString(),
                mapOf("Authorization" to "Bearer $key", "Content-Type" to "application/json"),
            ))
            SearchEngine.FREE -> duckResults(q, Http.post(
                "https://html.duckduckgo.com/html/", "q=${Http.enc(q)}",
                mapOf("Content-Type" to "application/x-www-form-urlencoded", "User-Agent" to Http.BROWSER, "Referer" to "https://html.duckduckgo.com/"),
            ))
        }
    }

    companion object {
        private fun host(url: String) = runCatching { URI(url).host.removePrefix("www.") }.getOrDefault(url)

        private fun numbered(query: String, items: List<Triple<String, String, String>>, lead: String = ""): ToolResult {
            if (items.isEmpty()) return ToolResult(lead.ifBlank { "No web results for \"$query\"." })
            val lines = items.take(5).mapIndexed { i, (title, url, text) -> "${i + 1}. $title (${host(url)}): $text".trim() }
            return ToolResult(
                (if (lead.isNotBlank()) "$lead\n" else "") + "Web results for \"$query\":\n" + lines.joinToString("\n"),
                items.take(5).map { (title, url, _) -> WebSource(title.ifBlank { host(url) }, url) },
            )
        }

        /** Brave's JSON → numbered plain-text results the model reads well. */
        fun braveResults(query: String, body: String): ToolResult {
            val results = JsonParser.parseString(body).asJsonObject.getAsJsonObject("web")?.getAsJsonArray("results") ?: JsonArray()
            return numbered(query, results.map { r ->
                val o = r.asJsonObject
                val age = o.str("age").takeIf { it.isNotBlank() }?.let { " ($it)" } ?: ""
                val extra = o.getAsJsonArray("extra_snippets")?.take(1)?.joinToString(" ") { stripTags(it.asString) }.orEmpty()
                Triple(stripTags(o.str("title")), o.str("url"), "${stripTags(o.str("description"))}$age $extra".trim())
            })
        }

        /** Tavily's JSON (a short answer plus results) → the same shape. */
        fun tavilyResults(query: String, body: String): ToolResult {
            val o = JsonParser.parseString(body).asJsonObject
            val answer = o.str("answer").trim().let { if (it.isNotBlank()) "Quick answer: $it" else "" }
            val results = o.getAsJsonArray("results") ?: JsonArray()
            return numbered(query, results.map { r ->
                val x = r.asJsonObject
                Triple(stripTags(x.str("title")), x.str("url"), stripTags(x.str("content")).take(400))
            }, answer)
        }

        /** DuckDuckGo's no-JavaScript results page → the same shape. Ads are skipped. */
        fun duckResults(query: String, html: String): ToolResult {
            if ("anomaly-modal" in html || "challenge-form" in html) throw IllegalStateException("DuckDuckGo asked for a robot check; try again in a minute")
            val links = Regex("""(?is)<a[^>]*class="result__a"[^>]*href="([^"]+)"[^>]*>(.*?)</a>""").findAll(html).toList()
            val snippets = Regex("""(?is)class="result__snippet"[^>]*>(.*?)</(?:a|div|td)>""").findAll(html).map { stripTags(it.groupValues[1]).trim() }.toList()
            val items = links.mapIndexedNotNull { i, m ->
                val url = duckUrl(m.groupValues[1]) ?: return@mapIndexedNotNull null
                Triple(stripTags(m.groupValues[2]).trim(), url, snippets.getOrElse(i) { "" })
            }
            return numbered(query, items)
        }

        /** "//duckduckgo.com/l/?uddg=https%3A%2F%2F…&rut=…" → the real link; ad links → null. */
        fun duckUrl(href: String): String? {
            val h = href.replace("&amp;", "&")
            if ("duckduckgo.com/y.js" in h || "ad_domain" in h) return null
            val real = Regex("[?&]uddg=([^&]+)").find(h)?.groupValues?.get(1)?.let { java.net.URLDecoder.decode(it, "UTF-8") } ?: h
            return real.takeIf { it.startsWith("http") }
        }
    }
}

class ReadPageTool(private val maxChars: Int = 3000) : BrainTool {
    override val name = "read_page"
    override val label = "Read a web page"
    override val description = "Read the text of a web page from its link, to summarize it or answer questions about it."
    override val inputSchema = schema("url" to "The full link, starting with http")
    override fun run(args: JsonObject): ToolResult {
        val url = args.str("url").trim().let { if (it.startsWith("http")) it else "https://$it" }
        require(url.startsWith("http://") || url.startsWith("https://")) { "only web links" }
        val html = Http.get(url, maxBytes = 1_500_000)
        val title = Regex("(?is)<title[^>]*>(.*?)</title>").find(html)?.groupValues?.get(1)?.let(::stripTags)?.trim().orEmpty()
        val text = readableText(html).take(maxChars)
        return ToolResult("Page \"$title\" ($url):\n$text", listOf(WebSource(title.ifBlank { url }, url)))
    }
}

class WikipediaTool : BrainTool {
    override val name = "wikipedia"
    override val label = "Wikipedia"
    override val description = "Look up facts about a person, place, thing or topic in Wikipedia."
    override val inputSchema = schema("topic" to "The topic")
    override fun run(args: JsonObject): ToolResult {
        val topic = args.str("topic")
        val search = JsonParser.parseString(
            Http.get("https://en.wikipedia.org/w/api.php?action=query&list=search&srlimit=1&format=json&srsearch=${Http.enc(topic)}"),
        ).asJsonObject.getAsJsonObject("query").getAsJsonArray("search")
        val title = search.firstOrNull()?.asJsonObject?.str("title") ?: return ToolResult("Wikipedia has nothing on \"$topic\".")
        val page = JsonParser.parseString(Http.get("https://en.wikipedia.org/api/rest_v1/page/summary/${Http.enc(title.replace(' ', '_'))}")).asJsonObject
        val url = page.getAsJsonObject("content_urls")?.getAsJsonObject("mobile")?.str("page") ?: "https://en.wikipedia.org/wiki/${title.replace(' ', '_')}"
        return ToolResult("Wikipedia, \"$title\": " + page.str("extract"), listOf(WebSource("Wikipedia: $title", url)))
    }
}

class WeatherTool(private val fahrenheit: Boolean = true) : BrainTool {
    override val name = "get_weather"
    override val label = "Weather"
    override val description = "Current weather and today's forecast for a place."
    override val inputSchema = schema("location" to "City name")
    override fun run(args: JsonObject): ToolResult {
        val place = args.str("location")
        val geo = JsonParser.parseString(Http.get("https://geocoding-api.open-meteo.com/v1/search?count=1&name=${Http.enc(place)}"))
            .asJsonObject.getAsJsonArray("results")?.firstOrNull()?.asJsonObject ?: return ToolResult("I couldn't find a place called \"$place\".")
        val unit = if (fahrenheit) "fahrenheit" else "celsius"
        val body = Http.get(
            "https://api.open-meteo.com/v1/forecast?latitude=${geo.str("latitude")}&longitude=${geo.str("longitude")}" +
                "&current=temperature_2m,weather_code&daily=temperature_2m_max,temperature_2m_min,precipitation_probability_max" +
                "&temperature_unit=$unit&forecast_days=1&timezone=auto",
        )
        return ToolResult(describeWeather(geo.str("name"), body, if (fahrenheit) "°F" else "°C"))
    }

    companion object {
        fun describeWeather(name: String, body: String, unit: String): String {
            val o = JsonParser.parseString(body).asJsonObject
            val now = o.getAsJsonObject("current")
            val day = o.getAsJsonObject("daily")
            fun d(k: String) = day.getAsJsonArray(k)[0].asDouble.let { Math.round(it) }
            val sky = when (now.get("weather_code").asInt) {
                0 -> "clear"; 1, 2 -> "partly cloudy"; 3 -> "overcast"; 45, 48 -> "foggy"; in 51..57 -> "drizzle"
                in 61..67 -> "rain"; in 71..77 -> "snow"; in 80..82 -> "rain showers"; in 85..86 -> "snow showers"
                in 95..99 -> "thunderstorms"; else -> "mixed"
            }
            return "$name now: ${Math.round(now.get("temperature_2m").asDouble)}$unit, $sky. Today: high ${d("temperature_2m_max")}$unit, " +
                "low ${d("temperature_2m_min")}$unit, ${d("precipitation_probability_max")}% chance of rain."
        }
    }
}

class CurrencyTool : BrainTool {
    override val name = "convert_currency"
    override val label = "Exchange rates"
    override val description = "Convert an amount between currencies using today's rates."
    override val inputSchema = schema("amount" to "Number", "from" to "3-letter code like USD", "to" to "3-letter code like EUR")
    override fun run(args: JsonObject): ToolResult {
        val from = args.str("from").uppercase().take(3); val to = args.str("to").uppercase().take(3)
        val amount = args.str("amount").filter { it.isDigit() || it == '.' }.toDoubleOrNull() ?: 1.0
        val body = JsonParser.parseString(Http.get("https://api.frankfurter.dev/v1/latest?from=$from&to=$to")).asJsonObject
        val rate = body.getAsJsonObject("rates").get(to).asDouble
        return ToolResult("${Calc.format(amount)} $from = ${"%.2f".format(Locale.US, amount * rate)} $to (rate ${"%.4f".format(Locale.US, rate)}, ${body.str("date")}).")
    }
}

// ==================================================================== text helpers

fun stripTags(s: String): String =
    s.replace(Regex("<[^>]+>"), "").replace("&amp;", "&").replace("&quot;", "\"").replace("&#39;", "'")
        .replace("&lt;", "<").replace("&gt;", ">").replace("&nbsp;", " ").trim()

/** The readable words of a page: no scripts, styles, menus or markup. */
fun readableText(html: String): String =
    html.replace(Regex("(?is)<(script|style|noscript|svg|nav|footer|header|form)[^>]*>.*?</\\1>"), " ")
        .replace(Regex("(?i)<br\\s*/?>|</p>|</h[1-6]>|</li>|</title>|</div>"), "\n")
        .let(::stripTags)
        .replace(Regex("[ \\t]+"), " ")
        .replace(Regex("\\s*\\n\\s*"), "\n")
        .replace(Regex("\\n{3,}"), "\n\n")
        .trim()
