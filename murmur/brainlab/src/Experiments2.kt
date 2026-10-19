import com.google.ai.edge.litertlm.*
import java.io.File

/** Voice notes and the bigger model. Usage: -PmainClass=Experiments2Kt --args="<e2b> <e4b> <dir with voice16.wav>" */
@OptIn(ExperimentalApi::class)
fun main(args: Array<String>) {
    val (e2b, e4b, dir) = args
    val tmp = System.getProperty("java.io.tmpdir")
    ExperimentalFlags.enableSpeculativeDecoding = true
    fun cfg(t: Double = 0.1, max: Int = 160) = ConversationConfig(samplerConfig = SamplerConfig(topK = 40, topP = 0.9, temperature = t, seed = 7), maxOutputToken = max)
    fun text(m: Message) = m.contents.contents.filterIsInstance<Content.Text>().joinToString("") { it.text }.trim().replace("\n", " ")
    fun timed(block: () -> String): String { val t0 = System.nanoTime(); val r = block(); return "$r  [${"%.1f".format((System.nanoTime() - t0) / 1e9)} s]" }

    println("=== Voice note (8 kHz phone-quality speech)")
    Engine(EngineConfig(modelPath = e2b, backend = Backend.CPU(), audioBackend = Backend.CPU(), maxNumTokens = 4096, cacheDir = tmp)).also { it.initialize() }.use { e ->
        val wav = File(dir, "voice16.wav").readBytes()
        println("transcript: " + timed { e.createConversation(cfg()).use { text(it.sendMessage(Contents.of(Content.AudioBytes(wav), Content.Text("Write down exactly what is said in this voice message.")))) } })
        println("gist: " + timed { e.createConversation(cfg()).use { text(it.sendMessage(Contents.of(Content.AudioBytes(wav), Content.Text("In one short sentence, what does the sender want?")))) } })
    }

    println("=== Big Brain (E4B) vs E2B on harder questions")
    val hard = listOf(
        "My friend texted: 'guess I'll just go by myself then, as usual'. What is she probably feeling, and what's a kind reply? Two sentences.",
        "I have \$60 for groceries for 3 days for two people. Give a short plan.",
        "Translate to Japanese: 'Thanks for dinner, see you next week!'",
        "A bat and a ball cost \$1.10 in total. The bat costs \$1.00 more than the ball. How much is the ball? Answer with the amount and one line why.",
    )
    for (path in listOf(e2b, e4b)) {
        println("--- ${File(path).name}")
        Engine(EngineConfig(modelPath = path, backend = Backend.CPU(), maxNumTokens = 2048, cacheDir = tmp)).also { it.initialize() }.use { e ->
            for (q in hard) println("Q: ${q.take(50)}… -> " + timed { e.createConversation(cfg(0.3, 140)).use { text(it.sendMessage(q)) } })
        }
    }
}
