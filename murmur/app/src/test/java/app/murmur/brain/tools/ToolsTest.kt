package app.murmur.brain.tools

import app.murmur.brain.BrainPrompts
import app.murmur.brain.Skills
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ToolsTest {

    // Shape documented by Brave Search's web search API.
    @Test fun readsBraveResults() {
        val body = """{"type":"search","web":{"results":[
            {"title":"<strong>Target</strong> Store Hours","url":"https://www.target.com/store-locator","description":"Most stores open 8 a.m. to 10 p.m.","age":"2 days ago","extra_snippets":["Holiday hours may vary."]},
            {"title":"Target near me","url":"https://maps.example.com/target","description":"Find a store &amp; hours."}]}}"""
        val r = WebSearchTool.braveResults("target hours", body)
        assertTrue(r.text, r.text.startsWith("Web results for \"target hours\":\n1. Target Store Hours (target.com): Most stores open 8 a.m. to 10 p.m. (2 days ago) Holiday hours may vary."))
        assertTrue(r.text.contains("2. Target near me (maps.example.com): Find a store & hours."))
        assertEquals(listOf("https://www.target.com/store-locator", "https://maps.example.com/target"), r.sources.map { it.url })
        assertEquals("No web results for \"x\".", WebSearchTool.braveResults("x", """{"web":{"results":[]}}""").text)
    }

    // Shape of DuckDuckGo's no-JavaScript page: an ad, then results with redirect links.
    @Test fun readsDuckDuckGoResults() {
        val html = """
            <div class="result results_links result--ad"><h2 class="result__title"><a rel="nofollow" class="result__a" href="https://duckduckgo.com/y.js?ad_domain=shop.com&amp;u3=x">Buy now</a></h2>
            <a class="result__snippet" href="x">Ad text</a></div>
            <div class="result results_links results_links_deep web-result "><h2 class="result__title">
            <a rel="nofollow" class="result__a" href="//duckduckgo.com/l/?uddg=https%3A%2F%2Fwww.target.com%2Fstore%2Dlocator&amp;rut=abc"><b>Target</b> Store Locator</a></h2>
            <a class="result__snippet" href="//duckduckgo.com/l/?uddg=x">Find <b>Target</b> store hours &amp; directions.</a></div>
            <div class="result results_links web-result "><h2 class="result__title">
            <a rel="nofollow" class="result__a" href="https://maps.example.com/target">Target near me</a></h2>
            <a class="result__snippet" href="https://maps.example.com/target">Open until 10 p.m.</a></div>"""
        val r = WebSearchTool.duckResults("target hours", html)
        assertEquals(listOf("https://www.target.com/store-locator", "https://maps.example.com/target"), r.sources.map { it.url })
        assertTrue(r.text, r.text.contains("1. Target Store Locator (target.com): Find Target store hours & directions."))
        assertTrue(r.text, r.text.contains("2. Target near me (maps.example.com): Open until 10 p.m."))
        assertEquals("No web results for \"zz\".", WebSearchTool.duckResults("zz", "<html>No results.</html>").text)
    }

    // Shape documented by Tavily's search API.
    @Test fun readsTavilyResults() {
        val body = """{"query":"bears score","answer":"The Bears won 24-17.","results":[
            {"title":"Bears beat Lions","url":"https://www.espn.com/nfl/game","content":"Chicago won 24-17 on Sunday.","score":0.9}]}"""
        val r = WebSearchTool.tavilyResults("bears score", body)
        assertTrue(r.text, r.text.startsWith("Quick answer: The Bears won 24-17.\nWeb results for \"bears score\":\n1. Bears beat Lions (espn.com): Chicago won 24-17 on Sunday."))
        assertEquals(1, r.sources.size)
    }

    // A real Open-Meteo response (trimmed).
    @Test fun describesWeather() {
        val body = """{"current":{"time":"2026-10-18T14:00","temperature_2m":54.3,"weather_code":61},
            "daily":{"time":["2026-10-18"],"temperature_2m_max":[58.6],"temperature_2m_min":[46.9],"precipitation_probability_max":[80]}}"""
        assertEquals(
            "Chicago now: 54°F, rain. Today: high 59°F, low 47°F, 80% chance of rain.",
            WeatherTool.describeWeather("Chicago", body, "°F"),
        )
    }

    // DeepWiki's actual reply format: a server-sent event.
    @Test fun readsMcpEventStream() {
        val body = "event: message\ndata: {\"jsonrpc\":\"2.0\",\"id\":3,\"result\":{\"tools\":[{\"name\":\"ask_wiki_question\"}]}}\n\n"
        val reply = McpServer.parseReply(body, 3, "text/event-stream")!!
        assertEquals("ask_wiki_question", reply.getAsJsonObject("result").getAsJsonArray("tools")[0].asJsonObject.get("name").asString)
        val json = McpServer.parseReply("{\"jsonrpc\":\"2.0\",\"id\":1,\"result\":{}}", 1, "application/json")!!
        assertTrue(json.has("result"))
    }

    @Test fun readsPages() {
        val html = "<html><head><title>Hi</title><script>var x=1</script></head><body><nav>Menu</nav><h1>Big news</h1><p>Line one.</p><p>Line &amp; two.</p></body></html>"
        assertEquals("Hi\nBig news\nLine one.\nLine & two.", readableText(html))
    }

    @Test fun matchesSkills() {
        assertEquals(listOf("split"), Skills.match("split \$137 four ways with tip", Skills.BUILT_IN).map { it.id })
        assertEquals(listOf("translate"), Skills.match("how do you say thank you in japanese", Skills.BUILT_IN).map { it.id })
        assertEquals(listOf("polish"), Skills.match("make this sound more professional: cant come tmrw", Skills.BUILT_IN).map { it.id })
        assertTrue(Skills.match("tell me a joke", Skills.BUILT_IN).isEmpty())
    }

    @Test fun dropsNamePrefix() {
        assertEquals("The tower is 330 m tall.", BrainPrompts.clean("Murmur: The tower is 330 m tall."))
        assertEquals("Hi!", BrainPrompts.clean("**Murmur:** Hi!"))
    }
}
