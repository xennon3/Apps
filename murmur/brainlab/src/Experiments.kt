import com.google.ai.edge.litertlm.*
import java.io.File

/**
 * Capability checks for a model file: speculative decoding speed, pictures and meaning search.
 * Usage: gradle run -PmainClass=ExperimentsKt --args="<llm.litertlm> <embedding.litertlm> <dir with flyer.jpg, scamshot.jpg>"
 */
@OptIn(ExperimentalApi::class)
fun main(args: Array<String>) {
    val (llm, embed, dir) = args
    val tmp = System.getProperty("java.io.tmpdir")
    val info = ModelInfo.from(llm)
    println("=== Model: ${File(llm).name}")
    println("modalities: ${info.inputModalities().let { "text=${it.text} vision=${it.vision} audio=${it.audio}" }}")
    if (info is ModelInfo.Llm) println("speculative decoding support: ${info.hasSpeculativeDecodingSupport()}")
    info.close()

    val prompt = "Write a friendly 120-word note inviting a neighbor to a weekend barbecue."
    for (spec in listOf(false, true)) {
        ExperimentalFlags.enableSpeculativeDecoding = spec
        val e = Engine(EngineConfig(modelPath = llm, backend = Backend.CPU(), maxNumTokens = 2048, cacheDir = tmp)).also { it.initialize() }
        e.createConversation(ConversationConfig(samplerConfig = SamplerConfig(topK = 1, topP = 1.0, temperature = 0.0, seed = 7), maxOutputToken = 200)).use { c ->
            c.sendMessage("hi") // warm
        }
        e.createConversation(ConversationConfig(samplerConfig = SamplerConfig(topK = 1, topP = 1.0, temperature = 0.0, seed = 7), maxOutputToken = 200)).use { c ->
            val t0 = System.nanoTime()
            val out = c.sendMessage(prompt).contents.contents.filterIsInstance<Content.Text>().joinToString("") { it.text }
            val s = (System.nanoTime() - t0) / 1e9
            val words = out.split(Regex("\\s+")).size
            println("speculative=$spec: ${"%.1f".format(s)} s for $words words (${"%.1f".format(words / s)} words/s)")
        }
        e.close()
    }
    ExperimentalFlags.enableSpeculativeDecoding = null

    println("=== Pictures")
    val e = Engine(EngineConfig(modelPath = llm, backend = Backend.CPU(), visionBackend = Backend.CPU(), maxNumTokens = 4096, cacheDir = tmp)).also { it.initialize() }
    fun look(file: String, question: String): String {
        val t0 = System.nanoTime()
        val a = e.createConversation(ConversationConfig(samplerConfig = SamplerConfig(topK = 40, topP = 0.9, temperature = 0.1, seed = 7), maxOutputToken = 120)).use { c ->
            c.sendMessage(Contents.of(Content.ImageBytes(File(dir, file).readBytes()), Content.Text(question))).contents.contents
                .filterIsInstance<Content.Text>().joinToString("") { it.text }
        }
        return a.trim().replace("\n", " ") + "  [${"%.1f".format((System.nanoTime() - t0) / 1e9)} s]"
    }
    println("flyer event: " + look("flyer.jpg", "If this picture shows a plan or event, answer EVENT: title | when | where. Otherwise answer NONE."))
    println("flyer describe: " + look("flyer.jpg", "Describe this picture in one sentence."))
    println("scam screenshot: " + look("scamshot.jpg", "Is this text in the picture a scam? Answer SCAM: reason or SAFE: reason."))
    e.close()

    println("=== Meaning search")
    val em = EmbeddingEngine(EmbeddingEngineConfig(modelPath = embed, backend = Backend.CPU(), cacheDir = tmp)).also { it.initialize() }
    val texts = listOf(
        "Jake: just bought a yacht lol, taking it out on the lake sunday",
        "Mom: don't forget grandma's birthday is on the 12th",
        "Sam: the wifi password is bluefish42",
        "Landlord: rent is going up to 1450 starting in January",
        "Priya: I'm vegetarian btw, so no meat dishes for me",
        "Dentist: your cleaning is booked for Tue Oct 21 at 9:30",
        "Jake: want to grab tacos after work?",
        "Work: the server outage is fixed, postmortem on Friday",
        "Mia: my flight lands at 6:40pm, terminal B",
        "Dad: car needs new brakes, mechanic quoted 380",
    )
    fun vec(s: String) = em.computeEmbedding(listOf(InputData.Text(s))).embedding
    val t0 = System.nanoTime()
    val docs = texts.map { vec("title: none | text: $it") }
    println("indexed ${texts.size} texts in ${(System.nanoTime() - t0) / 1_000_000 / texts.size} ms each, ${docs[0].size} numbers each")
    fun cos(a: FloatArray, b: FloatArray): Float { var d = 0f; var x = 0f; var y = 0f; for (i in a.indices) { d += a[i] * b[i]; x += a[i] * a[i]; y += b[i] * b[i] }; return d / kotlin.math.sqrt(x * y) }
    val queries = listOf("when did Jake mention the boat" to 0, "what's the internet password" to 2, "how much will my rent be" to 3,
        "who doesn't eat meat" to 4, "when is my teeth appointment" to 5, "when does the plane arrive" to 8, "how much to fix the car" to 9)
    var ok = 0
    for ((q, want) in queries) {
        val qv = vec("task: search result | query: $q")
        val best = docs.indices.maxBy { cos(qv, docs[it]) }
        if (best == want) ok++
        println("${if (best == want) "ok" else "XX"} $q -> ${texts[best]}")
    }
    println("meaning search $ok/${queries.size}")
    em.close()
}
