package app.murmur.ui

import android.Manifest
import android.util.Log
import kotlinx.coroutines.CancellationException
import android.content.pm.PackageManager
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.material.icons.outlined.GppMaybe
import androidx.compose.material.icons.outlined.Mic
import androidx.compose.material.icons.outlined.Repeat
import androidx.compose.material3.FilterChip
import androidx.compose.material3.SuggestionChip
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withLink
import androidx.core.content.ContextCompat
import app.murmur.brain.Brain
import app.murmur.brain.Eyes
import app.murmur.brain.ReplyStyle
import androidx.compose.material.icons.outlined.AutoAwesome
import app.murmur.data.Repeat
import app.murmur.brain.BrainPrompts
import app.murmur.brain.PlanTime
import app.murmur.brain.BrainMemory
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import android.provider.CalendarContract
import androidx.compose.material.icons.outlined.Event
import app.murmur.data.ScamShield
import app.murmur.voice.Voice
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.material.icons.outlined.AddPhotoAlternate
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.SimCard
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import app.murmur.data.Sims
import app.murmur.mms.Mms
import java.io.File
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
    var attachment by remember { mutableStateOf<String?>(null) }
    var preparing by remember { mutableStateOf(false) }
    val sims = remember { Sims.active(context) }
    var subId by remember { mutableIntStateOf(prefs.sim(threadId) ?: -1) }
    var viewing by remember { mutableStateOf<Uri?>(null) }
    var repeatKind by remember { mutableStateOf<Repeat.Kind?>(null) }
    var linkPrompt by remember { mutableStateOf<Pair<String, ScamShield.Verdict?>?>(null) }
    var scamTick by remember { mutableIntStateOf(0) }
    var transcripts by remember { mutableStateOf<Map<String, String>>(emptyMap()) }
    val brainState by Brain.state.collectAsState()
    val brainReady = brainState == Brain.State.Ready
    var brainVerdicts by remember { mutableStateOf<Map<String, ScamShield.Verdict>>(emptyMap()) }
    var plans by remember { mutableStateOf<Map<String, Pair<BrainPrompts.Plan, PlanTime.When>>>(emptyMap()) }
    var unreadAtOpen by remember { mutableIntStateOf(-1) }
    var catchUp by remember { mutableStateOf<String?>(null) }
    var catchingUp by remember { mutableStateOf(false) }
    var brainSheet by remember { mutableStateOf(false) }
    var brainStatus by remember { mutableStateOf<String?>(null) }
    var replies by remember { mutableStateOf<List<String>>(emptyList()) }
    var replyTranscript by remember { mutableStateOf("") }
    var pickedReply by remember { mutableStateOf<String?>(null) }
    var factOffer by remember { mutableStateOf<Pair<String, String>?>(null) }
    var lookingAt by remember { mutableStateOf<Message?>(null) }
    var lookAnswer by remember { mutableStateOf("") }
    var planChecked by remember { mutableIntStateOf(0) }
    var askText by remember { mutableStateOf("") }
    var askAnswer by remember { mutableStateOf<String?>(null) }
    var asking by remember { mutableStateOf(false) }

    // Voice notes
    val recorder = remember { Voice.Recorder(context) }
    var recording by remember { mutableStateOf(false) }
    var recElapsed by remember { mutableLongStateOf(0L) }
    val levels = remember { mutableStateListOf<Float>() }
    var voiceFile by remember { mutableStateOf<File?>(null) }
    var voiceTranscript by remember { mutableStateOf("") }
    var transcribing by remember { mutableStateOf(false) }
    val maxVoiceMs = remember(subId) { Voice.maxMillis(Mms.maxBytes(context, subId)) }
    val canTranscribe = remember { Voice.canTranscribe(context) }

    fun finishRecording() {
        recording = false
        val f = recorder.stop() ?: run {
            Toast.makeText(context, "Too short. Hold on a little longer", Toast.LENGTH_SHORT).show(); return
        }
        voiceFile = f
        voiceTranscript = ""
        if (canTranscribe) {
            transcribing = true
            scope.launch {
                voiceTranscript = Voice.transcribe(context, Uri.fromFile(f)).orEmpty()
                transcribing = false
            }
        }
    }

    fun startRecording() {
        Voice.Player.stop()
        if (!recorder.start()) {
            Toast.makeText(context, "Couldn't use the microphone", Toast.LENGTH_SHORT).show(); return
        }
        recording = true
        levels.clear()
        scope.launch {
            while (recording) {
                recElapsed = recorder.elapsed
                levels += recorder.level()
                if (levels.size > 200) levels.removeAt(0)
                if (recElapsed >= maxVoiceMs) { finishRecording(); break }
                delay(80)
            }
        }
    }

    val micPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        if (ok) startRecording()
        else Toast.makeText(context, "Murmur needs the microphone to record voice notes", Toast.LENGTH_LONG).show()
    }

    DisposableEffect(Unit) {
        onDispose { if (recording) recorder.cancel(); Voice.Player.stop() }
    }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        preparing = true
        scope.launch {
            val file = withContext(Dispatchers.IO) { Mms.preparePicture(context, uri, subId) }
            preparing = false
            if (file == null) Toast.makeText(context, "Couldn't open that picture", Toast.LENGTH_SHORT).show()
            else attachment = file.path
        }
    }

    LaunchedEffect(threadId, version) {
        val (c, m) = withContext(Dispatchers.IO) {
            if (unreadAtOpen < 0) unreadAtOpen = repo.unreadCount(threadId)
            repo.markRead(threadId)
            (repo.conversation(threadId) ?: fallbackConversation(repo, threadId)) to repo.messages(threadId)
        }
        Notifier.cancel(context, threadId)
        convo = c; messages = m; loaded = true
        // Dual SIM with no choice made yet: answer on the SIM they last wrote to.
        if (sims.size > 1 && prefs.sim(threadId) == null) {
            m.lastOrNull { it.box == MsgBox.IN && it.subId >= 0 }?.let { subId = it.subId }
        }
    }
    LaunchedEffect(text) {
        delay(400)
        prefs.setDraft(threadId, text)
    }

    val address = convo?.addresses?.joinToString(" ").orEmpty()

    fun doSend(body: String) {
        if (address.isBlank()) return
        voiceFile?.let { f ->
            val caption = voiceTranscript.trim()
            Outbox.send(context, threadId, address, if (caption.isEmpty()) "" else "🎙 $caption", f.path, subId)
            voiceFile = null; voiceTranscript = ""
            scope.launch { listState.animateScrollToItem(0) }
            return
        }
        if (body.isBlank() && attachment == null) return
        // Sending a suggested reply (as is or edited) teaches the next suggestions how the user really writes.
        pickedReply?.let { p -> if (body.isNotBlank() && replyTranscript.isNotEmpty()) ReplyStyle.learn(context, replyTranscript, body, replies - p) }
        pickedReply = null
        Outbox.send(context, threadId, address, body.trim(), attachment, subId)
        attachment = null
        text = ""
        prefs.setDraft(threadId, "")
        scope.launch { listState.animateScrollToItem(0) }
    }

    fun doSchedule(at: Long) {
        val repeat = repeatKind?.let { Repeat.encode(it, at) }
        repeatKind = null
        voiceFile?.let { f ->
            val caption = voiceTranscript.trim()
            Outbox.schedule(context, threadId, address, if (caption.isEmpty()) "" else "🎙 $caption", at, f.path, subId, repeat)
            voiceFile = null; voiceTranscript = ""
            Toast.makeText(context, "Scheduled for ${When.future(context, at)}", Toast.LENGTH_SHORT).show()
            return
        }
        if ((text.isBlank() && attachment == null) || address.isBlank()) return
        Outbox.schedule(context, threadId, address, text.trim(), at, attachment, subId, repeat)
        attachment = null
        Toast.makeText(context, "Scheduled for ${When.future(context, at)}", Toast.LENGTH_SHORT).show()
        text = ""
        prefs.setDraft(threadId, "")
    }

    fun trySend() {
        if (voiceFile != null) { doSend(""); return }
        val heat = Vibes.heat(text)
        if (prefs.sleepOnIt && Vibes.isLateNight() && heat != Vibes.Heat.CALM) sleepPrompt = true
        else doSend(text)
    }

    val rows = remember(messages, pending) { buildRows(messages, pending) }
    val verdicts = remember(messages, convo, scamTick) {
        val known = convo?.known == true
        messages.filter { it.box == MsgBox.IN && it.body.isNotBlank() && !prefs.scamDismissed(it.key) }
            .associate { it.key to ScamShield.analyze(it.body, it.address, known) }
            .filterValues { it.flagged } + brainVerdicts.filterKeys { !prefs.scamDismissed(it) }
    }

    // Murmur Brain: a second opinion on recent texts from strangers that carry a link or ask for something,
    // when the rules above let them through. Each message is asked about once.
    LaunchedEffect(messages.lastOrNull()?.key, brainReady) {
        if (!brainReady || convo?.known == true) return@LaunchedEffect
        delay(1_500)
        val candidates = messages.asReversed().asSequence()
            .filter { it.box == MsgBox.IN && it.body.length > 20 && !prefs.scamDismissed(it.key) }
            .filter { ScamShield.links(it.body).isNotEmpty() || Regex("(?i)\\$|pay|account|code|verify|refund|prize|won|gift|invest|crypto|job").containsMatchIn(it.body) }
            .filter { !ScamShield.analyze(it.body, it.address, false).flagged }
            .take(5).toList()
        // Pictures from strangers too: a screenshot of a "USPS fee" text is the same scam.
        val pictures = messages.asReversed().asSequence()
            .filter { it.box == MsgBox.IN && it.images.isNotEmpty() && !prefs.scamDismissed(it.key) && System.currentTimeMillis() - it.date < 7 * 86_400_000L }
            .take(2).toList()
        for (m in candidates + pictures.filter { it !in candidates }) try {
            val cached = prefs.brainScam(m.key) ?: run {
                val opinion = if (m.images.isNotEmpty() && m.body.length <= 20) {
                    val jpg = withContext(Dispatchers.IO) { Eyes.jpeg(context, m.images.first()) } ?: continue
                    Brain.look(context, jpg, BrainPrompts.PIC_SCAM, maxTokens = 40)?.let(BrainPrompts::parseScamAnswer) ?: return@LaunchedEffect
                } else Brain.scamCheck(context, m.body) ?: return@LaunchedEffect
                (if (opinion.scam) "1|${opinion.reason}" else "0").also { prefs.setBrainScam(m.key, it) }
            }
            if (cached.startsWith("1|")) {
                brainVerdicts = brainVerdicts + (m.key to ScamShield.Verdict(
                    ScamShield.Risk.CAUTION, listOf("Murmur Brain: " + cached.removePrefix("1|")), ScamShield.links(m.body),
                ))
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w("Murmur", "scam check failed", e)
        }
    }

    // Murmur Brain: three replies to the newest text, if it's from them and looks like it wants one.
    LaunchedEffect(messages.lastOrNull()?.key, brainReady) {
        replies = emptyList()
        val last = messages.lastOrNull() ?: return@LaunchedEffect
        if (!brainReady || !Brain.suggestOn(context) || last.box != MsgBox.IN || last.body.isBlank()) return@LaunchedEffect
        if (Vibes.code(last.body) != null || ScamShield.analyze(last.body, last.address, convo?.known == true).flagged) return@LaunchedEffect
        Brain.replyCache[last.key]?.let { replies = it; return@LaunchedEffect }
        delay(800)
        try {
            val lines = messages.takeLast(8).filter { it.body.isNotBlank() }.map { (it.box != MsgBox.IN) to it.body.removePrefix("🎙 ") }
            replyTranscript = BrainPrompts.replyTranscript(lines)
            val mine = messages.takeLast(40).filter { it.box != MsgBox.IN && it.body.isNotBlank() }.map { it.body }
            val got = Brain.suggestReplies(context, lines, mine) ?: return@LaunchedEffect
            Brain.replyCache[last.key] = got
            replies = got
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w("Murmur", "reply suggestions failed", e)
        }
    }

    // Murmur Brain: when someone shares something lasting about themselves ("I'm vegan now"), offer to remember it.
    LaunchedEffect(messages.lastOrNull()?.key, brainReady) {
        factOffer = null
        if (!brainReady) return@LaunchedEffect
        delay(2_500)
        val facts = context.getSharedPreferences("brain_facts", 0)
        val known = BrainMemory.facts(context).map { it.lowercase() }
        val now = System.currentTimeMillis()
        for (m in messages.takeLast(15).asReversed().filter { it.box == MsgBox.IN && now - it.date < 7 * 86_400_000L && BrainPrompts.mightHaveFact(it.body) }) try {
            if (facts.getBoolean("no_" + m.key, false)) continue
            val sender = (if ((convo?.addresses?.size ?: 1) > 1) withContext(Dispatchers.IO) { repo.contact(m.address).name } else convo?.title?.takeIf { convo?.known == true }) ?: continue
            val fact = facts.getString(m.key, null) ?: Brain.factCheck(context, sender.substringBefore(' '), m.body)?.also { facts.edit().putString(m.key, it).apply() } ?: return@LaunchedEffect
            if (fact != "-" && known.none { it == fact.lowercase() }) { factOffer = m.key to fact; return@LaunchedEffect }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w("Murmur", "fact check failed", e)
        }
    }

    // Murmur Brain: plans in recent texts ("dinner friday at 7") become an Add to calendar chip.
    LaunchedEffect(messages.lastOrNull()?.key, brainReady) {
        if (!brainReady) return@LaunchedEffect
        delay(1_500) // let the chat settle (it reloads once as it's marked read) before waking the model
        val now = System.currentTimeMillis()
        val candidates = messages.takeLast(20).asReversed()
            .filter { now - it.date < 14 * 86_400_000L }
            .filter { (it.body.isNotBlank() && PlanTime.mightHavePlan(it.body)) || (it.box == MsgBox.IN && it.images.isNotEmpty()) }
        planChecked = 0
        for ((n, m) in candidates.withIndex()) try {
            brainStatus = "Reading recent texts for plans… ${n + 1} of ${candidates.size}"
            val fromText = m.body.isNotBlank() && PlanTime.mightHavePlan(m.body)
            // A flyer or a screenshot of plans: the Brain reads the picture itself.
            val key = if (fromText) m.key else "img_" + m.key
            val stored = prefs.plan(key)
                ?: (if (fromText) Brain.planCheck(context, m.body.removePrefix("🎙 "))
                else withContext(Dispatchers.IO) { Eyes.jpeg(context, m.images.first()) }?.let { jpg ->
                    Brain.look(context, jpg, BrainPrompts.PIC_EVENT, maxTokens = 60)?.let { BrainPrompts.encodePlan(BrainPrompts.parseEvent(it)) }
                })?.also { prefs.setPlan(key, it) }
                ?: continue // the model couldn't answer this time; try again next visit
            planChecked++
            val plan = BrainPrompts.decodePlan(stored) ?: continue
            val time = PlanTime.resolve(plan.whenText, m.date, plan.title) ?: continue
            if (time.start < now - 2 * 3_600_000L) continue // already over
            plans = plans + (m.key to (plan to time))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w("Murmur", "plan check failed", e) // one odd message must never close the app
        }
        brainStatus = null
    }

    fun startCatchUp() {
        if (catchingUp) return
        catchingUp = true
        catchUp = ""
        scope.launch {
            val group = (convo?.addresses?.size ?: 1) > 1
            val names = HashMap<String, String>()
            val lines = messages.takeLast(maxOf(40, unreadAtOpen + 10)).map { m ->
                val who = when {
                    m.box == MsgBox.OUT -> "You"
                    group -> names.getOrPut(m.address) { withContext(Dispatchers.IO) { repo.contact(m.address).name ?: Phone.pretty(m.address) } }
                    else -> convo?.title ?: "Them"
                }
                val what = listOfNotNull(
                    if (m.images.isNotEmpty()) "[photo]" else null,
                    if (m.audio != null && !m.body.startsWith("🎙")) "[voice note]" else null,
                    m.body.replace('\n', ' ').ifBlank { null },
                ).joinToString(" ")
                "$who: $what"
            }
            // Keep the newest part of the chat that fits comfortably in the model's memory.
            val transcript = lines.asReversed().fold(StringBuilder()) { acc, l -> if (acc.length + l.length > 5000) acc else acc.insert(0, l + "\n") }.toString()
            catchUp = Brain.summarize(context, transcript) { partial -> catchUp = partial } ?: "Murmur Brain couldn't summarize this chat. Try again in a moment."
            catchingUp = false
        }
    }

    fun transcript(limit: Int): String {
        val group = (convo?.addresses?.size ?: 1) > 1
        val lines = messages.takeLast(limit).map { m ->
            val who = when {
                m.box == MsgBox.OUT -> "You"
                group -> repo.contact(m.address).name ?: Phone.pretty(m.address)
                else -> convo?.title ?: "Them"
            }
            val what = listOfNotNull(
                if (m.images.isNotEmpty()) "[photo]" else null,
                if (m.audio != null && !m.body.startsWith("🎙")) "[voice note]" else null,
                m.body.replace('\n', ' ').ifBlank { null },
            ).joinToString(" ")
            "[" + android.text.format.DateFormat.format("EEE MMM d", m.date) + "] $who: $what"
        }
        return lines.asReversed().fold(StringBuilder()) { acc, l -> if (acc.length + l.length > 5000) acc else acc.insert(0, l + "\n") }.toString()
    }

    fun askAboutChat() {
        val q = askText.trim()
        if (q.isEmpty() || asking) return
        asking = true
        askAnswer = ""
        scope.launch {
            val text = withContext(Dispatchers.IO) { transcript(80) }
            val system = BrainPrompts.system(
                BrainPrompts.CHAT_MESSAGES, BrainMemory.persona(context),
                listOf(BrainPrompts.memoryBlock(BrainMemory.facts(context))),
            )
            val prompt = "This conversation with ${convo?.title ?: "them"}:\n$text\nQuestion: $q"
            askAnswer = Brain.chat(context, emptyList(), prompt, system) { partial -> askAnswer = partial }
                ?: "Murmur Brain couldn't answer just now. Try again in a moment."
            asking = false
        }
    }

    fun addToCalendar(plan: BrainPrompts.Plan, time: PlanTime.When) {
        val intent = Intent(Intent.ACTION_INSERT).setData(CalendarContract.Events.CONTENT_URI)
            .putExtra(CalendarContract.Events.TITLE, plan.title)
            .putExtra(CalendarContract.EXTRA_EVENT_BEGIN_TIME, time.start)
            .putExtra(CalendarContract.EXTRA_EVENT_END_TIME, time.start + if (time.allDay) 86_400_000L else 3_600_000L)
            .putExtra(CalendarContract.EXTRA_EVENT_ALL_DAY, time.allDay)
            .apply { if (plan.place.isNotBlank()) putExtra(CalendarContract.Events.EVENT_LOCATION, plan.place) }
        runCatching { context.startActivity(intent) }
            .onFailure { Toast.makeText(context, "No calendar app found", Toast.LENGTH_SHORT).show() }
    }

    fun openLink(url: String, verdict: ScamShield.Verdict?) {
        if (verdict?.flagged == true) { linkPrompt = url to verdict; return }
        val full = if (url.contains("://")) url else "https://$url"
        runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(full))) }
    }

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
                    if (brainReady) IconButton(onClick = { brainSheet = true }) {
                        Icon(Icons.Outlined.AutoAwesome, "Murmur Brain for this chat", tint = MaterialTheme.colorScheme.primary)
                    }
                    if (convo?.addresses?.size == 1) IconButton(onClick = {
                        context.startActivity(Intent(Intent.ACTION_DIAL, Uri.parse("tel:" + Uri.encode(convo!!.addresses[0]))))
                    }) { Icon(Icons.Outlined.Call, "Call") }
                    Box {
                        IconButton(onClick = { menu = true }) { Icon(Icons.Outlined.MoreVert, "More") }
                        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                            if (brainReady && messages.size >= 3) DropdownMenuItem(
                                text = { Text("Catch me up") },
                                leadingIcon = { Icon(Icons.Outlined.AutoAwesome, null) },
                                onClick = { menu = false; startCatchUp() },
                            )
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
            if (brainReady && unreadAtOpen >= 8 && catchUp == null) {
                AssistChip(
                    onClick = ::startCatchUp,
                    label = { Text("Catch me up on $unreadAtOpen new messages") },
                    leadingIcon = { Icon(Icons.Outlined.AutoAwesome, null, Modifier.size(18.dp)) },
                    modifier = Modifier.padding(horizontal = 16.dp),
                    colors = AssistChipDefaults.assistChipColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
                )
            }
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
                            onImage = { viewing = it },
                            simLabel = if (sims.size > 1) sims.firstOrNull { it.subId == row.m.subId }?.label else null,
                            scam = verdicts[row.m.key],
                            onLink = { url -> openLink(url, verdicts[row.m.key]) },
                            onBlock = {
                                scope.launch(Dispatchers.IO) {
                                    val ok = repo.block(row.m.address)
                                    withContext(Dispatchers.Main) {
                                        Toast.makeText(context, if (ok) "Blocked. You won't get texts from this number" else "Couldn't block this number", Toast.LENGTH_SHORT).show()
                                    }
                                }
                            },
                            onNotScam = { prefs.dismissScam(row.m.key); scamTick++ },
                            plan = plans[row.m.key],
                            onAddPlan = { p, t -> addToCalendar(p, t) },
                            transcript = row.m.audio?.let { transcripts[row.m.key] ?: prefs.transcript(row.m.key) },
                            onTranscribe = if (canTranscribe && row.m.audio != null) {
                                {
                                    transcripts = transcripts + (row.m.key to "…")
                                    scope.launch {
                                        val t = Voice.transcribe(context, row.m.audio).orEmpty().ifBlank { "(no words caught)" }
                                        prefs.setTranscript(row.m.key, t)
                                        transcripts = transcripts + (row.m.key to t)
                                    }
                                }
                            } else null,
                        )
                        is Item.Wait -> WaitingBubble(
                            row.p,
                            onUndo = {
                                Outbox.undoWithAttachment(context, row.p.id)?.let {
                                    if (Voice.isVoice(it.attachment)) {
                                        voiceFile = File(it.attachment!!); voiceTranscript = it.body.removePrefix("🎙 ")
                                    } else {
                                        text = it.body; attachment = it.attachment
                                    }
                                }
                            },
                            onStopRepeat = { PendingStore.get(context).setRepeat(row.p.id, null) },
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
            factOffer?.let { (key, fact) ->
                FactChip(fact, onRemember = { BrainMemory.add(context, fact); factOffer = null; Toast.makeText(context, "Murmur Brain will remember that", Toast.LENGTH_SHORT).show() },
                    onDismiss = { context.getSharedPreferences("brain_facts", 0).edit().putBoolean("no_$key", true).apply(); factOffer = null })
            }
            if (replies.isNotEmpty() && text.isBlank() && attachment == null && voiceFile == null && isDefault) ReplyChips(replies) { text = it; pickedReply = it }
            Composer(
                text = text,
                onText = { text = it },
                enabled = isDefault,
                onSend = ::trySend,
                onSchedule = { scheduleSheet = true },
                attachment = attachment,
                preparing = preparing,
                onAttach = { picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) },
                onRemoveAttachment = { attachment?.let { File(it).delete() }; attachment = null },
                sims = sims,
                subId = subId,
                onSim = { subId = it; prefs.setSim(threadId, it) },
                group = (convo?.addresses?.size ?: 1) > 1,
                voice = {
                    when {
                        recording -> RecordingBar(recElapsed, maxVoiceMs, levels, onCancel = { recording = false; recorder.cancel() }, onStop = ::finishRecording)
                        voiceFile != null -> VoicePreview(
                            Uri.fromFile(voiceFile!!), Voice.durationOf(voiceFile!!), voiceTranscript, transcribing, canTranscribe,
                            onTranscript = { voiceTranscript = it },
                            onDelete = { Voice.Player.stop(); voiceFile?.delete(); voiceFile = null; voiceTranscript = "" },
                            modifier = Modifier.weight(1f),
                        )
                        else -> return@Composer false
                    }
                    true
                },
                hasVoice = voiceFile != null,
                onMic = {
                    if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) startRecording()
                    else micPermission.launch(Manifest.permission.RECORD_AUDIO)
                },
            )
        }
    }

    // ---------- sheets & dialogs ----------

    if (scheduleSheet) ModalBottomSheet(onDismissRequest = { scheduleSheet = false; if (!customPicker) repeatKind = null }) {
        Text("Send later", Modifier.padding(24.dp, 0.dp, 24.dp, 8.dp), style = MaterialTheme.typography.titleLarge)
        LazyRow(contentPadding = PaddingValues(horizontal = 20.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            item { FilterChip(selected = repeatKind == null, onClick = { repeatKind = null }, label = { Text("Once") }) }
            items(Repeat.Kind.entries) { k ->
                FilterChip(
                    selected = repeatKind == k, onClick = { repeatKind = k }, label = { Text(k.label) },
                    leadingIcon = if (repeatKind == k) { { Icon(Icons.Outlined.Repeat, null, Modifier.size(16.dp)) } } else null,
                )
            }
        }
        if (repeatKind != null) Text(
            "Pick when the first one goes. It then repeats (${repeatKind!!.label.lowercase()}) until you tap Stop repeating.",
            Modifier.padding(24.dp, 8.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
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
            if (m.images.isNotEmpty() && brainReady) ListItem(
                headlineContent = { Text("What's in this picture?") },
                supportingContent = { Text("Murmur Brain looks at it on this phone") },
                leadingContent = { Icon(Icons.Outlined.AutoAwesome, null) },
                modifier = Modifier.clickable {
                    actionsFor = null; lookingAt = m; lookAnswer = ""
                    scope.launch {
                        val jpg = withContext(Dispatchers.IO) { Eyes.jpeg(context, m.images.first()) }
                        lookAnswer = jpg?.let { Brain.look(context, it, BrainPrompts.PIC_DESCRIBE, maxTokens = 200) { p -> lookAnswer = p } }
                            ?: "Murmur Brain couldn't look at this picture. Try again in a moment."
                    }
                },
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
                    scope.launch(Dispatchers.IO) {
                        val picture = m.images.firstOrNull()?.let { Mms.copyToOutgoing(context, it) }
                        repo.deleteMessage(m)
                        Outbox.sendImmediately(context, threadId, address, m.body, picture?.path, subId)
                    }
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

    viewing?.let { PictureViewer(it) { viewing = null } }

    lookingAt?.let { m ->
        ModalBottomSheet(onDismissRequest = { lookingAt = null }) {
            Column(Modifier.padding(24.dp, 0.dp, 24.dp, 32.dp)) {
                Text("What's in this picture", style = MaterialTheme.typography.titleMedium)
                Text(
                    lookAnswer.ifEmpty { "Looking…" }, Modifier.padding(top = 8.dp),
                    style = MaterialTheme.typography.bodyLarge,
                    fontStyle = if (lookAnswer.isEmpty()) FontStyle.Italic else FontStyle.Normal,
                )
                if (lookAnswer.length > 20) TextButton(onClick = { copy(context, lookAnswer) }) { Text("Copy") }
            }
        }
    }

    if (brainSheet) ModalBottomSheet(onDismissRequest = { brainSheet = false }) {
        Column(Modifier.padding(24.dp, 0.dp, 24.dp, 24.dp).imePadding()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Outlined.AutoAwesome, null, tint = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.width(8.dp))
                Text("Murmur Brain · this chat", style = MaterialTheme.typography.titleLarge)
            }
            Spacer(Modifier.height(6.dp))
            Text(
                brainStatus ?: when {
                    plans.isNotEmpty() -> "Found ${plans.size} upcoming plan${if (plans.size == 1) "" else "s"} in recent texts."
                    planChecked > 0 -> "Read $planChecked recent text${if (planChecked == 1) "" else "s"} that mention a day or time. No upcoming plans."
                    else -> "No recent texts here mention a day or time, so there are no plans to add."
                },
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            plans.values.forEach { (p, t) ->
                ListItem(
                    headlineContent = { Text(p.title) },
                    supportingContent = { Text((if (t.allDay) When.day(context, t.start) else When.future(context, t.start)) + if (p.place.isNotBlank()) " · ${p.place}" else "") },
                    leadingContent = { Icon(Icons.Outlined.Event, null) },
                    trailingContent = { TextButton(onClick = { addToCalendar(p, t) }) { Text("Add") } },
                )
            }
            Spacer(Modifier.height(8.dp))
            OutlinedButton(
                onClick = { brainSheet = false; startCatchUp() },
                enabled = messages.size >= 3, modifier = Modifier.fillMaxWidth(),
            ) { Text("Catch me up on this chat") }
            Spacer(Modifier.height(16.dp))
            Text("Ask about this chat", style = MaterialTheme.typography.titleSmall)
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = askText, onValueChange = { askText = it }, singleLine = true,
                    placeholder = { Text("e.g. what time did we say?") }, modifier = Modifier.weight(1f),
                )
                Spacer(Modifier.width(8.dp))
                TextButton(onClick = ::askAboutChat, enabled = askText.isNotBlank() && !asking) { Text("Ask") }
            }
            askAnswer?.let { a ->
                Spacer(Modifier.height(8.dp))
                Text(
                    a.ifEmpty { "Reading the chat…" },
                    style = MaterialTheme.typography.bodyLarge,
                    fontStyle = if (a.isEmpty()) FontStyle.Italic else FontStyle.Normal,
                )
            }
        }
    }

    catchUp?.let { summary ->
        ModalBottomSheet(onDismissRequest = { if (!catchingUp) catchUp = null }) {
            Column(Modifier.padding(24.dp, 0.dp, 24.dp, 32.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Outlined.AutoAwesome, null, tint = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.width(8.dp))
                    Text("Catch me up", style = MaterialTheme.typography.titleLarge)
                }
                Spacer(Modifier.height(12.dp))
                if (summary.isEmpty()) Text(
                    "Reading the conversation…", style = MaterialTheme.typography.bodyMedium,
                    fontStyle = FontStyle.Italic, color = MaterialTheme.colorScheme.onSurfaceVariant,
                ) else Text(summary, style = MaterialTheme.typography.bodyLarge)
                if (!catchingUp) {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "Written by Murmur Brain on this phone. It can get details wrong.",
                        style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }

    linkPrompt?.let { (url, verdict) ->
        AlertDialog(
            onDismissRequest = { linkPrompt = null },
            icon = { Icon(Icons.Outlined.GppMaybe, null, tint = Flame) },
            title = { Text("This link looks risky") },
            text = {
                Column {
                    Text(url, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.height(8.dp))
                    verdict?.reasons?.take(4)?.forEach { Text("• $it", style = MaterialTheme.typography.bodyMedium) }
                    Spacer(Modifier.height(8.dp))
                    Text("Real companies don't text links asking you to pay, log in or confirm details. When in doubt, open their app or type their address yourself.", style = MaterialTheme.typography.bodySmall)
                }
            },
            confirmButton = { TextButton(onClick = { linkPrompt = null }) { Text("Stay safe") } },
            dismissButton = {
                TextButton(onClick = {
                    linkPrompt = null
                    val full = if (url.contains("://")) url else "https://$url"
                    runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(full))) }
                }) { Text("Open anyway", color = MaterialTheme.colorScheme.error) }
            },
        )
    }
}

