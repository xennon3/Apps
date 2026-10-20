import app.murmur.brain.AmbientMind
import app.murmur.brain.AmbientMind.Situation
import app.murmur.brain.BrainPrompts
import com.google.ai.edge.litertlm.*

/** Ambient mode's thinking on real-life moments. -PmainClass=AmbientLabKt --args="<model>" */
@OptIn(ExperimentalApi::class)
fun main(args: Array<String>) {
    ExperimentalFlags.enableSpeculativeDecoding = true
    val e = Engine(EngineConfig(modelPath = args[0], backend = Backend.CPU(), maxNumTokens = 4096, cacheDir = System.getProperty("java.io.tmpdir"))).also { it.initialize() }
    val cases = listOf(
        "battery: should notify" to Situation("Thursday 4:50 PM", listOf("Battery 18%, not charging, using about 11% an hour", "At: work (since 8:55 AM)", "Moving: still", "Usually plugs in around 10 PM"), listOf("Battery dropped below 20%"), emptyList(), emptyList(), emptyList()),
        "quiet evening: nothing or journal" to Situation("Wednesday 8:10 PM", listOf("Battery 64%, not charging", "At: home (since 6:20 PM)", "Moving: still", "Screen: on", "Headphones: connected"), listOf("Headphones connected"), emptyList(), emptyList(), listOf("6:20 PM arrived home")),
        "text while driving: plan, don't send" to Situation("Monday 6:05 PM", listOf("Moving: in a vehicle, 38 mph", "Battery 55%", "Screen: off"), listOf("New text from Mom: \"call me when you can, it's about Sunday\""), emptyList(), emptyList(), listOf("5:40 PM left work")),
        "goal: pills at 9pm" to Situation("Tuesday 9:01 PM", listOf("At: home", "Moving: still", "Battery 40%, charging"), listOf("Regular check-in"), listOf("Remind me to take my pills at 9 PM every day"), emptyList(), emptyList()),
        "hot phone" to Situation("Sunday 1:30 PM", listOf("Battery 72%, charging fast, 45°C (hot)", "Light: full daylight", "Moving: still"), listOf("Phone temperature rose above 43°C"), emptyList(), emptyList(), emptyList()),
        "late night, ringer on" to Situation("Friday 11:48 PM", listOf("At: home", "Moving: still", "Screen: off for 40 minutes", "Ringer: normal", "Light: dark"), listOf("Regular check-in"), listOf("Keep my nights quiet"), emptyList(), emptyList()),
    )
    for ((label, sit) in cases) {
        val t0 = System.nanoTime()
        val a = e.createConversation(ConversationConfig(
            systemInstruction = Contents.of(AmbientMind.SYSTEM),
            initialMessages = AmbientMind.EXAMPLES.flatMap { (q, r) -> listOf(Message.user(q), Message.model(r)) },
            samplerConfig = SamplerConfig(40, 0.9, 0.3, 7), maxOutputToken = 220,
        )).use { it.sendMessage(BrainPrompts.engineSafe(AmbientMind.situation(sit))).contents.contents.filterIsInstance<Content.Text>().joinToString("") { c -> c.text } }
        val t = AmbientMind.parse(a)
        println("== $label [${"%.0f".format((System.nanoTime() - t0) / 1e9)} s]\n   observe: ${t.observe}\n   thought: ${t.thought}\n   plans: ${t.plans}\n   acts: ${t.acts}")
    }
    e.close()
}
