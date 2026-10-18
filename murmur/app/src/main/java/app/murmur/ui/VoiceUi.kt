package app.murmur.ui

import android.media.MediaMetadataRetriever
import android.net.Uri
import androidx.compose.runtime.produceState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Hearing
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.murmur.voice.Voice

fun clock(ms: Long): String {
    val s = (ms / 1000).coerceAtLeast(0)
    return "%d:%02d".format(s / 60, s % 60)
}

/** Bars that look like a voice: the same note always draws the same shape. */
@Composable
private fun Waveform(seed: Int, progress: Float, played: Color, rest: Color, modifier: Modifier, bars: Int = 34) {
    val heights = remember(seed) {
        val r = java.util.Random(seed.toLong())
        List(bars) { i -> (0.25f + r.nextFloat() * 0.75f) * (0.6f + 0.4f * kotlin.math.sin(i / 3.0).toFloat().let { it * it }) }
    }
    Canvas(modifier) {
        val w = size.width / bars
        heights.forEachIndexed { i, h ->
            val barH = size.height * h.coerceIn(0.15f, 1f)
            drawRoundRect(
                color = if ((i + 0.5f) / bars <= progress) played else rest,
                topLeft = Offset(i * w + w * 0.2f, (size.height - barH) / 2),
                size = Size(w * 0.6f, barH),
                cornerRadius = CornerRadius(w * 0.3f),
            )
        }
    }
}

/** The voice note inside a message bubble: play, scrub, speed, and an earpiece hint while it plays. */
@Composable
fun VoiceNotePlayer(uri: Uri, mine: Boolean, fallbackMs: Long = 0) {
    val context = LocalContext.current
    val state by Voice.Player.state.collectAsState()
    val active = state.source == uri
    val scheme = MaterialTheme.colorScheme
    val ink = if (mine) scheme.onPrimary else scheme.onSurface
    // Read the length up front so the bubble shows "0:42" before anyone presses play.
    val known by produceState(fallbackMs, uri) {
        if (value <= 0) value = withContext(Dispatchers.IO) {
            runCatching {
                MediaMetadataRetriever().run {
                    setDataSource(context, uri)
                    val d = extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
                    release(); d
                }
            }.getOrDefault(0L)
        }
    }
    val total = if (active && state.durationMs > 0) state.durationMs.toLong() else known
    val progress = if (active && state.durationMs > 0) state.positionMs.toFloat() / state.durationMs else 0f

    Row(Modifier.widthIn(min = 220.dp, max = 270.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier.size(40.dp).clip(CircleShape).background(ink.copy(alpha = 0.16f)).clickable { Voice.Player.toggle(context, uri) },
            contentAlignment = Alignment.Center,
        ) {
            Icon(if (active && state.playing) Icons.Filled.Pause else Icons.Filled.PlayArrow, if (active && state.playing) "Pause" else "Play", tint = ink)
        }
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Waveform(
                uri.hashCode(), progress, ink, ink.copy(alpha = 0.35f),
                Modifier.fillMaxWidth().height(28.dp).pointerInput(uri) {
                    detectTapGestures { pos -> if (active) Voice.Player.seek(pos.x / size.width) }
                },
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    if (active) clock(state.positionMs.toLong()) + " / " + clock(total) else if (total > 0) clock(total) else "Voice note",
                    style = MaterialTheme.typography.labelSmall, color = ink.copy(alpha = 0.8f),
                )
                if (active && state.earpiece) {
                    Spacer(Modifier.width(6.dp))
                    Icon(Icons.Outlined.Hearing, "Playing through the earpiece", Modifier.size(12.dp), tint = ink.copy(alpha = 0.8f))
                }
            }
        }
        Spacer(Modifier.width(6.dp))
        Text(
            "${if (active) state.speed.toString().removeSuffix(".0") else "1"}×",
            Modifier.clip(RoundedCornerShape(10.dp)).background(ink.copy(alpha = 0.14f))
                .clickable { Voice.Player.cycleSpeed() }.padding(horizontal = 8.dp, vertical = 4.dp),
            style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold, color = ink,
        )
    }
}