/** Full-screen picture: pinch to zoom, tap to close. */
@Composable
private fun PictureViewer(uri: Uri, onClose: () -> Unit) {
    val img = rememberImage(uri, 2048)
    var scale by remember { mutableStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }
    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Box(
            Modifier.fillMaxSize().background(Color.Black)
                .pointerInput(Unit) {
                    detectTransformGestures { _, pan, zoom, _ ->
                        scale = (scale * zoom).coerceIn(1f, 6f)
                        offset = if (scale == 1f) Offset.Zero else offset + pan
                    }
                }
                .pointerInput(Unit) { detectTapGestures(onTap = { onClose() }) },
            contentAlignment = Alignment.Center,
        ) {
            if (img != null) Image(
                img, "Picture",
                Modifier.fillMaxSize().graphicsLayer {
                    scaleX = scale; scaleY = scale; translationX = offset.x; translationY = offset.y
                },
                contentScale = ContentScale.Fit,
            ) else CircularProgressIndicator()
        }
    }
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
internal fun Bubble(
    row: Item.Msg, convo: Conversation?, seed: String, onLongPress: () -> Unit,
    onImage: (Uri) -> Unit = {}, simLabel: String? = null,
    scam: ScamShield.Verdict? = null, onLink: (String) -> Unit = {}, onBlock: () -> Unit = {}, onNotScam: () -> Unit = {},
    transcript: String? = null, onTranscribe: (() -> Unit)? = null,
    plan: Pair<BrainPrompts.Plan, PlanTime.When>? = null, onAddPlan: (BrainPrompts.Plan, PlanTime.When) -> Unit = { _, _ -> },
) {
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
                        Modifier.widthIn(max = 260.dp).heightIn(max = 320.dp).clip(RoundedCornerShape(14.dp))
                            .combinedClickable(onClick = { onImage(img) }, onLongClick = onLongPress),
                        contentScale = ContentScale.Fit,
                    ) else Box(
                        Modifier.size(200.dp, 140.dp).clip(RoundedCornerShape(14.dp)).background(scheme.surfaceVariant),
                    )
                    Spacer(Modifier.height(4.dp))
                }
                val ink = if (mine) scheme.onPrimary else scheme.onSurface
                m.audio?.let { VoiceNotePlayer(it, mine) }
                val isCaption = m.audio != null && m.body.startsWith("🎙 ")
                when {
                    isCaption -> Text(
                        m.body.removePrefix("🎙 "), Modifier.padding(top = 6.dp),
                        style = MaterialTheme.typography.bodyMedium, fontStyle = FontStyle.Italic, color = ink.copy(alpha = 0.9f),
                    )
                    m.body.isNotBlank() -> Text(
                        linkified(m.body, if (scam != null) Flame else ink, onLink),
                        style = MaterialTheme.typography.bodyLarge,
                        color = ink,
                    )
                    m.audio != null && transcript != null -> Text(
                        if (transcript == "…") "Transcribing…" else transcript, Modifier.padding(top = 6.dp),
                        style = MaterialTheme.typography.bodyMedium, fontStyle = FontStyle.Italic, color = ink.copy(alpha = 0.9f),
                    )
                    m.audio != null && onTranscribe != null -> Text(
                        "Show transcript",
                        Modifier.padding(top = 6.dp).clip(RoundedCornerShape(8.dp)).clickable(onClick = onTranscribe).padding(4.dp),
                        style = MaterialTheme.typography.labelLarge, color = ink,
                    )
                }
            }
        }
        if (scam != null) ScamBanner(scam, onBlock, onNotScam)
        if (plan != null && scam == null) {
            val (p, t) = plan
            AssistChip(
                onClick = { onAddPlan(p, t) },
                label = {
                    Text(
                        "Add to calendar · ${p.title}, " + if (t.allDay) When.day(context, t.start) else When.future(context, t.start),
                        maxLines = 1, overflow = TextOverflow.Ellipsis,
                    )
                },
                leadingIcon = { Icon(Icons.Outlined.Event, null, Modifier.size(16.dp)) },
                modifier = Modifier.padding(top = 4.dp).widthIn(max = 300.dp),
            )
        }
        if (code != null && scam == null) {
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
                        simLabel?.let { append(" · $it") }
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
internal fun WaitingBubble(p: Pending, onUndo: () -> Unit, onSendNow: () -> Unit, onStopRepeat: () -> Unit = {}) {
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
            Column {
                if (Voice.isVoice(p.attachment)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Outlined.Mic, null, Modifier.size(18.dp), tint = scheme.primary)
                        Spacer(Modifier.width(6.dp))
                        Text("Voice note · " + clock(Voice.durationOf(File(p.attachment!!))), style = MaterialTheme.typography.labelLarge, color = scheme.onSurface)
                    }
                } else p.attachment?.let { path ->
                    val bmp = rememberImage(Uri.fromFile(File(path)), 600)
                    if (bmp != null) Image(
                        bmp, "Picture",
                        Modifier.widthIn(max = 220.dp).heightIn(max = 260.dp).clip(RoundedCornerShape(14.dp)),
                        contentScale = ContentScale.Fit,
                    )
                    if (p.body.isNotBlank()) Spacer(Modifier.height(4.dp))
                }
                if (p.body.isNotBlank()) Text(
                    p.body.removePrefix("🎙 "), style = MaterialTheme.typography.bodyLarge, color = scheme.onSurface,
                    fontStyle = if (Voice.isVoice(p.attachment)) FontStyle.Italic else FontStyle.Normal,
                )
            }
        }
        Row(
            Modifier.padding(top = 4.dp).animateContentSize(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            if (p.scheduled) {
                Icon(if (p.repeat != null) Icons.Outlined.Repeat else Icons.Outlined.Schedule, null, Modifier.size(14.dp), tint = scheme.secondary)
                Column {
                    Text(When.future(context, p.at), style = MaterialTheme.typography.labelMedium, color = scheme.secondary)
                    Repeat.describe(p.repeat)?.let { Text(it, style = MaterialTheme.typography.labelSmall, color = scheme.secondary) }
                }
                if (p.repeat != null) TextButton(onClick = onStopRepeat) { Text("Stop repeating") }
                else TextButton(onClick = onUndo) { Text("Edit") }
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
internal fun Composer(
    text: String, onText: (String) -> Unit, enabled: Boolean, onSend: () -> Unit, onSchedule: () -> Unit,
    attachment: String? = null, preparing: Boolean = false, onAttach: () -> Unit = {}, onRemoveAttachment: () -> Unit = {},
    sims: List<Sims.Sim> = emptyList(), subId: Int = -1, onSim: (Int) -> Unit = {}, group: Boolean = false,
    voice: @Composable RowScope.() -> Boolean = { false }, hasVoice: Boolean = false, onMic: (() -> Unit)? = null,
) {
    val context = LocalContext.current
    val scheme = MaterialTheme.colorScheme
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
            if (attachment != null || preparing) {
                Box(Modifier.padding(start = 52.dp, bottom = 6.dp)) {
                    val bmp = attachment?.let { rememberImage(Uri.fromFile(File(it)), 400) }
                    Box(
                        Modifier.size(96.dp).clip(RoundedCornerShape(16.dp)).background(scheme.surfaceVariant),
                        contentAlignment = Alignment.Center,
                    ) {
                        if (bmp != null) Image(bmp, "Picture to send", Modifier.size(96.dp), contentScale = ContentScale.Crop)
                        else CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
                    }
                    if (attachment != null) Box(
                        Modifier.align(Alignment.TopEnd).padding(4.dp).size(24.dp).clip(CircleShape)
                            .background(Color.Black.copy(alpha = 0.6f)).clickable(onClick = onRemoveAttachment),
                        contentAlignment = Alignment.Center,
                    ) { Icon(Icons.Outlined.Close, "Remove picture", Modifier.size(16.dp), tint = Color.White) }
                }
            }
            if (sims.size > 1) {
                val current = sims.firstOrNull { it.subId == subId } ?: sims.firstOrNull { it.subId == Sims.defaultSms() } ?: sims[0]
                AssistChip(
                    onClick = { onSim(sims[(sims.indexOf(current) + 1) % sims.size].subId) },
                    label = { Text(listOf(current.label, current.carrier).filter { it.isNotBlank() }.joinToString(" · ")) },
                    leadingIcon = { Icon(Icons.Outlined.SimCard, null, Modifier.size(16.dp), tint = Color(current.color or 0xFF000000.toInt())) },
                    modifier = Modifier.padding(start = 52.dp).height(30.dp),
                )
            }
            Row(verticalAlignment = Alignment.Bottom) {
                IconButton(onClick = onAttach, modifier = Modifier.padding(bottom = 4.dp)) {
                    Icon(Icons.Outlined.AddPhotoAlternate, "Add a picture", tint = scheme.primary)
                }
                if (!voice()) TextField(
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
                    supportingText = when {
                        attachment != null -> { { Text("Picture message") } }
                        group -> { { Text("Group chat") } }
                        text.isNotEmpty() && (segments > 1 || left < 20) ->
                            { { Text(if (segments > 1) "$left · $segments texts" else "$left left") } }
                        else -> null
                    },
                )
                Spacer(Modifier.width(8.dp))
                val active = (text.isNotBlank() || attachment != null || hasVoice) && !preparing
                val mic = !active && !preparing && onMic != null
                Box(
                    Modifier.padding(bottom = 2.dp).size(50.dp).clip(CircleShape)
                        .background(if (active) scheme.primary else if (mic) scheme.primaryContainer else scheme.surfaceVariant)
                        .combinedClickable(
                            enabled = active || mic,
                            onClick = { if (mic) onMic() else onSend() },
                            onLongClick = onSchedule,
                            onClickLabel = "Send",
                            onLongClickLabel = "Send later",
                        ),
                    contentAlignment = Alignment.Center,
                ) {
                    if (mic) Icon(Icons.Outlined.Mic, "Record a voice note", tint = scheme.onPrimaryContainer)
                    else Icon(
                        Icons.AutoMirrored.Filled.Send, "Send (hold to schedule)",
                        tint = if (active) scheme.onPrimary else scheme.onSurfaceVariant,
                    )
                }
            }
            if (text.isNotBlank() || attachment != null || hasVoice) Text(
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


/** Message text with tappable links; links in a suspected scam are drawn in warning red. */
private fun linkified(body: String, linkColor: Color, onLink: (String) -> Unit): AnnotatedString {
    val links = ScamShield.links(body)
    if (links.isEmpty()) return AnnotatedString(body)
    return buildAnnotatedString {
        var cursor = 0
        for (link in links) {
            val at = body.indexOf(link, cursor)
            if (at < 0) continue
            append(body.substring(cursor, at))
            withLink(
                LinkAnnotation.Clickable(
                    tag = link,
                    styles = TextLinkStyles(SpanStyle(color = linkColor, textDecoration = TextDecoration.Underline)),
                    linkInteractionListener = { onLink(link) },
                ),
            ) { append(link) }
            cursor = at + link.length
        }
        append(body.substring(cursor))
    }
}

@Composable
private fun ScamBanner(v: ScamShield.Verdict, onBlock: () -> Unit, onNotScam: () -> Unit) {
    val danger = v.risk == ScamShield.Risk.DANGER
    Surface(
        color = if (danger) Flame.copy(alpha = 0.16f) else Ember.copy(alpha = 0.14f),
        shape = RoundedCornerShape(16.dp),
        modifier = Modifier.padding(top = 6.dp).widthIn(max = 300.dp),
    ) {
        Column(Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Outlined.GppMaybe, null, Modifier.size(18.dp), tint = if (danger) Flame else Ember)
                Spacer(Modifier.width(6.dp))
                Text(
                    if (danger) "This looks like a scam" else "Be careful with this one",
                    style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurface,
                )
            }
            Spacer(Modifier.height(4.dp))
            v.reasons.take(3).forEach {
                Text("• $it", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Row {
                TextButton(onClick = onBlock) { Text("Block sender", color = if (danger) Flame else Ember) }
                TextButton(onClick = onNotScam) { Text("It's fine") }
            }
        }
    }
}

/** Suggested replies; tapping one puts it in the box to edit or send. */
@Composable
internal fun ReplyChips(replies: List<String>, onPick: (String) -> Unit) {
    LazyRow(
        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        items(replies) { r ->
            SuggestionChip(onClick = { onPick(r) }, label = { Text(r, maxLines = 2) })
        }
    }
}

/** "Remember: Priya is vegan?" One tap saves it to Murmur Brain's memory. */
@Composable
internal fun FactChip(fact: String, onRemember: () -> Unit, onDismiss: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
        AssistChip(
            onClick = onRemember,
            label = { Text("Remember: $fact", maxLines = 2) },
            leadingIcon = { Icon(Icons.Outlined.AutoAwesome, null, Modifier.size(16.dp)) },
            modifier = Modifier.weight(1f, fill = false),
        )
        IconButton(onClick = onDismiss) { Icon(Icons.Outlined.Close, "Not now", Modifier.size(18.dp)) }
    }
}
