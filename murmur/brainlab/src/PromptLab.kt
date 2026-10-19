import app.murmur.brain.BrainPrompts
import app.murmur.brain.Skills
import app.murmur.brain.tools.*
import com.google.ai.edge.litertlm.*

/**
 * Prompt checks against the real model. Usage: gradle run --args="/path/to/gemma-4-E2B-it.litertlm"
 * Prints each question, the tools the model called, and its answer, with a score for tool choice.
 * Add a case whenever a prompt changes or a phone shows a bad answer.
 */
fun main(args: Array<String>) {
    val e = Engine(EngineConfig(modelPath = args[0], backend = Backend.CPU(), maxNumTokens = 4096, cacheDir = System.getProperty("java.io.tmpdir")))
    e.initialize()
    fun ask(system: String, prompt: String, examples: List<Pair<String, String>> = emptyList(), tools: Toolbox? = null, max: Int = 300): String =
        e.createConversation(ConversationConfig(
            systemInstruction = Contents.of(BrainPrompts.engineSafe(system)),
            initialMessages = examples.flatMap { (q, a) -> listOf(Message.user(BrainPrompts.engineSafe(q)), Message.model(BrainPrompts.engineSafe(a))) },
            samplerConfig = SamplerConfig(topK = 40, topP = 0.9, temperature = 0.2, seed = 7), maxOutputToken = max,
            tools = tools?.providers() ?: emptyList(), automaticToolCalling = tools != null,
        )).use { BrainPrompts.clean(it.sendMessage(BrainPrompts.engineSafe(prompt)).contents.contents.filterIsInstance<Content.Text>().joinToString("") { c -> c.text }) }

    println("=== Tool choice (no network needed: calculator + clock only)")
    val cases = listOf("what's 18% tip on \$64.50?" to "calculate", "split \$137 four ways" to "calculate",
        "what time is it in tokyo" to "current_time", "write a haiku about rain" to "none", "give me 3 dinner ideas" to "none")
    var ok = 0
    for ((q, want) in cases) {
        val box = Toolbox(listOf(CalculateTool(), TimeTool()))
        val a = ask(BrainPrompts.toolsSystem(false), q, tools = box)
        val got = box.log.firstOrNull()?.substringBefore(':')?.let { if (it == "Calculator") "calculate" else "current_time" } ?: "none"
        if (got == want) ok++
        println("${if (got == want) "ok" else "XX"} $q -> $got | ${a.replace("\n", " ").take(140)}")
    }
    println("tool choice $ok/${cases.size}")

    println("=== Extraction prompts")
    for (m in listOf("dinner friday at 7 at Luigi's? 🍝", "I had pizza at 7 yesterday"))
        println("event: $m -> ${ask(BrainPrompts.EVENT, m, BrainPrompts.EVENT_EXAMPLES, max = 40)}")
    for (m in listOf("let my landlord know the sink is fixed", "text mom I'm running late"))
        println("send: $m -> ${ask(BrainPrompts.SEND, m, BrainPrompts.SEND_EXAMPLES, max = 60)}")
    for (m in listOf("can you pick up milk on the way home", "Your order has shipped"))
        println("triage: $m -> ${ask(BrainPrompts.TRIAGE, m, BrainPrompts.TRIAGE_EXAMPLES, max = 4)}")
    println("=== Reply suggestions")
    val chats = listOf(
        listOf(false to "hey are we still on for dinner tonight?"),
        listOf(true to "lol ok", true to "on my way", false to "can you grab ice on the way? we're out"),
        listOf(true to "Hello, I'm interested in the apartment listing.", false to "Great! Would you like to schedule a viewing this week?"),
        listOf(false to "did you finish the report? boss is asking"),
        listOf(true to "omg that's hilarious", false to "wanna come to the game sunday? I have an extra ticket"),
        listOf(false to "I'm so sorry about your grandma. Let me know if you need anything."),
    )
    for (c in chats) {
        val raw = ask(BrainPrompts.REPLIES, BrainPrompts.replyTranscript(c), BrainPrompts.REPLIES_EXAMPLES, max = 60)
        println("${c.last().second} -> ${BrainPrompts.parseReplies(raw, c.filter { it.first }.map { it.second })}")
    }
    println("skills for 'split the bill': ${Skills.match("split the bill", Skills.BUILT_IN).map { it.name }}")
    e.close()
}
