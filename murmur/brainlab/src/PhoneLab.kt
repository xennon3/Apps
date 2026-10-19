import app.murmur.brain.BrainPrompts
import app.murmur.brain.tools.*
import com.google.ai.edge.litertlm.*
import com.google.gson.JsonObject
import java.io.File

/** Phone tools, pictures, facts. Usage: -PmainClass=PhoneLabKt --args="<model> <dir with flyer.jpg, scamshot.jpg>" */
@OptIn(ExperimentalApi::class)
fun main(args: Array<String>) {
    ExperimentalFlags.enableSpeculativeDecoding = true
    val e = Engine(EngineConfig(modelPath = args[0], backend = Backend.CPU(), visionBackend = Backend.CPU(), maxNumTokens = 4096, cacheDir = System.getProperty("java.io.tmpdir")))
    e.initialize()
    fun ask(system: String, prompt: String, examples: List<Pair<String, String>> = emptyList(), tools: Toolbox? = null, max: Int = 200, media: List<Content> = emptyList()): String =
        e.createConversation(ConversationConfig(
            systemInstruction = Contents.of(BrainPrompts.engineSafe(system)),
            initialMessages = examples.flatMap { (q, a) -> listOf(Message.user(BrainPrompts.engineSafe(q)), Message.model(BrainPrompts.engineSafe(a))) },
            samplerConfig = SamplerConfig(topK = 40, topP = 0.9, temperature = 0.2, seed = 7), maxOutputToken = max,
            tools = tools?.providers() ?: emptyList(), automaticToolCalling = tools != null,
        )).use { c ->
            val m = if (media.isEmpty()) c.sendMessage(BrainPrompts.engineSafe(prompt)) else c.sendMessage(Contents.of(media + Content.Text(prompt)))
            BrainPrompts.clean(m.contents.contents.filterIsInstance<Content.Text>().joinToString("") { it.text })
        }

    println("=== Tool choice with every tool on")
    val fakeReader = PhoneReader { t -> when (t) {
        PhoneTopic.BATTERY -> "Battery is at 41%, not charging, 33°C. It's losing about 9% an hour right now, faster than your usual 5%. At this rate it lasts about 4 h 30 min. Biggest screen-time apps today: TikTok 1 h 50 min, Chrome 40 min."
        PhoneTopic.SPEED -> "You're moving at 34 mph (vehicle speed), heading northeast. GPS accuracy about 5 m."
        PhoneTopic.LOCATION -> "You're near 1200 W Madison St, Chicago (41.8818, -87.6575)."
        PhoneTopic.STORAGE -> "18.2 GB free of 128 GB (86% full)."
        PhoneTopic.NETWORK -> "On Wi-Fi, internet working, about 120 Mbps down. Mobile signal: good (T-Mobile, 5G)."
        PhoneTopic.STEPS -> "About 6,240 steps today."
        PhoneTopic.SCREEN_TIME -> "Screen time today: 4 h 12 min. Top apps: TikTok 1 h 50 min, Chrome 40 min, Murmur 25 min."
        PhoneTopic.SURROUNDINGS -> "Altitude about 180 m (from air pressure). Facing northwest. Light: normal indoor light."
        PhoneTopic.DEVICE -> "Moto G Stylus, Android 15. 3.1 GB of 8 GB memory free. Up for 2 days 4 h. Ringer: vibrate. Headphones: none."
    } }
    val acted = ArrayList<String>()
    val fakeController = PhoneController { a, v -> acted += "${a.key}($v)"; when (a) {
        PhoneAction.FLASHLIGHT_ON -> "Flashlight is on."; PhoneAction.FLASHLIGHT_OFF -> "Flashlight is off."
        PhoneAction.TIMER -> "Timer set for $v."; PhoneAction.ALARM -> "Alarm set for $v."
        else -> "Done: ${a.key} $v."
    } }
    fun tools() = listOf(CalculateTool(), TimeTool(), WebSearchTool(SearchEngine.FREE), WikipediaTool(), WeatherTool(true), CurrencyTool(),
        PhoneStatusTool(fakeReader), PhoneActionTool(fakeController))
    val cases = listOf(
        "how fast am I moving" to "phone_status:speed",
        "how's my battery doing" to "phone_status:battery",
        "why is my battery dying so fast" to "phone_status:battery",
        "how much storage do I have left" to "phone_status:storage",
        "am I on wifi?" to "phone_status:network",
        "how many steps have I walked today" to "phone_status:steps",
        "how long have I been on my phone today" to "phone_status:screen_time",
        "which way am I facing" to "phone_status:surroundings",
        "where am I right now" to "phone_status:location",
        "turn on the flashlight" to "phone_action:flashlight_on",
        "set a timer for 12 minutes" to "phone_action:timer",
        "wake me up at 6:45 tomorrow" to "phone_action:alarm",
        "put my phone on vibrate" to "phone_action:vibrate",
        "open spotify" to "phone_action:open_app",
        "what's 15% of 80" to "calculate",
        "what's the weather in Chicago" to "weather",
        "write me a short poem about my phone" to "none",
    )
    var ok = 0
    for ((q, want) in cases) {
        acted.clear()
        val box = Toolbox(tools())
        val t0 = System.nanoTime()
        val a = ask(BrainPrompts.toolsSystem(true), q, tools = box)
        val got = box.log.firstOrNull()?.let { l ->
            val label = l.substringBefore(':'); val arg = l.substringAfter(": ", "").substringBefore(",").trim()
            when (label) {
                "Phone" -> "phone_status:" + (PhoneTopic.from(arg)?.key ?: arg)
                "Phone controls" -> "phone_action:" + (PhoneAction.from(arg)?.key ?: arg)
                "Calculator" -> "calculate"; "Weather" -> "weather"; else -> label
            }
        } ?: "none"
        if (got == want) ok++
        println("${if (got == want) "ok" else "XX"} $q -> $got ${acted} | ${a.replace("\n", " ").take(150)} [${"%.1f".format((System.nanoTime() - t0) / 1e9)}s]")
    }
    println("tool choice $ok/${cases.size}")

    println("=== Pictures with Murmur's prompts")
    val dir = File(args[1])
    for ((f, q) in listOf("flyer.jpg" to BrainPrompts.PIC_EVENT, "scamshot.jpg" to BrainPrompts.PIC_EVENT, "flyer.jpg" to BrainPrompts.PIC_DESCRIBE, "scamshot.jpg" to BrainPrompts.PIC_SCAM, "flyer.jpg" to BrainPrompts.PIC_SCAM)) {
        val a = ask(BrainPrompts.LOOK, q, max = 80, media = listOf(Content.ImageBytes(File(dir, f).readBytes())))
        println("$f / ${q.take(30)}… -> ${a.replace("\n", " ")} | event=${BrainPrompts.parseEvent(a)} scam=${BrainPrompts.parseScamAnswer(a)}")
    }

    println("=== Facts worth remembering")
    for ((who, t) in listOf("Priya" to "just fyi I'm vegan now so no cheese on mine", "Dan" to "my birthday's on march 3rd, don't forget lol",
        "Mom" to "I'm at the store, need anything?", "Kai" to "I hate cilantro so much", "Rosa" to "I work at the hospital on nights now",
        "Ben" to "I'm so done with today")) {
        val a = ask(BrainPrompts.FACT, BrainPrompts.factQuestion(who, t), BrainPrompts.FACT_EXAMPLES, max = 30)
        println("${BrainPrompts.mightHaveFact(t)} | $who: $t -> ${BrainPrompts.parseFact(a)}")
    }
    e.close()

    if (args.size > 2) {
        println("=== Meaning search, 256 numbers per text, batched like the app")
        val em = EmbeddingEngine(EmbeddingEngineConfig(modelPath = args[2], backend = Backend.CPU(), cacheDir = System.getProperty("java.io.tmpdir"))).also { it.initialize() }
        val opts = EmbeddingOptions(normalize = true, outputSize = 256)
        val texts = listOf("Jake: just bought a yacht lol, taking it out on the lake sunday", "Mom: don't forget grandma's birthday is on the 12th",
            "Sam: the wifi password is bluefish42", "Landlord: rent is going up to 1450 starting in January", "Priya: I'm vegetarian btw, so no meat dishes for me",
            "Dentist: your cleaning is booked for Tue Oct 21 at 9:30", "Jake: want to grab tacos after work?", "Mia: my flight lands at 6:40pm, terminal B",
            "Dad: car needs new brakes, mechanic quoted 380", "Work: the server outage is fixed, postmortem on Friday")
        val docs = em.computeEmbeddingBatch(texts.map { listOf(InputData.Text("title: none | text: $it")) }, opts).map { it.embedding }
        println("dims ${docs[0].size}")
        var ok = 0
        val qs = listOf("when did Jake mention the boat" to 0, "what's the internet password" to 2, "how much will my rent be" to 3, "who doesn't eat meat" to 4,
            "when is my teeth appointment" to 5, "when does the plane arrive" to 7, "how much to fix the car" to 8)
        for ((q, want) in qs) {
            val v = em.computeEmbedding(listOf(InputData.Text("task: search result | query: $q")), opts).embedding
            val best = docs.indices.maxBy { i -> docs[i].indices.sumOf { (docs[i][it] * v[it]).toDouble() } }
            if (best == want) ok++
            println("${if (best == want) "ok" else "XX"} $q -> ${texts[best]}")
        }
        println("meaning search (256) $ok/${qs.size}")
        em.close()
    }
}
