package app.murmur.ui

import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.OutlinedTextField
import androidx.compose.runtime.key
import app.murmur.brain.BrainAccess
import app.murmur.brain.BrainMemory
import app.murmur.brain.BrainWeb
import app.murmur.brain.McpRegistry
import app.murmur.brain.McpStore
import app.murmur.brain.SkillStore
import androidx.compose.material3.AlertDialog
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import app.murmur.brain.Brain
import kotlinx.coroutines.launch

/** Settings card for Murmur Brain: turn it on, watch it download, test its speed, remove it. */
@Composable
fun BrainCard() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    LaunchedEffect(Unit) { Brain.refresh(context) }
    val state by Brain.state.collectAsState()
    val speed by Brain.speed.collectAsState()
    var confirmOn by remember { mutableStateOf(false) }
    var confirmOff by remember { mutableStateOf(false) }
    var mobileData by remember { mutableStateOf(false) }
    var testing by remember { mutableStateOf(false) }
    var testFailed by remember { mutableStateOf(false) }
    val scheme = MaterialTheme.colorScheme

    Surface(
        color = scheme.primaryContainer.copy(alpha = 0.45f),
        contentColor = scheme.onSurface,
        shape = RoundedCornerShape(20.dp),
        modifier = Modifier.fillMaxWidth().padding(16.dp, 8.dp),
    ) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Outlined.AutoAwesome, null, tint = scheme.primary)
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text("Murmur Brain", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "A small AI that runs on your phone, never online",
                        style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant,
                    )
                }
                Switch(
                    checked = state != Brain.State.Off,
                    onCheckedChange = { on -> if (on) confirmOn = true else confirmOff = true },
                )
            }
            Spacer(Modifier.height(8.dp))
            when (val s = state) {
                Brain.State.Off -> Text(
                    "Adds the Brain tab (chat, and questions about your texts), Catch me up summaries, Add to calendar " +
                        "for plans in texts, a smarter Needs reply list, and a second opinion on possible scams.",
                    style = MaterialTheme.typography.bodyMedium,
                )
                is Brain.State.Downloading -> {
                    Text(
                        if (s.waitingForWifi) "Waiting for Wi-Fi to download ${Brain.MODEL.name}…"
                        else "Downloading ${Brain.MODEL.name}… ${(s.fraction * 100).toInt()}% of ${Brain.MODEL.sizeLabel}",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Spacer(Modifier.height(6.dp))
                    LinearProgressIndicator(progress = { s.fraction }, Modifier.fillMaxWidth())
                }
                Brain.State.Ready -> {
                    Text(
                        "Ready. ${Brain.MODEL.name} lives on this phone (${Brain.MODEL.sizeLabel}) and loads only while it's working.",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Spacer(Modifier.height(6.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        OutlinedButton(
                            enabled = !testing,
                            onClick = {
                                testing = true; testFailed = false
                                scope.launch { testFailed = Brain.speedTest(context) == null; testing = false }
                            },
                        ) {
                            if (testing) CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                            else Text("Speed test")
                        }
                        Spacer(Modifier.width(12.dp))
                        Text(
                            when {
                                testing -> "Testing the processor and the graphics chip… about a minute"
                                testFailed -> "The model couldn't run on this phone"
                                speed != null -> speedLabel(speed!!)
                                else -> "See how fast it is on your phone"
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = if (testFailed) scheme.error else scheme.onSurfaceVariant,
                        )
                    }
                }
                is Brain.State.Failed -> {
                    Text(s.why, style = MaterialTheme.typography.bodyMedium, color = scheme.error)
                    TextButton(onClick = { Brain.turnOn(context, mobileData) }) { Text("Try again") }
                }
            }
        }
    }

    if (confirmOn) AlertDialog(
        onDismissRequest = { confirmOn = false },
        icon = { Icon(Icons.Outlined.AutoAwesome, null) },
        title = { Text("Download Murmur Brain?") },
        text = {
            Column {
                Text(
                    "Murmur will download ${Brain.MODEL.name}, Google's open AI model, once (${Brain.MODEL.sizeLabel}, so Wi-Fi is best). After that it works with " +
                        "no connection, and your messages never leave the phone. This download is the only thing " +
                        "Murmur ever uses the internet for.",
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(mobileData, { mobileData = it })
                    Text("Allow mobile data (otherwise waits for Wi-Fi)", style = MaterialTheme.typography.bodySmall)
                }
            }
        },
        confirmButton = { TextButton(onClick = { confirmOn = false; Brain.turnOn(context, mobileData) }) { Text("Download") } },
        dismissButton = { TextButton(onClick = { confirmOn = false }) { Text("Not now") } },
    )

    if (confirmOff) AlertDialog(
        onDismissRequest = { confirmOff = false },
        title = { Text("Turn off Murmur Brain?") },
        text = { Text("This deletes the model and frees ${Brain.MODEL.sizeLabel}. You can download it again any time.") },
        confirmButton = { TextButton(onClick = { confirmOff = false; Brain.turnOff(context) }) { Text("Turn off", color = scheme.error) } },
        dismissButton = { TextButton(onClick = { confirmOff = false }) { Text("Keep it") } },
    )
}

/** Writing speed is what you feel; two sentences are about 40 tokens. */
private fun speedLabel(s: Brain.Speed): String {
    val reply = s.firstWordSeconds + 40 / s.writePerSecond.coerceAtLeast(0.1f)
    return "Writes %.1f tokens/s · reads %.0f tokens/s · loads in %.0fs on the %s.\nA two-sentence answer takes about %.0f seconds once loaded."
        .format(s.writePerSecond, s.readPerSecond, s.loadSeconds, if (s.gpu) "graphics chip" else "processor", reply)
}

/** What Murmur Brain may use besides texts, what it remembers, and how it talks. */
@Composable
fun BrainExtras() {
    val context = LocalContext.current
    val scheme = MaterialTheme.colorScheme
    var tick by remember { mutableStateOf(0) }
    var asking by remember { mutableStateOf<BrainAccess.Source?>(null) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
        val s = asking ?: return@rememberLauncherForActivityResult
        val ok = result[s.permissions.first()] == true || BrainAccess.granted(context, s)
        BrainAccess.set(context, s, ok)
        if (!ok) Toast.makeText(context, "Android didn't allow it. You can allow it in Murmur's app info.", Toast.LENGTH_LONG).show()
        asking = null; tick++
    }

    Text(
        "What Murmur Brain can use", Modifier.padding(16.dp, 16.dp, 16.dp, 4.dp),
        style = MaterialTheme.typography.titleSmall, color = scheme.primary,
    )
    Text(
        "Your texts are always available to it. Everything below is off until you turn it on, is read only on this phone, " +
            "and is only looked at when a question needs it.",
        Modifier.padding(horizontal = 16.dp), style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant,
    )
    key(tick) {
        BrainAccess.Source.entries.forEach { s ->
            val on = BrainAccess.on(context, s)
            ListItem(
                headlineContent = { Text(s.label) },
                supportingContent = { Text(s.detail) },
                trailingContent = {
                    Switch(on, { want ->
                        if (!want) { BrainAccess.set(context, s, false); tick++ }
                        else if (BrainAccess.granted(context, s)) { BrainAccess.set(context, s, true); tick++ }
                        else { asking = s; launcher.launch(s.permissions.toTypedArray()) }
                    })
                },
            )
        }
    }

    Text(
        "Memory", Modifier.padding(16.dp, 16.dp, 16.dp, 4.dp),
        style = MaterialTheme.typography.titleSmall, color = scheme.primary,
    )
    var facts by remember { mutableStateOf(BrainMemory.facts(context)) }
    var newFact by remember { mutableStateOf("") }
    Text(
        "Things the Brain keeps in mind in every chat. Say \"remember that my sister is Priya\" in the Brain tab, or add them here. " +
            "It uses these to answer and to work out who \"my sister\" is when you ask it to text someone.",
        Modifier.padding(horizontal = 16.dp), style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant,
    )
    facts.forEach { f ->
        ListItem(
            headlineContent = { Text(f) },
            trailingContent = {
                IconButton(onClick = { BrainMemory.remove(context, f); facts = BrainMemory.facts(context) }) {
                    Icon(Icons.Outlined.Close, "Forget this")
                }
            },
        )
    }
    Row(Modifier.padding(16.dp, 4.dp), verticalAlignment = Alignment.CenterVertically) {
        OutlinedTextField(
            value = newFact, onValueChange = { newFact = it }, singleLine = true,
            placeholder = { Text("e.g. I'm vegetarian") }, modifier = Modifier.weight(1f),
        )
        Spacer(Modifier.width(8.dp))
        TextButton(
            enabled = newFact.isNotBlank(),
            onClick = { BrainMemory.add(context, newFact.trim()); newFact = ""; facts = BrainMemory.facts(context) },
        ) { Text("Add") }
    }

    Text(
        "How Murmur Brain talks", Modifier.padding(16.dp, 16.dp, 16.dp, 4.dp),
        style = MaterialTheme.typography.titleSmall, color = scheme.primary,
    )
    var persona by remember { mutableStateOf(BrainMemory.persona(context)) }
    OutlinedTextField(
        value = persona,
        onValueChange = { persona = it; BrainMemory.setPersona(context, it) },
        placeholder = { Text("e.g. Short and casual. Use British spelling.") },
        modifier = Modifier.fillMaxWidth().padding(16.dp, 4.dp),
        minLines = 2,
    )
}

/** Web lookups (Brave key), skills, and MCP servers. */
@Composable
fun BrainToolsSettings() {
    val context = LocalContext.current
    val scheme = MaterialTheme.colorScheme
    val scope = rememberCoroutineScope()

    // ---------- web
    Text("Web lookups", Modifier.padding(16.dp, 20.dp, 16.dp, 4.dp), style = MaterialTheme.typography.titleSmall, color = scheme.primary)
    var webOn by remember { mutableStateOf(BrainWeb.on(context)) }
    var key by remember { mutableStateOf(BrainWeb.braveKey(context)) }
    var keyStatus by remember { mutableStateOf<String?>(null) }
    ListItem(
        headlineContent = { Text("Let Murmur Brain look things up") },
        supportingContent = {
            Text(
                "News, scores, opening hours, weather, exchange rates, Wikipedia, and reading links you paste. " +
                    "Only the search words the Brain writes go online, never your texts, memory or contacts, and every answer " +
                    "that used the web says so.",
            )
        },
        trailingContent = { Switch(webOn, { webOn = it; BrainWeb.setOn(context, it) }) },
    )
    if (webOn) {
        Column(Modifier.padding(16.dp, 0.dp)) {
            Text(
                "General web search uses Brave Search with your own free key. Without one, the Brain can still use Wikipedia, " +
                    "weather, exchange rates and links.",
                style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant,
            )
            OutlinedTextField(
                value = key, onValueChange = { key = it; BrainWeb.setBraveKey(context, it); keyStatus = null },
                label = { Text("Brave Search API key") }, singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = {
                    runCatching { context.startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(BrainWeb.BRAVE_SIGNUP))) }
                }) { Text("Get a free key") }
                TextButton(enabled = key.isNotBlank(), onClick = {
                    keyStatus = "Testing…"
                    scope.launch {
                        keyStatus = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                            runCatching {
                                val r = app.murmur.brain.tools.WebSearchTool(key).run(com.google.gson.JsonObject().apply { addProperty("query", "weather") })
                                if (r.sources.isNotEmpty()) "✓ Works" else "Connected, but no results came back"
                            }.getOrElse { "Didn't work: ${it.message}. Check the key." }
                        }
                    }
                }) { Text("Test key") }
                keyStatus?.let { Text(it, style = MaterialTheme.typography.labelMedium, color = if (it.startsWith("✓")) scheme.primary else scheme.error) }
            }
        }
    }

    // ---------- skills
    Text("Skills", Modifier.padding(16.dp, 20.dp, 16.dp, 4.dp), style = MaterialTheme.typography.titleSmall, color = scheme.primary)
    Text(
        "Instruction packs the Brain switches on when a question matches, like \"split the bill\" or \"translate this\". " +
            "Write your own for anything you ask often.",
        Modifier.padding(horizontal = 16.dp), style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant,
    )
    var skillTick by remember { mutableStateOf(0) }
    var newSkill by remember { mutableStateOf(false) }
    key(skillTick) {
        SkillStore.all(context).forEach { s ->
            ListItem(
                headlineContent = { Text(s.name) },
                supportingContent = { Text("When you say: " + s.triggers.take(5).joinToString(", "), maxLines = 1, overflow = TextOverflow.Ellipsis) },
                trailingContent = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (!s.builtIn) IconButton(onClick = { SkillStore.removeCustom(context, s); skillTick++ }) { Icon(Icons.Outlined.Close, "Delete skill") }
                        Switch(SkillStore.enabled(context, s), { SkillStore.setEnabled(context, s, it); skillTick++ })
                    }
                },
            )
        }
    }
    TextButton(onClick = { newSkill = true }, modifier = Modifier.padding(horizontal = 8.dp)) { Text("+ Write a skill") }
    if (newSkill) {
        var name by remember { mutableStateOf("") }
        var triggers by remember { mutableStateOf("") }
        var text by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { newSkill = false },
            title = { Text("New skill") },
            text = {
                Column {
                    OutlinedTextField(name, { name = it }, label = { Text("Name") }, singleLine = true, placeholder = { Text("Workout coach") })
                    OutlinedTextField(triggers, { triggers = it }, label = { Text("Switch on when I say (comma-separated)") }, placeholder = { Text("workout, gym, exercise") })
                    OutlinedTextField(text, { text = it }, label = { Text("Instructions") }, minLines = 3, placeholder = { Text("Suggest a 20-minute routine with no equipment, as a short list.") })
                }
            },
            confirmButton = {
                TextButton(enabled = name.isNotBlank() && triggers.isNotBlank() && text.isNotBlank(), onClick = {
                    SkillStore.addCustom(context, name, triggers, text); newSkill = false; skillTick++
                }) { Text("Save") }
            },
            dismissButton = { TextButton(onClick = { newSkill = false }) { Text("Cancel") } },
        )
    }

    // ---------- MCP
    Text("MCP servers (experimental)", Modifier.padding(16.dp, 20.dp, 16.dp, 4.dp), style = MaterialTheme.typography.titleSmall, color = scheme.primary)
    Text(
        "Connect remote MCP servers by URL and the Brain can use their tools. Servers that run as programs on a computer " +
            "can't run on a phone, and many need a sign-in. The Brain handles about 8 tools well, so only the first 8 are offered. " +
            "Questions you ask that use these tools are sent to that server.",
        Modifier.padding(horizontal = 16.dp), style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant,
    )
    var mcpTick by remember { mutableStateOf(0) }
    var adding by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf<Map<String, String>>(emptyMap()) }
    key(mcpTick) {
        McpStore.list(context).forEach { e ->
            ListItem(
                headlineContent = { Text(e.url.removePrefix("https://"), maxLines = 1, overflow = TextOverflow.Ellipsis) },
                supportingContent = { Text(status[e.url] ?: "Tap Check to connect and list its tools") },
                trailingContent = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        TextButton(onClick = {
                            status = status + (e.url to "Connecting…")
                            scope.launch {
                                val s = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                                    runCatching { McpRegistry.connect(e).let { t -> "✓ ${t.size} tools: " + t.joinToString(", ") { it.name } } }
                                        .getOrElse { "Couldn't connect: ${it.message}" }
                                }
                                status = status + (e.url to s)
                            }
                        }) { Text("Check") }
                        IconButton(onClick = { McpStore.remove(context, e.url); mcpTick++ }) { Icon(Icons.Outlined.Close, "Remove server") }
                    }
                },
            )
        }
    }
    TextButton(onClick = { adding = true }, modifier = Modifier.padding(horizontal = 8.dp)) { Text("+ Add MCP server") }
    if (adding) {
        var url by remember { mutableStateOf("https://") }
        var token by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { adding = false },
            title = { Text("Add MCP server") },
            text = {
                Column {
                    OutlinedTextField(url, { url = it }, label = { Text("Server URL") }, singleLine = true)
                    OutlinedTextField(token, { token = it }, label = { Text("Token (if it needs one)") }, singleLine = true, visualTransformation = PasswordVisualTransformation())
                    Text(
                        "Try https://mcp.deepwiki.com/mcp: it explains any public GitHub project, no sign-in needed.",
                        style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant, modifier = Modifier.padding(top = 6.dp),
                    )
                }
            },
            confirmButton = {
                TextButton(enabled = url.startsWith("https://") && url.length > 10, onClick = {
                    McpStore.add(context, url, token); adding = false; mcpTick++
                }) { Text("Add") }
            },
            dismissButton = { TextButton(onClick = { adding = false }) { Text("Cancel") } },
        )
    }
}
