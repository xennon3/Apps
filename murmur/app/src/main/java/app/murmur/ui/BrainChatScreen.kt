package app.murmur.ui

import androidx.compose.material.icons.outlined.Psychology
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.result.PickVisualMediaRequest
import androidx.compose.material.icons.outlined.AddPhotoAlternate
import androidx.compose.material.icons.outlined.Mic
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.filled.Stop
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.launch
import androidx.core.content.ContextCompat
import android.Manifest
import android.content.pm.PackageManager
import androidx.compose.material.icons.filled.ThumbUp
import androidx.compose.material.icons.filled.ThumbDown
import androidx.compose.material.icons.outlined.ThumbUp
import androidx.compose.material.icons.outlined.ThumbDown
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.material.icons.outlined.Public
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.DeleteSweep
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.TextButton
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import app.murmur.brain.Brain
import app.murmur.brain.BrainChat
import app.murmur.data.Phone

private val suggestions = listOf(
    "What's the wifi password someone sent me?",
    "Text Mom that I'm running late",
    "Am I free Friday night?",
    "Remember that my sister is Priya",
    "What's 18% tip on $64.50?",
    "Will it rain tomorrow?",
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BrainChatScreen(onOpenThread: (Long) -> Unit, onSettings: () -> Unit, onMind: () -> Unit = {}) {
    val context = LocalContext.current
    LaunchedEffect(Unit) { Brain.refresh(context); BrainChat.load(context); Brain.warmUp(context) }
    val state by Brain.state.collectAsState()
    val turns by BrainChat.turns.collectAsState()
    val step by BrainChat.step.collectAsState()
    val activity by BrainChat.activity.collectAsState()
    var input by rememberSaveable { mutableStateOf("") }
    var correcting by remember { mutableStateOf<Int?>(null) }
    val scope = rememberCoroutineScope()
    // A picture to ask about: shrunk to 768 px right away and kept in Murmur's own storage.
    var picture by remember { mutableStateOf<java.io.File?>(null) }
    val pickPicture = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) scope.launch {
            picture = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                app.murmur.brain.Eyes.jpeg(context, uri)?.let { bytes ->
                    java.io.File(BrainChat.pictureDir(context), "p${System.currentTimeMillis()}.jpg").also { it.writeBytes(bytes) }
                }
            }
        }
    }
    // Asking out loud: tap the mic, speak, tap again.
    val recorder = remember { app.murmur.voice.Voice.Recorder(context) }
    var listening by remember { mutableStateOf(false) }
    val micPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        if (ok) listening = recorder.start()
    }
    DisposableEffect(Unit) { onDispose { if (listening) recorder.cancel() } }
    val list = rememberLazyListState()
    val scheme = MaterialTheme.colorScheme

    LaunchedEffect(turns.size, turns.lastOrNull()?.text?.length) {
        if (turns.isNotEmpty()) list.animateScrollToItem(turns.size - 1)
    }

    Scaffold(
        contentWindowInsets = WindowInsets.statusBars,
        topBar = {
            TopAppBar(
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Outlined.AutoAwesome, null, tint = scheme.primary)
                        Spacer(Modifier.width(8.dp))
                        Text("Brain", style = MaterialTheme.typography.headlineMedium)
                    }
                },
                actions = {
                    if (state == Brain.State.Ready) IconButton(onClick = onMind) {
                        Icon(Icons.Outlined.Psychology, "Mind window (Ambient mode)", tint = if (app.murmur.brain.Ambient.on(context)) scheme.primary else scheme.onSurfaceVariant)
                    }
                    if (turns.isNotEmpty()) IconButton(onClick = { BrainChat.clear(context) }) {
                        Icon(Icons.Outlined.DeleteSweep, "Clear chat")
                    }
                },
            )
        },
    ) { pad ->
        Column(Modifier.fillMaxSize().padding(pad).imePadding()) {
            if (state != Brain.State.Ready) {
                Column(Modifier.weight(1f).fillMaxWidth().padding(32.dp), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(Icons.Outlined.AutoAwesome, null, Modifier.size(48.dp), tint = scheme.primary)
                    Spacer(Modifier.height(12.dp))
                    Text("A private AI for your texts", style = MaterialTheme.typography.titleLarge, textAlign = TextAlign.Center)
                    Spacer(Modifier.height(8.dp))
                    Text(
                        when (val s = state) {
                            is Brain.State.Downloading -> "Murmur Brain is downloading (${(s.fraction * 100).toInt()}%). It'll be ready here when it's done."
                            else -> "Chat with an AI that lives on your phone, and ask it about your texts: \"what's the gate code Jen sent?\". Turn on Murmur Brain in Settings to start."
                        },
                        style = MaterialTheme.typography.bodyMedium, color = scheme.onSurfaceVariant, textAlign = TextAlign.Center,
                    )
                    if (state !is Brain.State.Downloading) {
                        Spacer(Modifier.height(16.dp))
                        Button(onClick = onSettings) { Text("Set up Murmur Brain") }
                    }
                }
                return@Column
            }

            LazyColumn(
                state = list,
                modifier = Modifier.weight(1f).fillMaxWidth(),
                contentPadding = PaddingValues(12.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                if (turns.isEmpty()) item {
                    Column(Modifier.fillMaxWidth().padding(top = 32.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("Ask me anything", style = MaterialTheme.typography.titleLarge)
                        Spacer(Modifier.height(6.dp))
                        Text(
                            "I run on this phone, so nothing you ask leaves it. Ask about your texts and I'll look through them first.",
                            style = MaterialTheme.typography.bodyMedium, color = scheme.onSurfaceVariant, textAlign = TextAlign.Center,
                        )
                        Spacer(Modifier.height(16.dp))
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally)) {
                            suggestions.forEach { s -> SuggestionChip(onClick = { BrainChat.send(context, s) }, label = { Text(s) }) }
                        }
                    }
                }
                itemsIndexed(turns) { i, t ->
                    if (t.fromUser) {
                        t.image?.let { path ->
                            Box(Modifier.fillMaxWidth().padding(bottom = 4.dp), contentAlignment = Alignment.CenterEnd) {
                                val img = rememberImage(android.net.Uri.fromFile(java.io.File(path)), 480)
                                if (img != null) androidx.compose.foundation.Image(img, "Your picture", Modifier.widthIn(max = 220.dp).clip(RoundedCornerShape(16.dp)))
                            }
                        }
                        if (t.text.isNotEmpty()) Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.CenterEnd) {
                            Text(
                                (if (t.spoken) "🎙 " else "") + t.text,
                                Modifier.widthIn(max = 300.dp).clip(RoundedCornerShape(20.dp, 20.dp, 6.dp, 20.dp))
                                    .background(scheme.primary).padding(horizontal = 14.dp, vertical = 9.dp),
                                color = scheme.onPrimary, style = MaterialTheme.typography.bodyLarge,
                            )
                        }
                    } else {
                        Row(verticalAlignment = Alignment.Top) {
                            Box(Modifier.size(28.dp).clip(CircleShape).background(scheme.primaryContainer), contentAlignment = Alignment.Center) {
                                Icon(Icons.Outlined.AutoAwesome, null, Modifier.size(16.dp), tint = scheme.primary)
                            }
                            Spacer(Modifier.width(8.dp))
                            Column(Modifier.weight(1f)) {
                                if (t.used.isNotEmpty()) Text(
                                    "Used: " + t.used.joinToString(", "),
                                    style = MaterialTheme.typography.labelSmall, color = scheme.onSurfaceVariant,
                                )
                                if (t.searched.isNotEmpty()) Row(verticalAlignment = Alignment.CenterVertically) {
                                    Icon(Icons.Outlined.Search, null, Modifier.size(12.dp), tint = scheme.onSurfaceVariant)
                                    Spacer(Modifier.width(4.dp))
                                    Text(
                                        (if (t.web.isNotEmpty() || t.used.contains("Web search")) "Searched the web for: " else "Looked in your texts for: ") +
                                            t.searched.joinToString(", "),
                                        style = MaterialTheme.typography.labelSmall, color = scheme.onSurfaceVariant,
                                    )
                                }
                                Surface(color = scheme.surfaceContainerHigh, shape = RoundedCornerShape(6.dp, 20.dp, 20.dp, 20.dp), modifier = Modifier.padding(top = 4.dp)) {
                                    Text(
                                        t.text.ifEmpty { "…" },
                                        Modifier.padding(horizontal = 14.dp, vertical = 9.dp),
                                        style = MaterialTheme.typography.bodyLarge,
                                    )
                                }
                                t.send?.let { card -> SendCardView(i, card) }
                                if (t.web.isNotEmpty() && !t.writing) FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(top = 4.dp)) {
                                    t.web.take(4).forEach { w ->
                                        AssistChip(
                                            onClick = { runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(w.url))) } },
                                            label = { Text(w.title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                                            leadingIcon = { Icon(Icons.Outlined.Public, null, Modifier.size(14.dp)) },
                                            modifier = Modifier.widthIn(max = 220.dp),
                                        )
                                    }
                                }
                                if (!t.writing && t.send == null && t.text.isNotEmpty()) Row(verticalAlignment = Alignment.CenterVertically) {
                                    if (t.text.length > 40) Text(
                                        "Copy",
                                        Modifier.padding(top = 2.dp).clip(RoundedCornerShape(8.dp)).clickable {
                                            context.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("Murmur Brain", t.text))
                                        }.padding(6.dp),
                                        style = MaterialTheme.typography.labelSmall, color = scheme.primary,
                                    )
                                    if (i > 0) {
                                        // Teaching: a good answer becomes an example; a bad one can be corrected.
                                        Icon(
                                            if (t.rating == 1) Icons.Filled.ThumbUp else Icons.Outlined.ThumbUp, "Good answer",
                                            Modifier.clip(CircleShape).clickable { BrainChat.rate(context, i, good = true) }.padding(6.dp).size(16.dp),
                                            tint = if (t.rating == 1) scheme.primary else scheme.onSurfaceVariant,
                                        )
                                        Icon(
                                            if (t.rating == -1) Icons.Filled.ThumbDown else Icons.Outlined.ThumbDown, "Bad answer",
                                            Modifier.clip(CircleShape).clickable { correcting = i }.padding(6.dp).size(16.dp),
                                            tint = if (t.rating == -1) scheme.error else scheme.onSurfaceVariant,
                                        )
                                    }
                                }
                                if (t.sources.isNotEmpty() && !t.writing) FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(top = 4.dp)) {
                                    t.sources.forEach { s -> AssistChip(onClick = { onOpenThread(s.threadId) }, label = { Text(s.label) }) }
                                }
                            }
                        }
                    }
                    if (i == turns.lastIndex && step != BrainChat.Step.IDLE && (step != BrainChat.Step.WRITING || activity != null)) {
                        Text(
                            activity ?: if (step == BrainChat.Step.SEARCHING) "Looking through your texts…" else "Thinking…",
                            Modifier.padding(start = 36.dp, top = 8.dp),
                            style = MaterialTheme.typography.bodySmall, fontStyle = FontStyle.Italic, color = scheme.onSurfaceVariant,
                        )
                    }
                }
            }

            Surface(color = scheme.surfaceContainer) {
              Column {
                picture?.let { f ->
                    Row(Modifier.padding(start = 12.dp, top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        val img = rememberImage(android.net.Uri.fromFile(f), 160)
                        if (img != null) androidx.compose.foundation.Image(img, null, Modifier.size(56.dp).clip(RoundedCornerShape(10.dp)), contentScale = androidx.compose.ui.layout.ContentScale.Crop)
                        Text("Ask anything about this picture, or just send it", Modifier.weight(1f).padding(horizontal = 10.dp), style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant)
                        IconButton(onClick = { f.delete(); picture = null }) { Icon(Icons.Outlined.Close, "Remove picture") }
                    }
                }
                if (listening) Row(Modifier.fillMaxWidth().padding(16.dp, 12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Outlined.Mic, null, tint = scheme.error)
                    Text("Listening… tap the button when you're done", Modifier.weight(1f).padding(horizontal = 10.dp), style = MaterialTheme.typography.bodyMedium)
                    TextButton(onClick = { recorder.cancel(); listening = false }) { Text("Cancel") }
                }
                Row(Modifier.padding(8.dp), verticalAlignment = Alignment.Bottom) {
                    IconButton(
                        onClick = { pickPicture.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) },
                        enabled = step == BrainChat.Step.IDLE && !listening,
                        modifier = Modifier.padding(bottom = 4.dp),
                    ) { Icon(Icons.Outlined.AddPhotoAlternate, "Ask about a picture", tint = scheme.primary) }
                    TextField(
                        value = input,
                        onValueChange = { input = it },
                        placeholder = { Text("Ask Murmur Brain") },
                        modifier = Modifier.weight(1f).heightIn(min = 52.dp, max = 160.dp),
                        shape = RoundedCornerShape(26.dp),
                        colors = TextFieldDefaults.colors(
                            focusedIndicatorColor = Color.Transparent, unfocusedIndicatorColor = Color.Transparent,
                            focusedContainerColor = scheme.surfaceContainerHighest, unfocusedContainerColor = scheme.surfaceContainerHighest,
                        ),
                    )
                    Spacer(Modifier.width(8.dp))
                    val idle = step == BrainChat.Step.IDLE
                    // Nothing typed and no picture: the button is a microphone.
                    val mic = input.isBlank() && picture == null
                    val canSend = idle && (listening || mic || input.isNotBlank() || picture != null)
                    IconButton(
                        onClick = {
                            when {
                                listening -> { listening = false; recorder.stop()?.let { BrainChat.sendVoice(context, it) } }
                                picture != null -> { BrainChat.sendPicture(context, input, picture!!); picture = null; input = "" }
                                mic -> if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) listening = recorder.start()
                                    else micPermission.launch(Manifest.permission.RECORD_AUDIO)
                                else -> { BrainChat.send(context, input); input = "" }
                            }
                        },
                        enabled = canSend,
                        modifier = Modifier.padding(bottom = 2.dp).size(50.dp).clip(CircleShape)
                            .background(if (listening) scheme.error else if (canSend) scheme.primary else scheme.surfaceVariant),
                    ) {
                        Icon(
                            when { listening -> Icons.Filled.Stop; mic -> Icons.Outlined.Mic; else -> Icons.AutoMirrored.Filled.Send },
                            when { listening -> "Done"; mic -> "Ask out loud"; else -> "Ask" },
                            tint = if (canSend) scheme.onPrimary else scheme.onSurfaceVariant,
                        )
                    }
                }
              }
            }
        }
    }

    correcting?.let { index ->
        var better by remember(index) { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { correcting = null },
            title = { Text("Teach Murmur Brain") },
            text = {
                Column {
                    Text(
                        "What should it have said? Next time someone asks something like this, it sees your answer as an example. Leave it empty to just mark this answer as bad.",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    OutlinedTextField(better, { better = it }, Modifier.fillMaxWidth().padding(top = 12.dp), label = { Text("A better answer (optional)") }, minLines = 3)
                }
            },
            confirmButton = { TextButton(onClick = { BrainChat.rate(context, index, good = false, better = better); correcting = null }) { Text("Save") } },
            dismissButton = { TextButton(onClick = { correcting = null }) { Text("Cancel") } },
        )
    }
}

