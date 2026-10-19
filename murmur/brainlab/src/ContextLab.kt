import com.google.ai.edge.litertlm.*
import java.io.File

/** Memory cost of a bigger workspace. -PmainClass=ContextLabKt --args="<model> <tokens>" (one size per run, fresh process) */
@OptIn(ExperimentalApi::class)
fun main(args: Array<String>) {
    fun rssMb() = File("/proc/self/status").readLines().first { it.startsWith("VmRSS") }.filter { it.isDigit() }.toLong() / 1024
    val info = ModelInfo.from(args[0]); println("model max context: ${info.maxContextTokens()}"); info.close()
    val before = rssMb()
    val n = args[1].toInt()
    val e = Engine(EngineConfig(modelPath = args[0], backend = Backend.CPU(), maxNumTokens = n, cacheDir = System.getProperty("java.io.tmpdir"))).also { it.initialize() }
    val long = (1..n / 40).joinToString(" ") { "Message $it: the meeting moved to room ${it % 9} at ${it % 12 + 1} pm." }
    val t0 = System.nanoTime()
    val a = e.createConversation(ConversationConfig(samplerConfig = SamplerConfig(1, 1.0, 0.0, 7), maxOutputToken = 30)).use {
        it.sendMessage("$long\n\nWhich room was the meeting in, in message 3?").contents.contents.filterIsInstance<Content.Text>().joinToString("") { c -> c.text }
    }
    println("maxNumTokens=$n: +${rssMb() - before} MB resident, prompt of ~${long.length / 4} tokens answered in ${"%.0f".format((System.nanoTime() - t0) / 1e9)} s: ${a.trim().take(80)}")
    e.close()
}
