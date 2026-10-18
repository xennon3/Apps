package app.murmur.ui

import android.app.AlarmManager
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.Bedtime
import androidx.compose.material.icons.outlined.Dialpad
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.Mic
import androidx.compose.material.icons.outlined.GppMaybe
import androidx.compose.material.icons.outlined.Key
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.NotificationsActive
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material.icons.automirrored.outlined.Undo
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.murmur.brain.Brain
import app.murmur.data.Phone
import app.murmur.data.Prefs
import app.murmur.data.SmsRepository
import app.murmur.data.PendingStore
import app.murmur.data.Repeat
import app.murmur.sms.Outbox
import app.murmur.voice.Voice
import androidx.compose.material.icons.outlined.Repeat
import androidx.compose.runtime.collectAsState
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

// ---------- new message ----------

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NewMessageScreen(prefill: String, onBack: () -> Unit, onPick: (String) -> Unit) {
    val context = LocalContext.current
    val repo = remember { SmsRepository(context) }
    var query by remember { mutableStateOf("") }
    var results by remember { mutableStateOf<List<SmsRepository.PhoneEntry>>(emptyList()) }
    val focus = remember { FocusRequester() }

    LaunchedEffect(Unit) { focus.requestFocus() }
    LaunchedEffect(query) {
        delay(120)
        results = withContext(Dispatchers.IO) { repo.searchContacts(query) }
    }
    val digits = Phone.digits(query)
    val looksLikeNumber = digits.count { it.isDigit() } >= 3 && query.none { it.isLetter() }

    Scaffold(
        topBar = {
            TopAppBar(
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
                title = { Text("New message") },
            )
        },
    ) { pad ->
        Column(Modifier.fillMaxSize().padding(pad)) {
            if (prefill.isNotBlank()) Text(
                "Sharing: \"${prefill.take(80)}\"",
                Modifier.padding(horizontal = 16.dp),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                label = { Text("To: name or number") },
                singleLine = true,
                shape = RoundedCornerShape(16.dp),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Go, keyboardType = KeyboardType.Text),
                keyboardActions = KeyboardActions(onGo = { if (looksLikeNumber) onPick(digits) }),
                modifier = Modifier.fillMaxWidth().padding(16.dp, 8.dp).focusRequester(focus),
            )
            LazyColumn(Modifier.fillMaxSize()) {
                if (looksLikeNumber) item {
                    ListItem(
                        leadingContent = {
                            Box(
                                Modifier.size(48.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primaryContainer),
                                contentAlignment = Alignment.Center,
                            ) { Icon(Icons.Outlined.Dialpad, null) }
                        },
                        headlineContent = { Text("Text ${Phone.pretty(digits)}") },
                        modifier = Modifier.clickable { onPick(digits) },
                    )
                }
                items(results, key = { it.name + it.number }) { r ->
                    ListItem(
                        leadingContent = { Avatar(r.name, r.number, r.photo) },
                        headlineContent = { Text(r.name) },
                        supportingContent = { Text("${r.label}  ${Phone.pretty(r.number)}") },
                        modifier = Modifier.clickable { onPick(r.number) },
                    )
                }
            }
        }
    }
}

// ---------- onboarding ----------