/** While recording: a pulsing dot, the clock, a live level meter, cancel and stop. */
@Composable
fun RecordingBar(elapsedMs: Long, maxMs: Long, levels: List<Float>, onCancel: () -> Unit, onStop: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    val pulse by rememberInfiniteTransition(label = "rec").animateFloat(
        1f, 0.25f, infiniteRepeatable(tween(600), RepeatMode.Reverse), label = "dot",
    )
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(26.dp)).background(scheme.surfaceContainerHighest).padding(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onCancel) { Icon(Icons.Outlined.Delete, "Cancel recording", tint = scheme.error) }
        Box(Modifier.size(10.dp).alpha(pulse).clip(CircleShape).background(Flame))
        Spacer(Modifier.width(8.dp))
        Text(clock(elapsedMs), style = MaterialTheme.typography.titleSmall)
        Spacer(Modifier.width(10.dp))
        Canvas(Modifier.weight(1f).height(30.dp)) {
            val n = 40
            val w = size.width / n
            val recent = levels.takeLast(n)
            recent.forEachIndexed { i, l ->
                val h = size.height * (0.12f + 0.88f * l.coerceIn(0f, 1f))
                drawRoundRect(
                    color = scheme.primary,
                    topLeft = Offset((n - recent.size + i) * w + w * 0.2f, (size.height - h) / 2),
                    size = Size(w * 0.6f, h),
                    cornerRadius = CornerRadius(w * 0.3f),
                )
            }
        }
        Spacer(Modifier.width(6.dp))
        if (maxMs - elapsedMs < 15_000) Text(
            "${(maxMs - elapsedMs) / 1000}s left", style = MaterialTheme.typography.labelSmall, color = Flame,
        )
        IconButton(onClick = onStop) {
            Box(Modifier.size(36.dp).clip(CircleShape).background(Flame), contentAlignment = Alignment.Center) {
                Icon(Icons.Filled.Stop, "Stop recording", tint = Color.White)
            }
        }
    }
}

/** After recording: listen back, fix the transcript, or throw it away. */
@Composable
fun VoicePreview(
    uri: Uri, durationMs: Long, transcript: String, transcribing: Boolean, canTranscribe: Boolean,
    onTranscript: (String) -> Unit, onDelete: () -> Unit, modifier: Modifier = Modifier,
) {
    val scheme = MaterialTheme.colorScheme
    Column(modifier.clip(RoundedCornerShape(22.dp)).background(scheme.surfaceContainerHighest).padding(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.weight(1f).padding(start = 4.dp)) { VoiceNotePlayer(uri, mine = false, fallbackMs = durationMs) }
            IconButton(onClick = onDelete) { Icon(Icons.Outlined.Delete, "Delete voice note", tint = scheme.error) }
        }
        when {
            transcribing -> Text(
                "Transcribing…", Modifier.padding(8.dp, 4.dp),
                style = MaterialTheme.typography.bodySmall, fontStyle = FontStyle.Italic, color = scheme.onSurfaceVariant,
            )
            canTranscribe || transcript.isNotEmpty() -> TextField(
                value = transcript,
                onValueChange = onTranscript,
                placeholder = { Text("No words caught. Type a caption, or leave it empty") },
                textStyle = MaterialTheme.typography.bodyMedium.copy(fontStyle = FontStyle.Italic),
                modifier = Modifier.fillMaxWidth(),
                colors = TextFieldDefaults.colors(
                    focusedContainerColor = Color.Transparent, unfocusedContainerColor = Color.Transparent,
                    focusedIndicatorColor = Color.Transparent, unfocusedIndicatorColor = Color.Transparent,
                ),
                supportingText = { Text("Sent with the note so it can be read too") },
            )
        }
    }
}