/** A drafted text: edit it, pick who, then Send, Schedule or Cancel. Nothing goes out on its own. */
@Composable
internal fun SendCardView(index: Int, card: BrainChat.SendCard) {
    val context = LocalContext.current
    val scheme = MaterialTheme.colorScheme
    var later by remember { mutableStateOf(false) }
    Surface(
        color = scheme.surfaceContainer, shape = RoundedCornerShape(18.dp),
        modifier = Modifier.padding(top = 6.dp).fillMaxWidth().border(1.dp, scheme.outlineVariant, RoundedCornerShape(18.dp)),
    ) {
        Column(Modifier.padding(12.dp)) {
            Text("To ${card.name}  ·  ${Phone.pretty(card.number)}", style = MaterialTheme.typography.labelLarge, color = scheme.primary)
            if (card.state == BrainChat.SendState.WAITING && card.alternatives.isNotEmpty()) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("Not them?", style = MaterialTheme.typography.labelSmall, color = scheme.onSurfaceVariant, modifier = Modifier.align(Alignment.CenterVertically))
                    card.alternatives.forEach { (n, num) ->
                        SuggestionChip(onClick = { BrainChat.switchRecipient(context, index, n, num) }, label = { Text(n) })
                    }
                }
            }
            Spacer(Modifier.height(6.dp))
            if (card.state == BrainChat.SendState.WAITING) {
                TextField(
                    value = card.message,
                    onValueChange = { BrainChat.editCard(context, index, it) },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(14.dp),
                    colors = TextFieldDefaults.colors(
                        focusedIndicatorColor = Color.Transparent, unfocusedIndicatorColor = Color.Transparent,
                        focusedContainerColor = scheme.surfaceContainerHighest, unfocusedContainerColor = scheme.surfaceContainerHighest,
                    ),
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Button(onClick = { BrainChat.confirm(context, index) }, enabled = card.message.isNotBlank()) { Text("Send") }
                    Spacer(Modifier.width(8.dp))
                    Box {
                        TextButton(onClick = { later = true }) { Text("Schedule") }
                        DropdownMenu(expanded = later, onDismissRequest = { later = false }) {
                            val now = System.currentTimeMillis()
                            listOf(
                                "In 1 hour" to now + 3_600_000L,
                                "Tonight at 8" to When.nextAt(20),
                                "Tomorrow at 9 AM" to When.nextAt(9).let { if (it - now < 3_600_000L * 4) it + 86_400_000L else it },
                            ).forEach { (label, at) ->
                                DropdownMenuItem(
                                    text = { Text("$label  ·  ${When.future(context, at)}") },
                                    onClick = { later = false; BrainChat.confirm(context, index, at) },
                                )
                            }
                        }
                    }
                    TextButton(onClick = { BrainChat.cancelCard(context, index) }) { Text("Cancel") }
                }
            } else {
                Text(card.message, style = MaterialTheme.typography.bodyLarge)
                Spacer(Modifier.height(4.dp))
                Text(
                    when (card.state) {
                        BrainChat.SendState.SENT -> "✓ Sent " + When.short(context, card.at) + ". Open the conversation within a few seconds to undo."
                        BrainChat.SendState.SCHEDULED -> "⏱ Scheduled for " + When.future(context, card.at)
                        else -> "Cancelled"
                    },
                    style = MaterialTheme.typography.labelMedium,
                    color = if (card.state == BrainChat.SendState.CANCELLED) scheme.onSurfaceVariant else scheme.primary,
                )
            }
        }
    }
}
