package app.murmur.brain.tools

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.net.HttpURLConnection
import java.net.URI
import java.util.concurrent.atomic.AtomicInteger

/**
 * A minimal MCP client for remote servers (the Streamable HTTP transport): connect, list
 * tools, call them. Servers that run as local programs (stdio) can't run on Android, so
 * only servers reachable by URL work. Plain Kotlin, tested against a live public server.
 */
class McpServer(val url: String, private val token: String? = null) {
    private var session: String? = null
    private val ids = AtomicInteger(1)
    var serverName: String = URI(url).host ?: url
        private set
    var instructions: String = ""
        private set

    /** Sends one JSON-RPC message; returns the matching result (or null for a notification). */
    @Synchronized
    private fun rpc(method: String, params: JsonObject = JsonObject(), notification: Boolean = false): JsonObject? {
        val id = ids.getAndIncrement()
        val msg = JsonObject().apply {
            addProperty("jsonrpc", "2.0")
            if (!notification) addProperty("id", id)
            addProperty("method", method)
            add("params", params)
        }
        val c = URI(url).toURL().openConnection() as HttpURLConnection
        c.requestMethod = "POST"
        c.doOutput = true
        c.connectTimeout = 15_000; c.readTimeout = 60_000
        c.setRequestProperty("Content-Type", "application/json")
        c.setRequestProperty("Accept", "application/json, text/event-stream")
        c.setRequestProperty("User-Agent", Http.AGENT)
        c.setRequestProperty("MCP-Protocol-Version", PROTOCOL)
        session?.let { c.setRequestProperty("Mcp-Session-Id", it) }
        token?.takeIf { it.isNotBlank() }?.let { c.setRequestProperty("Authorization", "Bearer $it") }
        c.outputStream.use { it.write(msg.toString().toByteArray()) }

        val code = c.responseCode
        c.getHeaderField("Mcp-Session-Id")?.let { session = it }
        if (code == 401 || code == 403) throw IllegalStateException("The server wants a sign-in (HTTP $code). Add a token, or use a server that doesn't need one.")
        if (code !in 200..299) throw IllegalStateException("HTTP $code")
        if (notification) return null
        val body = c.inputStream.use { String(Http.readUpTo(it, 2_000_000), Charsets.UTF_8) }
        val reply = parseReply(body, id, c.contentType.orEmpty()) ?: throw IllegalStateException("No reply from the server")
        reply.getAsJsonObject("error")?.let { throw IllegalStateException(it.str("message").ifBlank { "Server error" }) }
        return reply.getAsJsonObject("result")
    }

    fun connect(): List<McpTool> {
        val result = rpc("initialize", JsonObject().apply {
            addProperty("protocolVersion", PROTOCOL)
            add("capabilities", JsonObject())
            add("clientInfo", JsonObject().apply { addProperty("name", "Murmur"); addProperty("version", "1.6") })
        })
        result?.getAsJsonObject("serverInfo")?.str("name")?.takeIf { it.isNotBlank() }?.let { serverName = it }
        instructions = result?.str("instructions").orEmpty()
        runCatching { rpc("notifications/initialized", notification = true) }
        return listTools()
    }

    fun listTools(): List<McpTool> =
        (rpc("tools/list")?.getAsJsonArray("tools") ?: JsonArray()).map { t ->
            val o = t.asJsonObject
            McpTool(this, o.str("name"), o.str("description"), o.getAsJsonObject("inputSchema") ?: schema())
        }

    fun call(name: String, args: JsonObject): String {
        val result = rpc("tools/call", JsonObject().apply { addProperty("name", name); add("arguments", args) })
            ?: return "No result."
        val text = (result.getAsJsonArray("content") ?: JsonArray()).mapNotNull { c ->
            val o = c.asJsonObject
            if (o.str("type") == "text") o.str("text") else null
        }.joinToString("\n")
        return if (result.get("isError")?.asBoolean == true) "The tool reported an error: $text" else text
    }

    companion object {
        const val PROTOCOL = "2025-06-18"

        /** Replies come as plain JSON or as a server-sent event stream; find the one for our id. */
        fun parseReply(body: String, id: Int, contentType: String): JsonObject? {
            val candidates = if (contentType.contains("event-stream") || body.trimStart().startsWith("event:") || body.trimStart().startsWith("data:")) {
                body.split(Regex("\\r?\\n\\r?\\n")).mapNotNull { event ->
                    event.lines().filter { it.startsWith("data:") }.joinToString("\n") { it.removePrefix("data:").trim() }.takeIf { it.isNotBlank() }
                }
            } else listOf(body)
            return candidates.mapNotNull { runCatching { JsonParser.parseString(it).asJsonObject }.getOrNull() }
                .firstOrNull { it.get("id")?.asInt == id } ?: candidates.lastOrNull()?.let { runCatching { JsonParser.parseString(it).asJsonObject }.getOrNull() }
        }
    }
}

/** One tool from a remote MCP server, offered to the Brain like any built-in tool. */
class McpTool(private val server: McpServer, private val toolName: String, desc: String, override val inputSchema: JsonObject) : BrainTool {
    // Tool names must be unique and simple for the model.
    override val name = toolName.replace(Regex("[^A-Za-z0-9_]"), "_")
    override val description = desc.lineSequence().take(4).joinToString(" ").take(400)
    override val label = server.serverName
    override fun run(args: JsonObject): ToolResult = ToolResult(server.call(toolName, args).take(3500))
}
