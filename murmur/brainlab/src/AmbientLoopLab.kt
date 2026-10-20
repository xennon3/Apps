import app.murmur.brain.AmbientMind
import app.murmur.brain.AmbientMind.Act
import app.murmur.brain.AmbientMind.Situation
import app.murmur.brain.BrainPrompts
import com.google.ai.edge.litertlm.*
import java.util.Calendar

/** A night of check-ins with memory fed back, like the phone. -PmainClass=AmbientLoopLabKt --args="<model>" */
@OptIn(ExperimentalApi::class)
fun main(args: Array<String>) {
    ExperimentalFlags.enableSpeculativeDecoding = true
    val e = Engine(EngineConfig(modelPath = args[0], backend = Backend.CPU(), maxNumTokens = 4096, cacheDir = System.getProperty("java.io.tmpdir"))).also { it.initialize() }
    val night = args.getOrNull(1) != "drive"
    val goals = if (night) listOf("Wake me up at 8:00 AM") else emptyList()
    var plans = listOf<Pair<String, String>>()
    val reminders = ArrayList<Pair<String, String>>() // when, text
    val said = ArrayList<Pair<String, String>>()      // time, text
    val all = ArrayList<String>()
    val seenTexts = ArrayList<String>()
    val asks = ArrayList<String>()
    val times = if (night) listOf("1:10 AM", "1:20 AM", "1:30 AM", "2:00 AM", "6:59 AM", "7:10 AM", "7:30 AM", "8:05 AM")
        else listOf("5:40 PM", "5:50 PM", "6:05 PM", "6:15 PM", "6:25 PM", "6:40 PM")
    for ((i, t) in times.withIndex()) {
        val events = if (!night) when (t) {
            "5:40 PM" -> listOf("Started driving or riding (about 38 mph)")
            "5:50 PM" -> listOf("New text from Jake: \"are we still on for tonight?\"")
            "6:05 PM" -> listOf("Stopped driving", "Arrived at home")
            else -> listOf("Regular check-in")
        } else when (t) {
            "1:10 AM" -> listOf("New goal: Wake me up at 8:00 AM")
            "6:59 AM" -> listOf("New text from 28640: \"An eNotice is available now in eAlert inside Online Banking.\"")
            else -> listOf("Regular check-in")
        }
        events.filter { it.startsWith("New text from") }.forEach { seenTexts += "$t ${it.removePrefix("New text ")}" }
        val cal = Calendar.getInstance().apply { val hm = app.murmur.brain.tools.PhoneMath.parseClock(t)!!; set(Calendar.HOUR_OF_DAY, hm.first); set(Calendar.MINUTE, hm.second) }
        val senses = if (night) listOf("At: home", "Moving: still", "Screen: off", "Ringer: normal", "Battery 80%, not charging")
            else if (t < "6:05 PM" && t.length == 7) listOf("Moving: in a vehicle", "Battery 60%") else listOf("At: home", "Moving: still", "Battery 58%")
        val sit = Situation("Tuesday $t", senses, events, goals,
            plans.map { "${it.first}: ${it.second}" }, emptyList(),
            done = reminders.map { "reminder set for ${it.first}: ${it.second}" } + asks.map { "waiting for the user's OK to text: $it" } + said.map { "${it.first} told you: ${it.second}" },
            texts = seenTexts.toList())
        val prompt = AmbientMind.situation(sit)
        val a = e.createConversation(ConversationConfig(
            systemInstruction = Contents.of(AmbientMind.SYSTEM),
            initialMessages = AmbientMind.EXAMPLES.flatMap { (q, r) -> listOf(Message.user(q), Message.model(r)) },
            samplerConfig = SamplerConfig(40, 0.9, 0.3, 7 + i), maxOutputToken = 220,
        )).use { it.sendMessage(BrainPrompts.engineSafe(prompt)).contents.contents.filterIsInstance<Content.Text>().joinToString("") { c -> c.text } }
        val th = AmbientMind.allowed(AmbientMind.parse(a), prompt, goals)
        val (kept, added) = AmbientMind.mergePlans(plans, th.plans, cal)
        plans = kept
        val acts = AmbientMind.notRepeated(th.acts, reminders.map { it.second }, said.map { it.second }, asks, seenTexts.map { it.substringAfter(": ").trim('"') })
        for (x in acts) when (x) {
            is Act.Remind -> { reminders += x.whenText to x.text; all += "remind ${x.whenText}: ${x.text}" }
            is Act.Notify -> { said += t to x.text; all += "notify: ${x.text}" }
            is Act.AskSend -> { asks += x.message; all += "ask to text ${x.who}: ${x.message}" }
            else -> all += x.toString()
        }
        println("$t | model acts ${th.acts.size}, kept ${acts.size} | new plans $added | ${acts}")
    }
    println("ALL ACTIONS OVER THE NIGHT (${all.size}):"); all.forEach { println("  $it") }
    e.close()
}
