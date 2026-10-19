import com.google.ai.edge.litertlm.*

/** Does LiteRT-LM's benchmark reflect fast writing? -PmainClass=BenchLabKt --args="<model>" */
@OptIn(ExperimentalApi::class)
fun main(args: Array<String>) {
    val tmp = System.getProperty("java.io.tmpdir")
    for (spec in listOf(false, true)) {
        ExperimentalFlags.enableSpeculativeDecoding = spec
        val b = benchmark(args[0], Backend.CPU(), 256, 32, tmp)
        println("benchmark speculative=$spec: decode ${"%.1f".format(b.lastDecodeTokensPerSecond)} tok/s, prefill ${"%.0f".format(b.lastPrefillTokensPerSecond)}")
        val b2 = benchmark(args[0], Backend.CPU(), 256, 128, tmp)
        println("benchmark(128 out) speculative=$spec: decode ${"%.1f".format(b2.lastDecodeTokensPerSecond)} tok/s")
    }
}
