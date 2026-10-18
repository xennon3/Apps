package app.murmur.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Chat
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.DoneAll
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.PushPin
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.murmur.data.Conversation
import app.murmur.data.PendingStore
import app.murmur.data.Prefs
import app.murmur.data.SmsRepository
import app.murmur.data.ScamShield
import app.murmur.data.Vibes
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private enum class Filter(val label: String) { ALL("All"), UNREAD("Unread"), PEOPLE("People"), UNKNOWN("Unknown"), CODES("Codes"), SUSPICIOUS("Suspicious") }

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun ConversationsScreen(
    version: Int,
    isDefault: Boolean,
    onOpen: (Long) -> Unit,
    onNew: () -> Unit,
    onSettings: () -> Unit,
    onScheduled: () -> Unit,
    onMakeDefault: () -> Unit,
) {
    val context = LocalContext.current
    val repo = remember { SmsRepository(context) }
    val prefs = remember { Prefs(context) }
    val scope = rememberCoroutineScope()
    var conversations by remember { mutableStateOf<List<Conversation>?>(null) }
    var filter by rememberSaveable { mutableStateOf(Filter.ALL) }
    var searching by rememberSaveable { mutableStateOf(false) }
    var query by rememberSaveable { mutableStateOf("") }
    var hits by remember { mutableStateOf<List<SmsRepository.Hit>>(emptyList()) }
    var sheetFor by remember { mutableStateOf<Conversation?>(null) }
    var confirmDelete by remember { mutableStateOf<Conversation?>(null) }
    var localBump by remember { mutableStateOf(0) }
    val pending by PendingStore.get(context).all.collectAsState()

    LaunchedEffect(version, localBump) {
        conversations = withContext(Dispatchers.IO) { runCatching { repo.conversations() }.getOrDefault(emptyList()) }
    }
    LaunchedEffect(query) {
        if (query.length < 2) { hits = emptyList(); return@LaunchedEffect }
        delay(180)
        hits = withContext(Dispatchers.IO) { repo.searchMessages(query) }
    }

    val scheduledThreads = pending.filter { it.scheduled }.groupingBy { it.threadId }.eachCount()
    val shown = conversations.orEmpty().filter { c ->
        when (filter) {
            Filter.ALL -> true
            Filter.UNREAD -> c.unread
            Filter.PEOPLE -> c.known
            Filter.UNKNOWN -> !c.known
            Filter.CODES -> Vibes.code(c.snippet) != null
            Filter.SUSPICIOUS -> c.suspicious()
        }
    }.filter { c ->
        query.isBlank() || c.title.contains(query, true) || c.addresses.any { it.contains(query) }
    }

    val scrollBehavior = TopAppBarDefaults.enterAlwaysScrollBehavior()
    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            TopAppBar(
                scrollBehavior = scrollBehavior,
                title = {
                    if (searching) {
                        TextField(
                            value = query, onValueChange = { query = it },
                            placeholder = { Text("Search people and messages") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                            colors = TextFieldDefaults.colors(
                                focusedContainerColor = Color.Transparent,
                                unfocusedContainerColor = Color.Transparent,
                                focusedIndicatorColor = Color.Transparent,
                                unfocusedIndicatorColor = Color.Transparent,
                            ),
                        )
                    } else {
                        Text("Murmur", style = MaterialTheme.typography.headlineMedium)
                    }
                },
                actions = {
                    IconButton(onClick = { searching = !searching; if (!searching) query = "" }) {
                        Icon(if (searching) Icons.Outlined.Close else Icons.Outlined.Search, "Search")
                    }
                    if (pending.any { it.scheduled }) IconButton(onClick = onScheduled) {
                        BadgedBox(badge = { Badge { Text("${pending.count { it.scheduled }}") } }) {
                            Icon(Icons.Outlined.Schedule, "Scheduled messages")
                        }
                    }
                    IconButton(onClick = onSettings) { Icon(Icons.Outlined.Settings, "Settings") }
                },
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = onNew,
                icon = { Icon(Icons.Outlined.Edit, null) },
                text = { Text("New") },
                containerColor = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary,
            )
        },
    ) { pad ->
        LazyColumn(
            Modifier.fillMaxSize(),
            contentPadding = PaddingValues(top = pad.calculateTopPadding(), bottom = 96.dp + pad.calculateBottomPadding()),
        ) {
            if (!isDefault) item("banner") {
                Surface(
                    color = MaterialTheme.colorScheme.secondaryContainer,
                    shape = RoundedCornerShape(20.dp),
                    modifier = Modifier.padding(16.dp, 4.dp).fillMaxWidth(),
                    onClick = onMakeDefault,
                ) {
                    Column(Modifier.padding(16.dp)) {
                        Text("Murmur isn't your messaging app yet", fontWeight = FontWeight.SemiBold)
                        Text(
                            "New texts still go to your old app. Tap to switch.",
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                }
            }
            item("filters") {
                LazyRow(
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    items(Filter.entries) { f ->
                        FilterChip(selected = filter == f, onClick = { filter = f }, label = { Text(f.label) })
                    }
                }
            }
            if (conversations == null) return@LazyColumn
            if (shown.isEmpty() && hits.isEmpty()) item("empty") { Empty(filter, query) }
            items(shown, key = { it.threadId }) { c ->
                Row(
                    Modifier.fillMaxWidth()
                        .combinedClickable(onClick = { onOpen(c.threadId) }, onLongClick = { sheetFor = c })
                        .padding(horizontal = 16.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box {
                        Avatar(c.title, c.addresses.firstOrNull() ?: c.title, c.photo)
                        if (c.unread) Box(
                            Modifier.align(Alignment.TopEnd).size(14.dp).clip(CircleShape)
                                .background(MaterialTheme.colorScheme.background).padding(2.dp)
                                .clip(CircleShape).background(MaterialTheme.colorScheme.secondary),
                        )
                    }
                    Spacer(Modifier.width(14.dp))
                    Column(Modifier.weight(1f)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            if (c.pinned) {
                                Icon(Icons.Filled.PushPin, "Pinned", Modifier.size(14.dp), tint = MaterialTheme.colorScheme.primary)
                                Spacer(Modifier.width(4.dp))
                            }
                            Text(
                                c.title, Modifier.weight(1f, fill = false),
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = if (c.unread) FontWeight.Bold else FontWeight.SemiBold,
                                maxLines = 1, overflow = TextOverflow.Ellipsis,
                            )
                            Spacer(Modifier.weight(1f))
                            Text(
                                When.short(context, c.date),
                                style = MaterialTheme.typography.labelMedium,
                                color = if (c.unread) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            val draft = remember(c.threadId, version) { prefs.draft(c.threadId) }
                            val n = scheduledThreads[c.threadId] ?: 0
                            if (c.suspicious()) {
                                Text(
                                    "⚠ Possible scam  ",
                                    style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.error,
                                )
                            }
                            if (n > 0) {
                                Icon(Icons.Outlined.Schedule, null, Modifier.size(14.dp), tint = MaterialTheme.colorScheme.secondary)
                                Spacer(Modifier.width(4.dp))
                            }
                            Text(
                                if (draft.isNotBlank()) "Draft: $draft" else c.snippet,
                                style = MaterialTheme.typography.bodyMedium,
                                color = when {
                                    draft.isNotBlank() -> MaterialTheme.colorScheme.secondary
                                    c.unread -> MaterialTheme.colorScheme.onSurface
                                    else -> MaterialTheme.colorScheme.onSurfaceVariant
                                },
                                maxLines = 2, overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }
            }
            if (hits.isNotEmpty()) {
                item("hits") {
                    Text(
                        "In messages", Modifier.padding(16.dp, 16.dp, 16.dp, 4.dp),
                        style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary,
                    )
                }
                items(hits, key = { "hit${it.id}" }) { h ->
                    val title = conversations.orEmpty().firstOrNull { it.threadId == h.threadId }?.title ?: h.address
                    ListItem(
                        headlineContent = { Text(title, maxLines = 1) },
                        supportingContent = { Text(h.body, maxLines = 2, overflow = TextOverflow.Ellipsis) },
                        trailingContent = { Text(When.short(context, h.date), style = MaterialTheme.typography.labelSmall) },
                        modifier = Modifier.clickable { onOpen(h.threadId) },
                    )
                }
            }
        }
    }

    sheetFor?.let { c ->
        ModalBottomSheet(onDismissRequest = { sheetFor = null }) {
            Text(c.title, Modifier.padding(24.dp, 0.dp, 24.dp, 8.dp), style = MaterialTheme.typography.titleLarge)
            SheetAction(if (c.pinned) Icons.Outlined.PushPin else Icons.Filled.PushPin, if (c.pinned) "Unpin" else "Pin to top") {
                prefs.setPinned(c.threadId, !c.pinned); sheetFor = null; localBump++
            }
            if (c.unread) SheetAction(Icons.Outlined.DoneAll, "Mark as read") {
                scope.launch(Dispatchers.IO) { repo.markRead(c.threadId) }; sheetFor = null
            }
            SheetAction(Icons.AutoMirrored.Outlined.Chat, "Open") { sheetFor = null; onOpen(c.threadId) }
            SheetAction(Icons.Outlined.Delete, "Delete conversation", danger = true) {
                confirmDelete = c; sheetFor = null
            }
            Spacer(Modifier.height(24.dp))
        }
    }

    confirmDelete?.let { c ->
        AlertDialog(
            onDismissRequest = { confirmDelete = null },
            title = { Text("Delete conversation?") },
            text = { Text("Every message with ${c.title} will be removed from this phone. This can't be undone.") },
            confirmButton = {
                TextButton(onClick = {
                    scope.launch(Dispatchers.IO) { repo.deleteThread(c.threadId) }
                    confirmDelete = null
                }) { Text("Delete", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = null }) { Text("Keep") } },
        )
    }
}

@Composable
private fun SheetAction(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, danger: Boolean = false, onClick: () -> Unit) {
    val color = if (danger) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface
    ListItem(
        leadingContent = { Icon(icon, null, tint = color) },
        headlineContent = { Text(label, color = color) },
        modifier = Modifier.clickable(onClick = onClick),
    )
}

@Composable
private fun Empty(filter: Filter, query: String) {
    val (title, body) = when {
        query.isNotBlank() -> "Nothing matches \"$query\"" to "Try a name, a number or a word from the message."
        filter == Filter.UNREAD -> "All caught up" to "Nothing is waiting for you."
        filter == Filter.CODES -> "No codes" to "Login and verification codes show up here, with a Copy button."
        filter == Filter.UNKNOWN -> "No strangers" to "Texts from numbers not in your contacts land here."
        filter == Filter.SUSPICIOUS -> "Nothing fishy" to "Texts that look like scams (fake tolls, parcels, banks) show up here with a warning."
        else -> "Quiet in here" to "Start a conversation with the New button."
    }
    Column(
        Modifier.fillMaxWidth().padding(32.dp, 64.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(title, style = MaterialTheme.typography.titleLarge)
        Spacer(Modifier.height(8.dp))
        Text(body, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

private fun Conversation.suspicious() =
    addresses.size == 1 && ScamShield.analyze(snippet, addresses[0], known).flagged