@Composable
fun OnboardingScreen(
    isDefault: Boolean,
    askedForRole: Boolean,
    canRead: Boolean,
    onMakeDefault: () -> Unit,
    onContinue: () -> Unit,
) {
    val context = LocalContext.current
    val scheme = MaterialTheme.colorScheme
    Surface(color = scheme.background, contentColor = scheme.onBackground) { Box(
        Modifier.fillMaxSize().background(
            Brush.verticalGradient(listOf(scheme.primaryContainer.copy(alpha = 0.55f), scheme.background, scheme.background)),
        ),
    ) {
        Column(
            Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()
                .verticalScroll(rememberScrollState()).padding(24.dp),
        ) {
            Spacer(Modifier.height(32.dp))
            Text("Murmur", fontSize = 44.sp, fontWeight = FontWeight.Black, color = scheme.primary)
            Text("Texting, but kinder to you.", style = MaterialTheme.typography.titleMedium, color = scheme.onSurfaceVariant)
            Spacer(Modifier.height(28.dp))
            Feature(Icons.AutoMirrored.Outlined.Undo, "Undo send", "Every text waits a few seconds before it leaves. Changed your mind? Pull it back.")
            Feature(Icons.Outlined.Schedule, "Send later", "Hold the send button to schedule. It goes out even if the app is closed.")
            Feature(Icons.Outlined.Bedtime, "Sleep on it", "Writing something heated at 2am? Murmur offers to send it at 8 instead.")
            Feature(Icons.Outlined.AutoAwesome, "Murmur Brain", "An optional AI that stays on your phone: ask it about your texts, catch up on busy chats, add plans to your calendar.")
            Feature(Icons.Outlined.Key, "Codes, copied", "Login codes get a Copy button right in the notification.")
            Feature(Icons.Outlined.NotificationsActive, "Remind me to reply", "Snooze a conversation and Murmur nudges you later.")
            Feature(Icons.Outlined.Mic, "Voice notes", "Talk instead of type. Each note comes with a transcript anyone can read.")
            Feature(Icons.Outlined.GppMaybe, "Scam shield", "Fake toll, parcel and bank texts get a warning, and their links ask before opening.")
            Feature(Icons.Outlined.Image, "Pictures & group chats", "Send and receive photos and group messages. Dual-SIM phones pick the SIM per chat.")
            Spacer(Modifier.height(24.dp))

            if (isDefault) {
                Button(onClick = onContinue, Modifier.fillMaxWidth().height(56.dp)) { Text("Let's go") }
            } else {
                Button(
                    onClick = onMakeDefault,
                    modifier = Modifier.fillMaxWidth().height(56.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = scheme.primary),
                ) { Text("Make Murmur my messaging app") }
                if (askedForRole) {
                    Spacer(Modifier.height(16.dp))
                    Surface(color = scheme.secondaryContainer, shape = RoundedCornerShape(20.dp)) {
                        Column(Modifier.padding(16.dp)) {
                            Text("Android said no?", fontWeight = FontWeight.SemiBold)
                            Spacer(Modifier.height(4.dp))
                            Text(
                                "Apps installed from a download, not the Play Store, are blocked from texting " +
                                    "until you allow it. In Murmur's app info, tap ⋮ (top right) → " +
                                    "Allow restricted settings, then come back and tap the button above again.\n\n" +
                                    "If there's no ⋮ menu, try the button once more first; Android only unlocks it " +
                                    "after it has blocked something.",
                                style = MaterialTheme.typography.bodyMedium,
                            )
                            Spacer(Modifier.height(8.dp))
                            OutlinedButton(onClick = {
                                context.startActivity(
                                    Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + context.packageName))
                                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                                )
                            }) { Text("Open Murmur's app info") }
                        }
                    }
                }
                if (canRead) {
                    Spacer(Modifier.height(8.dp))
                    OutlinedButton(onClick = onContinue, Modifier.fillMaxWidth()) { Text("Just look around for now") }
                }
            }
            Spacer(Modifier.height(16.dp))
            Text(
                "Everything stays on your phone. Murmur has no servers and no account, and your messages never go online.",
                style = MaterialTheme.typography.bodySmall,
                color = scheme.onSurfaceVariant,
            )
        }
    } }
}

