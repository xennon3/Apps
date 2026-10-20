import app.murmur.brain.BrainPrompts
import app.murmur.brain.tools.*
import com.google.ai.edge.litertlm.*
import com.google.gson.JsonObject
import java.io.File

/** One model through Murmur's real jobs. -PmainClass=BakeOffKt --args="<model.litertlm>" */
@OptIn(ExperimentalApi::class)
fun main(args: Array<String>) {
    val path = args[0]
    val name = File(path).name
    val info = runCatching { ModelInfo.from(path) }.getOrNull()
    val spec = (info as? ModelInfo.Llm)?.hasSpeculativeDecodingSupport() == true
    val mods = info?.inputModalities()
    info?.close()
    ExperimentalFlags.enableSpeculativeDecoding = spec
    val t0 = System.nanoTime()
    val e = Engine(EngineConfig(modelPath = path, backend = Backend.CPU(), maxNumTokens = 4096, cacheDir = System.getProperty("java.io.tmpdir"))).also { it.initialize() }
    println("##### $name  load ${"%.0f".format((System.nanoTime() - t0) / 1e9)} s  vision=${mods?.vision} audio=${mods?.audio} fastWriting=$spec")
    fun ask(system: String, q: String, ex: List<Pair<String, String>> = emptyList(), tools: Toolbox? = null, max: Int = 400): String = runCatching {
        e.createConversation(ConversationConfig(
            systemInstruction = Contents.of(system),
            initialMessages = ex.flatMap { (a, b) -> listOf(Message.user(a), Message.model(b)) },
            samplerConfig = SamplerConfig(40, 0.9, 0.2, 7), maxOutputToken = max,
            tools = tools?.providers() ?: emptyList(), automaticToolCalling = tools != null,
        )).use { BrainPrompts.clean(it.sendMessage(q).contents.contents.filterIsInstance<Content.Text>().joinToString("") { c -> c.text }) }
    }.getOrElse { "ERROR ${it.message}" }

    // speed
    val s0 = System.nanoTime()
    val note = ask("You are a helpful assistant.", "Write a friendly 100-word note inviting a neighbor to a weekend barbecue.", max = 200)
    val secs = (System.nanoTime() - s0) / 1e9
    println("speed: ${"%.1f".format(note.split(Regex("\\s+")).size / secs)} words/s")

    // reasoning (no thinking, same prompt Murmur uses for hard questions)
    val reasoning = listOf(
        "I have a dentist appointment at 3:15, it takes 25 minutes to drive there and I need to pick up my kid from school at 2:40, which is 10 minutes away from home and 20 minutes from the dentist. When should I leave home?" to Regex("2:30"),
        "If it's 9pm in Chicago in July, what time is it in Tokyo?" to Regex("(?i)11(:00)? ?a"),
        "A bat and a ball cost \$1.10 in total. The bat costs \$1.00 more than the ball. How much is the ball?" to Regex("0?\\.05|5 cents"),
        "A shirt costs \$40 after a 20% discount. What was the original price?" to Regex("\\$?50\\b"),
        "Used car \$9,000 cash or a new one at \$350 a month for 5 years: what's the total cost of each?" to Regex("21,?000"),
        "I need to be at work by 8:30, it's a 35 minute drive and I want coffee on the way (10 min stop). When should I leave?" to Regex("7:45"),
    )
    var r = 0
    for ((q, want) in reasoning) { val a = ask(BrainPrompts.CHAT_DEEP, q); val ok = want.containsMatchIn(a); if (ok) r++; println("  ${if (ok) "ok" else "XX"} ${q.take(40)}… -> ${a.replace("\n", " ").take(110)}") }
    println("reasoning $r/${reasoning.size}")

    // opinions
    val ops = listOf("what's the best season of the year?", "which is better, iPhone or Android?", "should I get a cat or a dog?",
        "with 5000$ to buy a used car, which car would you purchase", "is pineapple on pizza good?", "what's your honest take on capitalism vs socialism?")
    var o = 0
    for (q in ops) { val a = ask(BrainPrompts.CHAT_GENERAL, q, BrainPrompts.OPINION_EXAMPLES); val ok = !BrainPrompts.isRefusal(a, q); if (ok) o++; println("  ${if (ok) "ok" else "XX"} $q -> ${a.replace("\n", " ").take(100)}") }
    println("opinions $o/${ops.size}")

    // scams
    val scams = listOf("Your package is on hold. Pay the \$1.99 redelivery fee: usps-help.info" to true, "Running 10 min late, save me a seat!" to false,
        "Congrats! You won a \$500 Walmart gift card, claim now: wmt-prize.top" to true, "Your appointment with Dr. Lee is tomorrow at 9:30. Reply C to confirm" to false,
        "Hi dad, this is my new number, I need money for rent urgently, can you send \$800?" to true, "Your verification code is 552891. Don't share it with anyone." to false,
        "E-ZPass: unpaid toll of \$4.15. Avoid a \$50 late fee: ezpass-pay-now.com" to true, "Hey it's Sam from work, can you cover my shift Sunday?" to false)
    var sc = 0
    for ((m, want) in scams) { val op = BrainPrompts.parseScamAnswer(ask(BrainPrompts.SCAM, BrainPrompts.scamQuestion(m), BrainPrompts.SCAM_EXAMPLES.map { (a, b) -> BrainPrompts.scamQuestion(a) to b }, max = 32)); if (op?.scam == want) sc++ }
    println("scams $sc/${scams.size}")

    // tool choice
    val reader = PhoneReader { t -> "Reading for ${t.key}: fine." }
    val ctl = PhoneController { a, v -> "Done ${a.key} $v." }
    fun box() = Toolbox(listOf(CalculateTool(), TimeTool(), PhoneStatusTool(reader), PhoneActionTool(ctl)))
    val cases = listOf("how fast am I moving" to "Phone", "how's my battery doing" to "Phone", "turn on the flashlight" to "Phone controls",
        "set a timer for 12 minutes" to "Phone controls", "what's 18% tip on \$64.50?" to "Calculator", "what time is it in tokyo" to "Clock",
        "write a haiku about rain" to "none", "how many steps have I walked today" to "Phone")
    var tc = 0
    for ((q, want) in cases) { val b = box(); ask(BrainPrompts.toolsSystem(false), q, tools = b); val got = b.labels.firstOrNull() ?: "none"; if (got == want) tc++ }
    println("tools $tc/${cases.size}")
    println("SUMMARY $name: reasoning $r/${reasoning.size}, opinions $o/${ops.size}, scams $sc/${scams.size}, tools $tc/${cases.size}")
    e.close()
}
