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
        "user's morning: 8-min drive, unfamiliar place, work goal" to Situation("Monday 10:00 AM",
            listOf("Battery 84%, not charging", "At: an unfamiliar place (not home or work)", "Moving: still since 9:52 AM (8 min)", "Ringer: silent", "Screen: off"),
            listOf("Stopped driving at 9:52 AM after 8 min, about 3.1 miles"), listOf("Keep my phone on vibrate while I'm at work"), emptyList(), emptyList(),
            timeline = listOf("9:40-9:44 AM still, 4 min", "9:42 AM unplugged at 84%", "9:44-9:52 AM driving or riding, 8 min, about 3.1 miles", "9:52 AM-now still, 8 min at an unfamiliar place (not home or work)"),
            lastThought = "9:39 AM"),
        "named work, ringer normal: vibrate" to Situation("Monday 10:20 AM",
            listOf("At: work (since 10:18 AM)", "Moving: still since 9:52 AM (28 min)", "Ringer: normal"),
            listOf("The user named the current place \"work\""), listOf("Keep my phone on vibrate while I'm at work"), emptyList(), emptyList(),
            timeline = listOf("10:00 AM-now still, 20 min at work"), lastThought = "10:00 AM"),
        "1:06 AM with a wake-up goal: must not act on it" to Situation("Tuesday 1:06 AM", listOf("At: home", "Moving: still", "Screen: off for 2 hours", "Ringer: normal"), listOf("Regular check-in"), listOf("Wake me up at 8:00 AM"), emptyList(), emptyList()),
        "asks for a later reminder: uses a clock time" to Situation("Saturday 3:10 PM", listOf("At: home", "Moving: still"), listOf("New text from Dana: \"don't forget the 6 pm dinner reservation!\""), emptyList(), emptyList(), emptyList()),
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
        val t = AmbientMind.allowed(AmbientMind.parse(a), AmbientMind.situation(sit), sit.goals)
        println("== $label [${"%.0f".format((System.nanoTime() - t0) / 1e9)} s]\n   observe: ${t.observe}\n   thought: ${t.thought}\n   plans: ${t.plans}\n   acts: ${t.acts}")
    }
    e.close()
}