@Composable
private fun Feature(icon: ImageVector, title: String, body: String) {
    Row(Modifier.padding(vertical = 8.dp), verticalAlignment = Alignment.Top) {
        Box(
            Modifier.size(40.dp).clip(RoundedCornerShape(12.dp)).background(MaterialTheme.colorScheme.surfaceContainerHighest),
            contentAlignment = Alignment.Center,
        ) { Icon(icon, null, tint = MaterialTheme.colorScheme.secondary) }
        Spacer(Modifier.width(14.dp))
        Column {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(body, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

// ---------- settings ----------

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(isDefault: Boolean, onMakeDefault: () -> Unit, onBack: () -> Unit) {
    val context = LocalContext.current
    val prefs = remember { Prefs(context) }
    var undo by remember { mutableIntStateOf(prefs.undoSeconds) }
    var sleep by remember { mutableStateOf(prefs.sleepOnIt) }
    var groupMms by remember { mutableStateOf(prefs.groupMms) }

    Scaffold(
        topBar = {
            TopAppBar(
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
                title = { Text("Settings") },
            )
        },
    ) { pad ->
        Column(Modifier.fillMaxSize().padding(pad).verticalScroll(rememberScrollState())) {
            ListItem(
                headlineContent = { Text("Default messaging app") },
                supportingContent = { Text(if (isDefault) "Murmur is handling your texts" else "Your old app still gets new texts") },
                trailingContent = {
                    if (!isDefault) Button(onClick = onMakeDefault) { Text("Switch") }
                    else OutlinedButton(onClick = {
                        context.startActivity(Intent(Settings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                    }) { Text("Change") }
                },
            )
            BrainCard()
            if (Brain.enabled(context)) BrainExtras()
            ListItem(
                headlineContent = { Text("Undo window") },
                supportingContent = {
                    Column {
                        Text("How long a sent text waits before it really leaves")
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 8.dp)) {
                            for (s in listOf(0, 3, 5, 10)) FilterChip(
                                selected = undo == s,
                                onClick = { undo = s; prefs.undoSeconds = s },
                                label = { Text(if (s == 0) "Off" else "${s}s") },
                            )
                        }
                    }
                },
            )
            ListItem(
                headlineContent = { Text("Sleep on it") },
                supportingContent = { Text("Between midnight and 5am, offer to hold heated texts until 8am") },
                trailingContent = { Switch(sleep, { sleep = it; prefs.sleepOnIt = it }) },
            )
            ListItem(
                headlineContent = { Text("Group chats") },
                supportingContent = {
                    Text(
                        if (groupMms) "Texts to several people go as one group conversation, so replies reach everyone"
                        else "Texts to several people go to each person separately",
                    )
                },
                trailingContent = { Switch(groupMms, { groupMms = it; prefs.groupMms = it }) },
            )
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val exact = context.getSystemService(AlarmManager::class.java).canScheduleExactAlarms()
                ListItem(
                    headlineContent = { Text("On-time scheduled texts") },
                    supportingContent = {
                        Text(
                            if (exact) "Allowed. Scheduled texts go out on the minute."
                            else "Off. Android may send scheduled texts a few minutes late. Tap to allow alarms.",
                        )
                    },
                    modifier = if (exact) Modifier else Modifier.clickable {
                        context.startActivity(
                            Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, Uri.parse("package:" + context.packageName))
                                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                        )
                    },
                )
            }
            ListItem(
                headlineContent = { Text("Notifications") },
                supportingContent = { Text("Sounds, and separate channels for codes and reply reminders") },
                modifier = Modifier.clickable {
                    context.startActivity(
                        Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                            .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                    )
                },
            )
            Text(
                "Murmur 1.5 · texts, codes and drafts never leave this phone.",
                Modifier.padding(16.dp).navigationBarsPadding(),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

// ---------- everything scheduled ----------

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ScheduledScreen(onBack: () -> Unit, onOpen: (Long) -> Unit) {
    val context = LocalContext.current
    val repo = remember { SmsRepository(context) }
    val all by PendingStore.get(context).all.collectAsState()
    val scheduled = all.filter { it.scheduled }.sortedBy { it.at }
    var titles by remember { mutableStateOf<Map<Long, String>>(emptyMap()) }
    LaunchedEffect(scheduled.map { it.threadId }.toSet()) {
        titles = withContext(Dispatchers.IO) {
            val convos = runCatching { repo.conversations() }.getOrDefault(emptyList()).associateBy { it.threadId }
            scheduled.associate { p -> p.threadId to (convos[p.threadId]?.title ?: Phone.pretty(p.address)) }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
                title = { Text("Scheduled") },
            )
        },
    ) { pad ->
        if (scheduled.isEmpty()) {
            Column(Modifier.fillMaxSize().padding(pad).padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Spacer(Modifier.height(48.dp))
                Text("Nothing scheduled", style = MaterialTheme.typography.titleLarge)
                Spacer(Modifier.height(8.dp))
                Text(
                    "In any conversation, hold the send button to send later or on a repeat: every morning, every weekday, every birthday.",
                    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            return@Scaffold
        }
        LazyColumn(Modifier.fillMaxSize().padding(pad)) {
            items(scheduled, key = { it.id }) { p ->
                Surface(
                    color = MaterialTheme.colorScheme.surfaceContainer,
                    shape = RoundedCornerShape(20.dp),
                    modifier = Modifier.fillMaxWidth().padding(16.dp, 6.dp),
                    onClick = { onOpen(p.threadId) },
                ) {
                    Column(Modifier.padding(16.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                if (p.repeat != null) Icons.Outlined.Repeat else Icons.Outlined.Schedule, null,
                                Modifier.size(18.dp), tint = MaterialTheme.colorScheme.secondary,
                            )
                            Spacer(Modifier.width(8.dp))
                            Text(titles[p.threadId] ?: "…", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                        }
                        Text(
                            When.future(context, p.at) + (Repeat.describe(p.repeat)?.let { "  ·  $it" } ?: ""),
                            style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.secondary,
                        )
                        Spacer(Modifier.height(6.dp))
                        Text(
                            when {
                                Voice.isVoice(p.attachment) -> "🎙 Voice note" + p.body.removePrefix("🎙 ").let { if (it.isBlank()) "" else ": $it" }
                                p.attachment != null -> "📷 " + p.body.ifBlank { "Photo" }
                                else -> p.body
                            },
                            style = MaterialTheme.typography.bodyMedium, maxLines = 3,
                        )
                        Row {
                            TextButton(onClick = { Outbox.sendNow(context, p.id) }) { Text("Send now") }
                            if (p.repeat != null) TextButton(onClick = { PendingStore.get(context).setRepeat(p.id, null) }) { Text("Stop repeating") }
                            TextButton(onClick = {
                                Outbox.undoWithAttachment(context, p.id)?.attachment?.let { File(it).delete() }
                            }) { Text("Cancel", color = MaterialTheme.colorScheme.error) }
                        }
                    }
                }
            }
        }
    }
}
