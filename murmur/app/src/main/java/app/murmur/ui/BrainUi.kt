package app.murmur.ui

import androidx.compose.foundation.layout.Column
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
                    "Adds two things: a \"Calm it down\" rewrite when the heat meter goes red, and a second opinion on " +
                        "texts that might be scams but don't trip the usual checks.",
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
                                testing -> "Thinking… the first run loads the model"
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

private fun speedLabel(tps: Float): String {
    val verdict = when {
        tps >= 12 -> "quick"
        tps >= 5 -> "usable"
        else -> "slow on this phone"
    }
    return "%.1f tokens a second, %s".format(tps, verdict)
}
