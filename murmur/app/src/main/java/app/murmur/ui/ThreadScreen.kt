package app.murmur.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material.icons.outlined.Bedtime
import androidx.compose.material.icons.outlined.Call
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.NotificationsActive
import androidx.compose.material.icons.outlined.PushPin
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
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
import androidx.compose.material3.TimePicker
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.murmur.data.Box as MsgBox
import app.murmur.data.Conversation
import app.murmur.data.Delivery
import app.murmur.data.Message
import app.murmur.data.Pending
import app.murmur.data.PendingStore
import app.murmur.data.Phone
import app.murmur.data.Prefs
import app.murmur.data.SmsRepository
import app.murmur.data.Vibes
import app.murmur.notify.Notifier
import app.murmur.sms.Outbox
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Calendar
import java.util.TimeZone

internal sealed interface Item {
    val key: String
    data class Day(val t: Long) : Item { override val key = "d$t" }
    data class Msg(val m: Message, val firstOfRun: Boolean, val lastOfRun: Boolean) : Item { override val key = m.key }
    data class Wait(val p: Pending) : Item { override val key = "p${p.id}" }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ThreadScreen(threadId: Long, prefill: String, version: Int, isDefault: Boolean, onBack: () -> Unit) {
    val context = LocalContext.current
    val repo = remember { SmsRepository(context) }
    val prefs = remember { Prefs(context) }
    val scope = rememberCoroutineScope()

    var convo by remember { mutableStateOf<Conversation?>(null) }
    var messages by remember { mutableStateOf<List<Message>>(emptyList()) }
    var loaded by remember { mutableStateOf(false) }
    var text by remember { mutableStateOf(prefill.ifBlank { prefs.draft(threadId) }) }
    var menu by remember { mutableStateOf(false) }
    var scheduleSheet by remember { mutableStateOf(false) }
    var customPicker by remember { mutableStateOf(false) }
    var sleepPrompt by remember { mutableStateOf(false) }
    var actionsFor by remember { mutableStateOf<Message?>(null) }
    var confirmDelete by remember { mutableStateOf(false) }
    var remindSheet by remember { mutableStateOf(false) }
    var reminder by remember { mutableStateOf(Notifier.reminderFor(context, threadId)) }
    var pinned by remember { mutableStateOf(threadId in prefs.pinned()) }
    val allPending by PendingStore.get(context).all.collectAsState()
    val pending = allPending.filter { it.threadId == threadId }
    val listState = rememberLazyListState()

    LaunchedEffect(threadId, version) {
        val (c, m) = withContext(Dispatchers.IO) {
            repo.markRead(threadId)
            (repo.conversation(threadId) ?: fallbackConversation(repo, threadId)) to repo.messages(threadId)
        }
        Notifier.cancel(context, threadId)
        convo = c; messages = m; loaded = true
    }
    LaunchedEffect(text) {
        delay(400)
        prefs.setDraft(threadId, text)
    }

    val address = convo?.addresses?.joinToString(" ").orEmpty()

    fun doSend(body: String) {
        if (body.isBlank() || address.isBlank()) return
        Outbox.send(context, threadId, address, body.trim())
        text = ""
        prefs.setDraft(threadId, "")
        scope.launch { listState.animateScrollToItem(0) }
    }

    fun doSchedule(at: Long) {
        if (text.isBlank() || address.isBlank()) return
        Outbox.schedule(context, threadId, address, text.trim(), at)
        Toast.makeText(context, "Scheduled for ${When.future(context, at)}", Toast.LENGTH_SHORT).show()
        text = ""
        prefs.setDraft(threadId, "")
    }

    fun trySend() {
        val heat = Vibes.heat(text)
        if (prefs.sleepOnIt && Vibes.isLateNight() && heat != Vibes.Heat.CALM) sleepPrompt = true
        else doSend(text)
    }

    val rows = remember(messages, pending) { buildRows(messages, pending) }

