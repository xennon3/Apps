package app.murmur.brain.tools

import com.google.ai.edge.litertlm.OpenApiTool
import com.google.ai.edge.litertlm.ToolProvider
import com.google.ai.edge.litertlm.tool
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.util.concurrent.CopyOnWriteArrayList

/**
 * The tools offered for one question, wired into LiteRT-LM's tool calling. Records what ran
 * so the answer can say "Used: Web search" and link its sources.
 */
class Toolbox(val tools: List<BrainTool>, private val onStart: (String) -> Unit = {}) {
    /** What ran, e.g. "Web search: target hours". */
    val log = CopyOnWriteArrayList<String>()
    val labels = CopyOnWriteArrayList<String>()
    val sources = CopyOnWriteArrayList<WebSource>()
    private val results = CopyOnWriteArrayList<String>()

    /** What the tools returned, for a second try that shouldn't search again. */
    fun lastResults(): String = results.joinToString("\n").take(2500)

    fun providers(): List<ToolProvider> = tools.map { t ->
        tool(object : OpenApiTool {
            override fun getToolDescriptionJsonString(): String = JsonObject().apply {
                addProperty("name", t.name)
                addProperty("description", t.description)
                add("parameters", t.inputSchema)
            }.toString()

            override fun execute(paramsJsonString: String): String {
                val args = runCatching { JsonParser.parseString(paramsJsonString).asJsonObject }.getOrDefault(JsonObject())
                onStart(t.label)
                val result = runCatching { t.run(args) }.getOrElse {
                    // Tell the model plainly; it then says it couldn't, instead of inventing an answer.
                    ToolResult("${t.label} didn't work just now (${it.message ?: it.javaClass.simpleName}). Tell the user you couldn't look it up.")
                }
                if (t.label !in labels) labels += t.label
                log += t.label + ": " + args.entrySet().joinToString(", ") { it.value.toString().trim('"') }
                result.sources.forEach { s -> if (sources.none { it.url == s.url }) sources += s }
                results += result.text
                return result.text
            }
        })
    }
}
