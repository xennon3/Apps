import app.murmur.brain.BrainPrompts
import com.google.ai.edge.litertlm.*
import java.io.File

/** Brain-tab picture questions. -PmainClass=PictureLabKt --args="<model> <dir>" */
@OptIn(ExperimentalApi::class)
fun main(args: Array<String>) {
    ExperimentalFlags.enableSpeculativeDecoding = true
    val e = Engine(EngineConfig(modelPath = args[0], backend = Backend.CPU(), visionBackend = Backend.CPU(), maxNumTokens = 8192, cacheDir = System.getProperty("java.io.tmpdir"))).also { it.initialize() }
    for ((f, q, think) in listOf(
        Triple("receipt.jpg", "what's the total if I add a 20% tip on the total?", true),
        Triple("receipt.jpg", "what was the most expensive thing we ordered?", false),
        Triple("sign.jpg", "what does this sign say? can I park here for 10 minutes?", false),
        Triple("worksheet.jpg", "check my kid's answers", true),
    )) {
        val t0 = System.nanoTime()
        val a = e.createConversation(ConversationConfig(
            systemInstruction = Contents.of(BrainPrompts.CHAT_PICTURE),
            initialMessages = BrainPrompts.OPINION_EXAMPLES.flatMap { (x, y) -> listOf(Message.user(x), Message.model(y)) },
            samplerConfig = SamplerConfig(40, 0.9, 0.4, 7), maxOutputToken = 700 + if (think) 600 else 0,
            thinkingConfig = if (think) ThinkingConfig(true, 600) else null,
        )).use { it.sendMessage(Contents.of(Content.ImageBytes(File(args[1], f).readBytes()), Content.Text(q))).contents.contents.filterIsInstance<Content.Text>().joinToString("") { c -> c.text } }
        println("$f | $q | think=$think [${"%.0f".format((System.nanoTime() - t0) / 1e9)} s]\n   ${BrainPrompts.clean(a).replace("\n", " ").take(420)}")
    }
    e.close()
}