    Scaffold(
        topBar = {
            TopAppBar(
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
                title = {
                    val c = convo
                    if (c != null) Row(verticalAlignment = Alignment.CenterVertically) {
                        Avatar(c.title, c.addresses.firstOrNull() ?: c.title, c.photo, 38.dp)
                        Spacer(Modifier.width(12.dp))
                        Column {
                            Text(c.title, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            if (c.known && c.addresses.size == 1) Text(
                                Phone.pretty(c.addresses[0]),
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                },
                actions = {
                    if (convo?.addresses?.size == 1) IconButton(onClick = {
                        context.startActivity(Intent(Intent.ACTION_DIAL, Uri.parse("tel:" + Uri.encode(convo!!.addresses[0]))))
                    }) { Icon(Icons.Outlined.Call, "Call") }
                    Box {
                        IconButton(onClick = { menu = true }) { Icon(Icons.Outlined.MoreVert, "More") }
                        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                            DropdownMenuItem(
                                text = { Text("Remind me to reply") },
                                leadingIcon = { Icon(Icons.Outlined.NotificationsActive, null) },
                                onClick = { menu = false; remindSheet = true },
                            )
                            DropdownMenuItem(
                                text = { Text(if (pinned) "Unpin" else "Pin to top") },
                                leadingIcon = { Icon(if (pinned) Icons.Outlined.PushPin else Icons.Filled.PushPin, null) },
                                onClick = { menu = false; pinned = !pinned; prefs.setPinned(threadId, pinned) },
                            )
                            DropdownMenuItem(
                                text = { Text("Delete conversation") },
                                leadingIcon = { Icon(Icons.Outlined.Delete, null) },
                                onClick = { menu = false; confirmDelete = true },
                            )
                        }
                    }
                },
            )
        },
    ) { pad ->
        Column(Modifier.fillMaxSize().padding(top = pad.calculateTopPadding()).imePadding()) {
            reminder?.let { at ->
                AssistChip(
                    onClick = { Notifier.clearReminder(context, threadId); reminder = null },
                    label = { Text("Reminder ${When.future(context, at)}  ·  tap to clear") },
                    leadingIcon = { Icon(Icons.Outlined.NotificationsActive, null, Modifier.size(18.dp)) },
                    modifier = Modifier.padding(horizontal = 16.dp),
                    colors = AssistChipDefaults.assistChipColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
                )
            }
            LazyColumn(
                state = listState,
                reverseLayout = true,
                modifier = Modifier.weight(1f).fillMaxWidth(),
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
            ) {
                items(rows, key = { it.key }) { row ->
                    when (row) {
                        is Item.Day -> Text(
                            When.day(context, row.t),
                            Modifier.fillMaxWidth().padding(vertical = 14.dp),
                            textAlign = TextAlign.Center,
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        is Item.Msg -> Bubble(
                            row, convo,
                            seed = convo?.addresses?.firstOrNull().orEmpty(),
                            onLongPress = { actionsFor = row.m },
                        )
                        is Item.Wait -> WaitingBubble(
                            row.p,
                            onUndo = { Outbox.undo(context, row.p.id)?.let { text = it } },
                            onSendNow = { Outbox.sendNow(context, row.p.id) },
                        )
                    }
                }
                if (loaded && rows.isEmpty()) item {
                    Text(
                        "Say hello.",
                        Modifier.fillMaxWidth().padding(48.dp),
                        textAlign = TextAlign.Center,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Composer(
                text = text,
                onText = { text = it },
                enabled = isDefault,
                onSend = ::trySend,
                onSchedule = { scheduleSheet = true },
            )
        }
    }

    // ---------- sheets & dialogs ----------

    if (scheduleSheet) ModalBottomSheet(onDismissRequest = { scheduleSheet = false }) {
        Text("Send later", Modifier.padding(24.dp, 0.dp, 24.dp, 8.dp), style = MaterialTheme.typography.titleLarge)
        val now = System.currentTimeMillis()
        val choices = listOf(
            "In 1 hour" to now + 3_600_000,
            "This evening" to When.nextAt(19),
            "Tomorrow morning" to When.nextAt(8).let { if (it - now < 4 * 3_600_000) it + 86_400_000 else it },
            "Tomorrow at noon" to When.nextAt(12).let { if (it - now < 12 * 3_600_000) it + 86_400_000 else it },
        ).distinctBy { it.second }
        for ((label, at) in choices) ListItem(
            headlineContent = { Text(label) },
            supportingContent = { Text(When.future(context, at)) },
            leadingContent = { Icon(Icons.Outlined.Schedule, null) },
            modifier = Modifier.clickable { scheduleSheet = false; doSchedule(at) },
        )
        ListItem(
            headlineContent = { Text("Pick date & time") },
            leadingContent = { Icon(Icons.Outlined.Schedule, null) },
            modifier = Modifier.clickable { scheduleSheet = false; customPicker = true },
        )
        Spacer(Modifier.height(24.dp))
    }

    if (customPicker) DateTimePicker(onDismiss = { customPicker = false }) { at ->
        customPicker = false
        if (at > System.currentTimeMillis()) doSchedule(at)
        else Toast.makeText(context, "That time has already passed", Toast.LENGTH_SHORT).show()
    }

    if (remindSheet) ModalBottomSheet(onDismissRequest = { remindSheet = false }) {
        Text("Remind me to reply", Modifier.padding(24.dp, 0.dp, 24.dp, 8.dp), style = MaterialTheme.typography.titleLarge)
        val now = System.currentTimeMillis()
        for ((label, at) in listOf(
            "In 1 hour" to now + 3_600_000,
            "In 3 hours" to now + 3 * 3_600_000,
            "This evening" to When.nextAt(19),
            "Tomorrow morning" to When.nextAt(9).let { if (it - now < 4 * 3_600_000) it + 86_400_000 else it },
        ).distinctBy { it.second }) ListItem(
            headlineContent = { Text(label) },
            supportingContent = { Text(When.future(context, at)) },
            leadingContent = { Icon(Icons.Outlined.NotificationsActive, null) },
            modifier = Modifier.clickable {
                Notifier.remindAt(context, threadId, at); reminder = at; remindSheet = false
            },
        )
        Spacer(Modifier.height(24.dp))
    }

    if (sleepPrompt) AlertDialog(
        onDismissRequest = { sleepPrompt = false },
        icon = { Icon(Icons.Outlined.Bedtime, null) },
        title = { Text("Sleep on it?") },
        text = {
            Text(
                "It's ${When.time(context, System.currentTimeMillis())} and this reads a little heated. " +
                    "Murmur can hold it and send it at ${When.future(context, Vibes.nextMorning())}. " +
                    "You can still edit or cancel it before then.",
            )
        },
        confirmButton = {
            TextButton(onClick = { sleepPrompt = false; doSchedule(Vibes.nextMorning()) }) { Text("Send in the morning") }
        },
        dismissButton = {
            TextButton(onClick = { sleepPrompt = false; doSend(text) }) { Text("Send now") }
        },
    )

    actionsFor?.let { m ->
        ModalBottomSheet(onDismissRequest = { actionsFor = null }) {
            Text(
                m.body.ifBlank { "Picture message" }, Modifier.padding(24.dp, 0.dp, 24.dp, 8.dp),
                maxLines = 3, overflow = TextOverflow.Ellipsis, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (m.body.isNotBlank()) ListItem(
                headlineContent = { Text("Copy text") },
                leadingContent = { Icon(Icons.Outlined.ContentCopy, null) },
                modifier = Modifier.clickable { copy(context, m.body); actionsFor = null },
            )
            if (m.delivery == Delivery.FAILED && isDefault) ListItem(
                headlineContent = { Text("Try again") },
                leadingContent = { Icon(Icons.Outlined.Refresh, null) },
                modifier = Modifier.clickable {
                    actionsFor = null
                    scope.launch(Dispatchers.IO) { repo.deleteMessage(m) }
                    Outbox.sendImmediately(context, threadId, address, m.body)
                },
            )
            ListItem(
                headlineContent = { Text("Delete", color = MaterialTheme.colorScheme.error) },
                leadingContent = { Icon(Icons.Outlined.Delete, null, tint = MaterialTheme.colorScheme.error) },
                modifier = Modifier.clickable {
                    actionsFor = null
                    scope.launch(Dispatchers.IO) { repo.deleteMessage(m) }
                },
            )
            Spacer(Modifier.height(24.dp))
        }
    }

    if (confirmDelete) AlertDialog(
        onDismissRequest = { confirmDelete = false },
        title = { Text("Delete conversation?") },
        text = { Text("Every message in this conversation will be removed from this phone.") },
        confirmButton = {
            TextButton(onClick = {
                confirmDelete = false
                scope.launch(Dispatchers.IO) { repo.deleteThread(threadId) }
                onBack()
            }) { Text("Delete", color = MaterialTheme.colorScheme.error) }
        },
        dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Keep") } },
    )
}

private fun fallbackConversation(repo: SmsRepository, threadId: Long): Conversation? {
    // A brand-new thread has no messages yet, so it's missing from the list. Ask the thread itself.
    val addr = repo.messages(threadId, 1).firstOrNull()?.address ?: return threadRecipients(repo, threadId)
    val c = repo.contact(addr)
    return Conversation(threadId, listOf(addr), c.name ?: Phone.pretty(addr), c.photo, "", 0, false, c.name != null, false)
}

private fun threadRecipients(repo: SmsRepository, threadId: Long): Conversation? =
    repo.recipientsOf(threadId)?.let { addrs ->
        val contacts = addrs.map(repo::contact)
        Conversation(
            threadId, addrs,
            addrs.indices.joinToString(", ") { contacts[it].name ?: Phone.pretty(addrs[it]) },
            if (addrs.size == 1) contacts[0].photo else null, "", 0, false, contacts.any { it.name != null }, false,
        )
    }

private fun buildRows(messages: List<Message>, pending: List<Pending>): List<Item> {
    val out = ArrayList<Item>()
    // Newest first, because the list is drawn bottom-up.
    for (p in pending.sortedByDescending { it.at }) out += Item.Wait(p)
    val newestFirst = messages.asReversed()
    for (i in newestFirst.indices) {
        val m = newestFirst[i]
        val older = newestFirst.getOrNull(i + 1)
        val next = newestFirst.getOrNull(i - 1)
        val firstOfRun = older == null || older.box != m.box || older.address != m.address ||
            m.date - older.date > 3 * 60_000 || !When.sameDay(older.date, m.date)
        val lastOfRun = next == null || next.box != m.box || next.address != m.address ||
            next.date - m.date > 3 * 60_000 || !When.sameDay(next.date, m.date)
        out += Item.Msg(m, firstOfRun, lastOfRun)
        if (older == null || !When.sameDay(older.date, m.date)) out += Item.Day(m.date)
    }
    return out
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun Bubble(row: Item.Msg, convo: Conversation?, seed: String, onLongPress: () -> Unit) {
    val context = LocalContext.current
    val m = row.m
    val mine = m.box == MsgBox.OUT
    val accent = Color(Vibes.color(seed.ifEmpty { "x" }))
    val scheme = MaterialTheme.colorScheme
    val big = 22.dp
    val small = 6.dp
    // Runs of messages hug each other on the sender's side.
    val shape = if (mine) RoundedCornerShape(big, if (row.firstOfRun) big else small, if (row.lastOfRun) big else small, big)
    else RoundedCornerShape(if (row.firstOfRun) big else small, big, big, if (row.lastOfRun) big else small)
    val code = if (!mine) Vibes.code(m.body) else null
    val group = (convo?.addresses?.size ?: 1) > 1

    Column(
        Modifier.fillMaxWidth().padding(top = if (row.firstOfRun) 8.dp else 2.dp),
        horizontalAlignment = if (mine) Alignment.End else Alignment.Start,
    ) {
        if (group && !mine && row.firstOfRun) {
            val name = remember(m.address) { SmsRepository(context).contact(m.address).name ?: Phone.pretty(m.address) }
            Text(name, Modifier.padding(start = 14.dp, bottom = 2.dp), style = MaterialTheme.typography.labelSmall, color = accent)
        }
        Box(
            Modifier.widthIn(max = 300.dp).clip(shape)
                .background(
                    if (mine) Brush.linearGradient(
                        listOf(scheme.primary, lerp(scheme.primary, accent, 0.45f)),
                    ) else Brush.linearGradient(listOf(scheme.surfaceContainerHighest, scheme.surfaceContainerHigh)),
                )
                .combinedClickable(onClick = {}, onLongClick = onLongPress)
                .padding(horizontal = 14.dp, vertical = 9.dp),
        ) {
            Column {
                for (img in m.images) {
                    val bmp = rememberImage(img, 900)
                    if (bmp != null) Image(
                        bmp, "Picture",
                        Modifier.widthIn(max = 260.dp).heightIn(max = 320.dp).clip(RoundedCornerShape(14.dp)),
                        contentScale = ContentScale.Fit,
                    )
                    Spacer(Modifier.height(4.dp))
                }
                if (m.body.isNotBlank()) Text(
                    m.body,
                    style = MaterialTheme.typography.bodyLarge,
                    color = if (mine) scheme.onPrimary else scheme.onSurface,
                )
            }
        }
        if (code != null) {
            AssistChip(
                onClick = { copy(context, code) },
                label = { Text("Copy $code") },
                leadingIcon = { Icon(Icons.Outlined.ContentCopy, null, Modifier.size(16.dp)) },
                modifier = Modifier.padding(top = 4.dp),
            )
        }
        if (row.lastOfRun || m.delivery == Delivery.FAILED) {
            Row(Modifier.padding(horizontal = 8.dp, vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
                if (m.delivery == Delivery.FAILED) {
                    Icon(Icons.Outlined.ErrorOutline, null, Modifier.size(14.dp), tint = scheme.error)
                    Spacer(Modifier.width(4.dp))
                }
                Text(
                    buildString {
                        append(When.time(context, m.date))
                        when (m.delivery) {
                            Delivery.SENDING -> append(" · Sending…")
                            Delivery.DELIVERED -> append(" · Delivered")
                            Delivery.FAILED -> append(" · Not sent. Hold to retry")
                            else -> {}
                        }
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = if (m.delivery == Delivery.FAILED) scheme.error else scheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
internal fun WaitingBubble(p: Pending, onUndo: () -> Unit, onSendNow: () -> Unit) {
    val context = LocalContext.current
    val scheme = MaterialTheme.colorScheme
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    val total = remember(p.id) { (p.at - System.currentTimeMillis()).coerceAtLeast(1) }
    if (!p.scheduled) LaunchedEffect(p.id) {
        while (now < p.at) { delay(100); now = System.currentTimeMillis() }
    }
    val left = ((p.at - now).coerceAtLeast(0)).toFloat() / total
    val progress by animateFloatAsState(left, tween(100, easing = LinearEasing), label = "undo")

    Column(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalAlignment = Alignment.End) {
        Box(
            Modifier.widthIn(max = 300.dp)
                .clip(RoundedCornerShape(22.dp, 22.dp, 6.dp, 22.dp))
                .border(1.5.dp, if (p.scheduled) scheme.secondary else scheme.primary, RoundedCornerShape(22.dp, 22.dp, 6.dp, 22.dp))
                .background(scheme.surfaceContainer)
                .padding(horizontal = 14.dp, vertical = 9.dp),
        ) {
            Text(p.body, style = MaterialTheme.typography.bodyLarge, color = scheme.onSurface)
        }
        Row(
            Modifier.padding(top = 4.dp).animateContentSize(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            if (p.scheduled) {
                Icon(Icons.Outlined.Schedule, null, Modifier.size(14.dp), tint = scheme.secondary)
                Text(When.future(context, p.at), style = MaterialTheme.typography.labelMedium, color = scheme.secondary)
                TextButton(onClick = onUndo) { Text("Edit") }
                TextButton(onClick = onSendNow) { Text("Send now") }
            } else {
                Box(contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(
                        progress = { progress }, Modifier.size(22.dp), strokeWidth = 2.5.dp,
                        color = scheme.primary, trackColor = scheme.surfaceVariant,
                    )
                    Text(
                        "${((p.at - now + 999) / 1000).coerceAtLeast(0)}",
                        style = MaterialTheme.typography.labelSmall,
                    )
                }
                TextButton(onClick = onUndo) { Text("Undo", fontWeight = FontWeight.Bold) }
                TextButton(onClick = onSendNow) { Text("Send now") }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun Composer(text: String, onText: (String) -> Unit, enabled: Boolean, onSend: () -> Unit, onSchedule: () -> Unit) {
    val context = LocalContext.current
    val prefs = remember { Prefs(context) }
    val scheme = MaterialTheme.colorScheme
    val heat = if (prefs.heatMeter) Vibes.heat(text) else Vibes.Heat.CALM
    val ring by animateColorAsState(
        when (heat) {
            Vibes.Heat.CALM -> scheme.primary
            Vibes.Heat.WARM -> Ember
            Vibes.Heat.HOT -> Flame
        },
        tween(400), label = "heat",
    )
    val (segments, left) = Vibes.segments(text)

    Surface(color = scheme.surfaceContainer) {
        Column(Modifier.navigationBarsPadding().padding(8.dp)) {
            if (!enabled) {
                Text(
                    "Make Murmur your default messaging app to send from here.",
                    Modifier.fillMaxWidth().padding(12.dp),
                    style = MaterialTheme.typography.bodyMedium,
                    color = scheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
                return@Column
            }
            if (heat == Vibes.Heat.HOT && text.isNotBlank()) {
                Text(
                    "This reads a little heated.",
                    Modifier.padding(start = 16.dp, bottom = 4.dp),
                    style = MaterialTheme.typography.labelMedium,
                    color = Flame,
                )
            }
            Row(verticalAlignment = Alignment.Bottom) {
                TextField(
                    value = text,
                    onValueChange = onText,
                    placeholder = { Text("Message") },
                    modifier = Modifier.weight(1f).heightIn(min = 52.dp, max = 180.dp),
                    shape = RoundedCornerShape(26.dp),
                    colors = TextFieldDefaults.colors(
                        focusedIndicatorColor = Color.Transparent,
                        unfocusedIndicatorColor = Color.Transparent,
                        focusedContainerColor = scheme.surfaceContainerHighest,
                        unfocusedContainerColor = scheme.surfaceContainerHighest,
                    ),
                    supportingText = if (text.isNotEmpty() && (segments > 1 || left < 20)) {
                        { Text(if (segments > 1) "$left · $segments texts" else "$left left") }
                    } else null,
                )
                Spacer(Modifier.width(8.dp))
                val active = text.isNotBlank()
                Box(
                    Modifier.padding(bottom = 2.dp).size(50.dp).clip(CircleShape)
                        .background(if (active) ring else scheme.surfaceVariant)
                        .combinedClickable(
                            enabled = active,
                            onClick = onSend,
                            onLongClick = onSchedule,
                            onClickLabel = "Send",
                            onLongClickLabel = "Send later",
                        ),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        Icons.AutoMirrored.Filled.Send, "Send (hold to schedule)",
                        tint = if (active) scheme.onPrimary else scheme.onSurfaceVariant,
                    )
                }
            }
            if (text.isNotBlank()) Text(
                "Hold send to schedule",
                Modifier.align(Alignment.End).padding(end = 6.dp, top = 2.dp),
                style = MaterialTheme.typography.labelSmall,
                color = scheme.onSurfaceVariant.copy(alpha = 0.7f),
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DateTimePicker(onDismiss: () -> Unit, onPicked: (Long) -> Unit) {
    var stage by remember { mutableStateOf(0) }
    val date = rememberDatePickerState(
        initialSelectedDateMillis = System.currentTimeMillis(),
        selectableDates = object : androidx.compose.material3.SelectableDates {
            override fun isSelectableDate(utcTimeMillis: Long) =
                utcTimeMillis >= System.currentTimeMillis() - 86_400_000
        },
    )
    val nowCal = Calendar.getInstance()
    val time = rememberTimePickerState(
        initialHour = (nowCal.get(Calendar.HOUR_OF_DAY) + 1) % 24,
        initialMinute = 0,
    )
    if (stage == 0) {
        DatePickerDialog(
            onDismissRequest = onDismiss,
            confirmButton = { TextButton(onClick = { stage = 1 }) { Text("Next") } },
            dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
        ) { DatePicker(date) }
    } else {
        AlertDialog(
            onDismissRequest = onDismiss,
            title = { Text("What time?") },
            text = { TimePicker(time) },
            confirmButton = {
                TextButton(onClick = {
                    // The date picker reports midnight UTC of the chosen day.
                    val utc = Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply {
                        timeInMillis = date.selectedDateMillis ?: System.currentTimeMillis()
                    }
                    val local = Calendar.getInstance().apply {
                        set(utc.get(Calendar.YEAR), utc.get(Calendar.MONTH), utc.get(Calendar.DAY_OF_MONTH), time.hour, time.minute, 0)
                        set(Calendar.MILLISECOND, 0)
                    }
                    onPicked(local.timeInMillis)
                }) { Text("Schedule") }
            },
            dismissButton = { TextButton(onClick = { stage = 0 }) { Text("Back") } },
        )
    }
}

private fun copy(context: android.content.Context, text: String) {
    context.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("Message", text))
    if (android.os.Build.VERSION.SDK_INT < 33) Toast.makeText(context, "Copied", Toast.LENGTH_SHORT).show()
}
