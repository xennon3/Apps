import app.murmur.brain.BrainPrompts
import app.murmur.brain.tools.*
import com.google.ai.edge.litertlm.*
import com.google.gson.JsonObject

/** Does the Brain dodge opinions? Old prompts vs new. -PmainClass=OpinionLabKt --args="<model>" */
@OptIn(ExperimentalApi::class)
fun main(args: Array<String>) {
    ExperimentalFlags.enableSpeculativeDecoding = true
    val e = Engine(EngineConfig(modelPath = args[0], backend = Backend.CPU(), maxNumTokens = 4096, cacheDir = System.getProperty("java.io.tmpdir"))).also { it.initialize() }
    fun ask(system: String, q: String, examples: List<Pair<String, String>>, tools: Toolbox? = null): String =
        e.createConversation(ConversationConfig(
            systemInstruction = Contents.of(system),
            initialMessages = examples.flatMap { (a, b) -> listOf(Message.user(a), Message.model(b)) },
            samplerConfig = SamplerConfig(topK = 40, topP = 0.9, temperature = 0.6, seed = 7), maxOutputToken = 300,
            tools = tools?.providers() ?: emptyList(), automaticToolCalling = tools != null,
        )).use { BrainPrompts.clean(it.sendMessage(q).contents.contents.filterIsInstance<Content.Text>().joinToString("") { c -> c.text }) }

    val cars = object : BrainTool {
        override val name = "web_search"; override val label = "Web search"
        override val description = "Search the web for recent or specific information: news, scores, opening hours, prices, events, products."
        override val inputSchema = schema("query" to "What to search for")
        override fun run(args: JsonObject) = ToolResult("Web results for \"${args.str("query")}\":\n" +
            "1. 10 Best Used Cars Under \$5,000 (caranddriver.com): 2008-2011 Toyota Corolla, 2007-2011 Honda Civic, 2009-2012 Mazda3, 2008-2012 Honda Accord, 2005-2010 Toyota Camry, 2010-2013 Ford Fusion.\n" +
            "2. Best Used Cars on a \$5k Budget (consumerreports.org): Look for Toyota and Honda sedans with service records; avoid early-2010s CVT Nissans.\n" +
            "3. The Best Used Cars Under \$5000 (kbb.com): Toyota Corolla tops reliability; Honda Fit is the best small hatchback; Mazda3 is the most fun to drive.")
    }
    val oldGeneral = "You are Murmur, a friendly assistant inside a texting app, running privately on the user's phone. Keep answers short and plain, a few sentences at most."
    val oldTools = "You are Murmur, a friendly assistant inside a texting app on the user's phone. Most questions need no tool: just answer. Use a tool only when it helps, and never talk to the user about tools. For any math, use calculate. You don't know anything recent: for news, sports results, prices, opening hours, events or anything that changes, use web_search. For facts about the world you aren't sure of, look them up instead of guessing. When you used the web, say where the answer came from. Keep answers short."
    val qs = listOf("what's the best season of the year?", "which is better, iPhone or Android?", "should I get a cat or a dog?",
        "what movie should I watch tonight?", "is pineapple on pizza good?", "I need your personal opinion: what's the best car brand?")
    for (new in listOf(false, true)) {
        println("=== ${if (new) "NEW" else "OLD"} prompts")
        var dodged = 0
        val carQ = "with 5000$ to buy a used car, which car would you purchase"
        val carA = ask(if (new) BrainPrompts.toolsSystem(true) else oldTools, carQ, if (new) BrainPrompts.OPINION_EXAMPLES else emptyList(), Toolbox(listOf(cars, CalculateTool())))
        if (BrainPrompts.isRefusal(carA)) dodged++
        println("${if (BrainPrompts.isRefusal(carA)) "DODGE" else "ok   "} $carQ -> ${carA.replace("\n", " ").take(260)}")
        for (q in qs) {
            val a = ask(if (new) BrainPrompts.CHAT_GENERAL else oldGeneral, q, if (new) BrainPrompts.OPINION_EXAMPLES else emptyList())
            if (BrainPrompts.isRefusal(a)) dodged++
            println("${if (BrainPrompts.isRefusal(a)) "DODGE" else "ok   "} $q -> ${a.replace("\n", " ").take(200)}")
        }
        println("dodged ${dodged}/${qs.size + 1}")
    }
    e.close()
}
