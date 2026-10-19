import app.murmur.brain.BrainPrompts
import com.google.ai.edge.litertlm.*

/** Thinking on vs off on hard questions. -PmainClass=ThinkLabKt --args="<model>" */
@OptIn(ExperimentalApi::class)
fun main(args: Array<String>) {
    ExperimentalFlags.enableSpeculativeDecoding = true
    val e = Engine(EngineConfig(modelPath = args[0], backend = Backend.CPU(), maxNumTokens = 4096, cacheDir = System.getProperty("java.io.tmpdir"))).also { it.initialize() }
    val qs = listOf(
        "I have a dentist appointment at 3:15, it takes 25 minutes to drive there and I need to pick up my kid from school at 2:40, which is 10 minutes away from home and 20 minutes from the dentist. When should I leave home?",
        "A shirt costs \$40 after a 20% discount. What was the original price?",
        "My friend cancelled on me three times this month and now asks to hang out Saturday. How should I reply without being rude but making the point?",
        "Should I buy a used car for \$9,000 cash or a new one at \$350 a month for 5 years? I drive 8,000 miles a year. Give me a recommendation.",
        "If it's 9pm in Chicago, what time is it in Tokyo?",
    )
    for (think in listOf(false, true)) {
        println("=== thinking=$think")
        for (q in qs) {
            val t0 = System.nanoTime()
            val m = e.createConversation(ConversationConfig(
                systemInstruction = Contents.of(if (think) BrainPrompts.CHAT_DEEP else BrainPrompts.CHAT_GENERAL),
                samplerConfig = SamplerConfig(topK = 40, topP = 0.9, temperature = 0.4, seed = 7), maxOutputToken = 900,
                thinkingConfig = if (think) ThinkingConfig(true, 600) else null,
            )).use { it.sendMessage(q) }
            val text = m.contents.contents.filterIsInstance<Content.Text>().joinToString("") { it.text }
            val s = (System.nanoTime() - t0) / 1e9
            println("Q: ${q.take(60)}…  [${"%.0f".format(s)} s] channels=${m.channels.mapValues { it.value.length }}")
            println("   A: " + BrainPrompts.clean(text).replace("\n", " ").take(400))
            m.channels.values.firstOrNull()?.let { println("   T: " + it.replace("\n", " ").take(200)) }
        }
    }
    e.close()
}
