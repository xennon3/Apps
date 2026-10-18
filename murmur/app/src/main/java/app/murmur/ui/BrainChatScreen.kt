package app.murmur.ui

import androidx.compose.foundation.background
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

private val suggestions = listOf(
    "What's the wifi password someone sent me?",
    "When did Mom say she's coming?",
    "Give me 3 quick dinner ideas",
    "Help me word a birthday text for my sister",
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BrainChatScreen(onOpenThread: (Long) -> Unit, onSettings: () -> Unit) {
    val context = LocalContext.current
    LaunchedEffect(Unit) { Brain.refresh(context); BrainChat.load(context); Brain.warmUp(context) }
    val state by Brain.state.collectAsState()
    val turns by BrainChat.turns.collectAsState()
    val step by BrainChat.step.collectAsState()
    var input by rememberSaveable { mutableStateOf("") }
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
                        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.CenterEnd) {
                            Text(
                                t.text,
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
                                if (t.searched.isNotEmpty()) Row(verticalAlignment = Alignment.CenterVertically) {
                                    Icon(Icons.Outlined.Search, null, Modifier.size(12.dp), tint = scheme.onSurfaceVariant)
                                    Spacer(Modifier.width(4.dp))
                                    Text(
                                        "Looked in your texts for: " + t.searched.joinToString(", "),
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
                                if (t.sources.isNotEmpty() && !t.writing) FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(top = 4.dp)) {
                                    t.sources.forEach { s -> AssistChip(onClick = { onOpenThread(s.threadId) }, label = { Text(s.label) }) }
                                }
                            }
                        }
                    }
                    if (i == turns.lastIndex && step != BrainChat.Step.IDLE && step != BrainChat.Step.WRITING) {
                        Text(
                            if (step == BrainChat.Step.SEARCHING) "Looking through your texts…" else "Thinking…",
                            Modifier.padding(start = 36.dp, top = 8.dp),
                            style = MaterialTheme.typography.bodySmall, fontStyle = FontStyle.Italic, color = scheme.onSurfaceVariant,
                        )
                    }
                }
            }

            Surface(color = scheme.surfaceContainer) {
                Row(Modifier.padding(8.dp), verticalAlignment = Alignment.Bottom) {
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
                    val canSend = input.isNotBlank() && step == BrainChat.Step.IDLE
                    IconButton(
                        onClick = { BrainChat.send(context, input); input = "" },
                        enabled = canSend,
                        modifier = Modifier.padding(bottom = 2.dp).size(50.dp).clip(CircleShape)
                            .background(if (canSend) scheme.primary else scheme.surfaceVariant),
                    ) {
                        Icon(Icons.AutoMirrored.Filled.Send, "Ask", tint = if (canSend) scheme.onPrimary else scheme.onSurfaceVariant)
                    }
                }
            }
        }
    }
}
