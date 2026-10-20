package app.murmur.ui

import android.Manifest
import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.Bolt
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.EditNote
import androidx.compose.material.icons.outlined.Flag
import androidx.compose.material.icons.outlined.HelpOutline
import androidx.compose.material.icons.outlined.Psychology
import androidx.compose.material.icons.outlined.Sensors
import androidx.compose.material.icons.outlined.DeleteSweep
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.unit.dp
import app.murmur.brain.Ambient
import app.murmur.brain.Brain

/**
 * The Mind window: Ambient mode's switch, and a live view of what Murmur senses, is thinking,
 * plans to do and has done. Texts it wants to send wait here for a tap.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MindScreen(onBack: () -> Unit) {
    BackHandler(onBack = onBack)
    val context = LocalContext.current
    val scheme = MaterialTheme.colorScheme
    LaunchedEffect(Unit) { Ambient.load(context); Ambient.ensureAlarms(context) }
    val running by Ambient.running.collectAsState()
    val senses by Ambient.senses.collectAsState()
    val thinking by Ambient.thinking.collectAsState()
    val plans by Ambient.plans.collectAsState()
    val asks by Ambient.asks.collectAsState()
    val timeline by Ambient.timeline.collectAsState()
    var on by remember { mutableStateOf(Ambient.on(context)) }
    var goalTick by remember { mutableIntStateOf(0) }
    var newGoal by remember { mutableStateOf("") }
    var pace by remember { mutableStateOf(Ambient.pace(context)) }
    var confirmClear by remember { mutableStateOf(false) }
    var naming by remember { mutableStateOf(false) }

    // Location, steps and notifications are asked for when it's switched on; it works with whatever is allowed.
    val perms = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        Ambient.start(context); on = true
    }
    fun switch(want: Boolean) {
        if (!want) { Ambient.stop(context); on = false; return }
        val need = buildList {
            add(Manifest.permission.ACCESS_FINE_LOCATION); add(Manifest.permission.ACCESS_COARSE_LOCATION)
            if (Build.VERSION.SDK_INT >= 29) add(Manifest.permission.ACTIVITY_RECOGNITION)
            if (Build.VERSION.SDK_INT >= 33) add(Manifest.permission.POST_NOTIFICATIONS)
        }
        perms.launch(need.toTypedArray())
    }

    Scaffold(topBar = {
        TopAppBar(
            title = { Text("Mind") },
            navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
            actions = {
                if (timeline.isNotEmpty()) IconButton(onClick = { confirmClear = true }) { Icon(Icons.Outlined.DeleteSweep, "Clear what happened") }
            },
        )
    }) { pad ->
        LazyColumn(Modifier.fillMaxSize().padding(pad).padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item {
                Surface(color = scheme.primaryContainer.copy(alpha = 0.45f), contentColor = scheme.onSurface, shape = RoundedCornerShape(20.dp)) {
                    Column(Modifier.padding(16.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Outlined.Psychology, null, tint = scheme.primary)
                            Spacer(Modifier.width(10.dp))
                            Column(Modifier.weight(1f)) {
                                Text("Ambient mode", style = MaterialTheme.typography.titleMedium)
                                Text(
                                    if (on) (if (running) "Aware. Thoughts today: ${Ambient.thoughtsToday(context)} of ${pace.daily}" else "Starting…")
                                    else "Off",
                                    style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant,
                                )
                            }
                            Switch(on, { switch(it) }, enabled = Brain.ready(context))
                        }
                        if (!on) Text(
                            "Murmur Brain lives in your phone: it senses battery, movement, places, headphones, Wi-Fi and incoming texts, " +
                                "wakes up when something changes, and helps: a heads-up, a reminder, a journal note, the ringer. Texts to " +
                                "other people always wait for your tap. Everything stays on the phone, and Android shows a notification " +
                                "the whole time it's on.",
                            Modifier.padding(top = 8.dp), style = MaterialTheme.typography.bodyMedium,
                        )
                        Text("How often it thinks", Modifier.padding(top = 10.dp), style = MaterialTheme.typography.labelLarge)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Ambient.Pace.entries.forEach { p ->
                                FilterChip(pace == p, { pace = p; Ambient.setPace(context, p) }, label = { Text(p.label) })
                            }
                        }
                        Text(
                            "Checks in every ${pace.checkMinutes} minutes, and within ${pace.gapMinutes} minutes of a change (urgent ones at once). " +
                                when (pace) {
                                    Ambient.Pace.LIVELY -> "Most aware, most battery."
                                    Ambient.Pace.BALANCED -> "A good middle."
                                    Ambient.Pace.RELAXED -> "Lightest on the battery."
                                },
                            style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant,
                        )
                        if (!Brain.ready(context)) Text("Turn on Murmur Brain in Settings first.", Modifier.padding(top = 6.dp), color = scheme.error)
                    }
                }
            }

            if (asks.isNotEmpty()) {
                item { Header("Waiting for you") }
                items(asks, key = { it.id }) { a ->
                    Surface(color = scheme.tertiaryContainer, contentColor = scheme.onTertiaryContainer, shape = RoundedCornerShape(16.dp)) {
                        Column(Modifier.padding(14.dp)) {
                            Text("Text ${a.who}?", style = MaterialTheme.typography.titleSmall)
                            Text("\"${a.message}\"", Modifier.padding(vertical = 6.dp))
                            Row {
                                OutlinedButton(onClick = { Ambient.answerAsk(context, a, send = true) }) { Text("Send") }
                                Spacer(Modifier.width(8.dp))
                                TextButton(onClick = { Ambient.answerAsk(context, a, send = false) }) { Text("Skip") }
                            }
                        }
                    }
                }
            }

            item { Header("Thinking") }
            item {
                Text(
                    thinking?.ifEmpty { "Waking up…" } ?: if (running) "Asleep. It wakes when something changes, and checks in every ${pace.checkMinutes} minutes." else "—",
                    style = MaterialTheme.typography.bodyLarge,
                    fontStyle = if (thinking == null) FontStyle.Italic else FontStyle.Normal,
                    color = if (thinking == null) scheme.onSurfaceVariant else scheme.onSurface,
                )
            }

            if (senses.isNotEmpty()) {
                item { Header("Senses now") }
                items(senses) { s -> Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Outlined.Sensors, null, Modifier.size(16.dp), tint = scheme.primary)
                    Spacer(Modifier.width(8.dp))
                    Text(s, style = MaterialTheme.typography.bodyMedium)
                } }
                if (running) item {
                    TextButton(onClick = { naming = true }) { Text("Name this place (home, work…)") }
                }
                item {
                    Text(
                        "Sensor diary: ${Ambient.diarySize()} readings (one every 5 seconds, last 6 hours, on this phone only).",
                        style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant,
                    )
                }
            }

            if (plans.isNotEmpty()) {
                item { Header("Plans") }
                items(plans) { p -> Text("• ${p.whenText}: ${p.what}", style = MaterialTheme.typography.bodyMedium) }
            }

            item { Header("Your goals for it") }
            val goals = run { goalTick; Ambient.goals(context) }
            items(goals) { g ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Outlined.Flag, null, Modifier.size(16.dp), tint = scheme.primary)
                    Text(g, Modifier.weight(1f).padding(horizontal = 8.dp), style = MaterialTheme.typography.bodyMedium)
                    IconButton(onClick = { Ambient.removeGoal(context, g); goalTick++ }) { Icon(Icons.Outlined.Close, "Remove") }
                }
            }
            item {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(
                        newGoal, { newGoal = it }, Modifier.weight(1f),
                        placeholder = { Text("e.g. Remind me to take my pills at 9 PM") }, singleLine = true,
                    )
                    TextButton(enabled = newGoal.isNotBlank(), onClick = { Ambient.addGoal(context, newGoal); newGoal = ""; goalTick++ }) { Text("Add") }
                }
            }

            item { Header("What happened") }
            if (timeline.isEmpty()) item { Text("Nothing yet.", color = scheme.onSurfaceVariant) }
            items(timeline.asReversed().take(150)) { e ->
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
                    Text(Ambient.stamp(e.at), Modifier.width(64.dp), style = MaterialTheme.typography.labelSmall, color = scheme.onSurfaceVariant)
                    Icon(
                        when (e.kind) {
                            Ambient.Kind.SENSE -> Icons.Outlined.Sensors
                            Ambient.Kind.THOUGHT -> Icons.Outlined.AutoAwesome
                            Ambient.Kind.PLAN -> Icons.Outlined.Flag
                            Ambient.Kind.ACT -> Icons.Outlined.Bolt
                            Ambient.Kind.ASK -> Icons.Outlined.HelpOutline
                            Ambient.Kind.JOURNAL -> Icons.Outlined.EditNote
                        },
                        null, Modifier.size(16.dp),
                        tint = if (e.kind == Ambient.Kind.ACT || e.kind == Ambient.Kind.ASK) scheme.tertiary else scheme.primary,
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(e.text, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                }
            }
            item { Spacer(Modifier.size(32.dp)) }
        }
    }

    if (confirmClear) AlertDialog(
        onDismissRequest = { confirmClear = false },
        title = { Text("Clear what happened?") },
        text = { Text("This wipes the timeline, its plans and the sensor diary. Your goals and the places it learned stay.") },
        confirmButton = { TextButton(onClick = { Ambient.clear(context); confirmClear = false }) { Text("Clear") } },
        dismissButton = { TextButton(onClick = { confirmClear = false }) { Text("Keep") } },
    )

    if (naming) {
        var name by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { naming = false },
            title = { Text("What is this place?") },
            text = {
                Column {
                    Text("It then knows where it is instead of guessing, and goals like \"vibrate at work\" work right away.", style = MaterialTheme.typography.bodyMedium)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 8.dp)) {
                        listOf("home", "work", "gym", "school").forEach { n -> FilterChip(name == n, { name = n }, label = { Text(n) }) }
                    }
                    OutlinedTextField(name, { name = it }, Modifier.fillMaxWidth().padding(top = 6.dp), label = { Text("Or type a name") }, singleLine = true)
                }
            },
            confirmButton = {
                TextButton(enabled = name.isNotBlank(), onClick = {
                    val ok = Ambient.nameCurrentPlace(context, name)
                    android.widget.Toast.makeText(context, if (ok) "Got it: this is $name" else "No location yet; try again in a minute", android.widget.Toast.LENGTH_SHORT).show()
                    naming = false
                }) { Text("Save") }
            },
            dismissButton = { TextButton(onClick = { naming = false }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun Header(text: String) =
    Text(text, Modifier.padding(top = 4.dp), style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
