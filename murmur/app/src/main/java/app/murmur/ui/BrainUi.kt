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
