package app.murmur.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.text.format.DateFormat
import android.text.format.DateUtils
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.murmur.data.Vibes
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.Calendar
import java.util.concurrent.ConcurrentHashMap

private val bitmapCache = ConcurrentHashMap<String, ImageBitmap>()

/** Loads a content:// image off the main thread, scaled down to roughly [maxPx]. */
@Composable
fun rememberImage(uri: Uri?, maxPx: Int = 256): ImageBitmap? {
    val context = LocalContext.current
    val img by produceState<ImageBitmap?>(initialValue = uri?.let { bitmapCache["$it@$maxPx"] }, uri) {
        if (uri == null || value != null) return@produceState
        value = withContext(Dispatchers.IO) { decode(context, uri, maxPx) }?.also { bitmapCache["$uri@$maxPx"] = it }
    }
    return img
}

private fun decode(context: Context, uri: Uri, maxPx: Int): ImageBitmap? = runCatching {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
    var sample = 1
    while (bounds.outWidth / (sample * 2) >= maxPx && bounds.outHeight / (sample * 2) >= maxPx) sample *= 2
    val opts = BitmapFactory.Options().apply { inSampleSize = sample; inPreferredConfig = Bitmap.Config.ARGB_8888 }
    context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, opts) }?.asImageBitmap()
}.getOrNull()

@Composable
fun Avatar(title: String, seed: String, photo: Uri?, size: Dp = 48.dp) {
    val img = rememberImage(photo, 160)
    val base = Color(Vibes.color(seed))
    Box(
        Modifier.size(size).clip(CircleShape)
            .background(Brush.linearGradient(listOf(base, base.copy(alpha = 0.55f)))),
        contentAlignment = Alignment.Center,
    ) {
        if (img != null) {
            Image(img, null, Modifier.size(size), contentScale = ContentScale.Crop)
        } else {
            Text(
                Vibes.initials(title),
                color = Color(0xFF1B1430),
                fontWeight = FontWeight.Bold,
                fontSize = (size.value * 0.36f).sp,
            )
        }
    }
}

object When {
    fun short(context: Context, t: Long): String {
        val now = System.currentTimeMillis()
        return when {
            now - t < 60_000 -> "now"
            DateUtils.isToday(t) -> DateFormat.getTimeFormat(context).format(t)
            now - t < 6 * DateUtils.DAY_IN_MILLIS ->
                DateUtils.formatDateTime(context, t, DateUtils.FORMAT_SHOW_WEEKDAY or DateUtils.FORMAT_ABBREV_WEEKDAY)
            sameYear(t, now) ->
                DateUtils.formatDateTime(context, t, DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_ABBREV_MONTH or DateUtils.FORMAT_NO_YEAR)
            else -> DateUtils.formatDateTime(context, t, DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_NUMERIC_DATE)
        }
    }

    fun time(context: Context, t: Long): String = DateFormat.getTimeFormat(context).format(t)

    fun day(context: Context, t: Long): String = when {
        DateUtils.isToday(t) -> "Today"
        DateUtils.isToday(t + DateUtils.DAY_IN_MILLIS) -> "Yesterday"
        else -> DateUtils.formatDateTime(
            context, t,
            DateUtils.FORMAT_SHOW_WEEKDAY or DateUtils.FORMAT_SHOW_DATE or
                (if (sameYear(t, System.currentTimeMillis())) DateUtils.FORMAT_NO_YEAR else 0),
        )
    }

    /** "Tomorrow 8:00 AM", "Fri 6:30 PM" … */
    fun future(context: Context, t: Long): String {
        val prefix = when {
            DateUtils.isToday(t) -> "Today"
            DateUtils.isToday(t - DateUtils.DAY_IN_MILLIS) -> "Tomorrow"
            t - System.currentTimeMillis() < 6 * DateUtils.DAY_IN_MILLIS ->
                DateUtils.formatDateTime(context, t, DateUtils.FORMAT_SHOW_WEEKDAY or DateUtils.FORMAT_ABBREV_WEEKDAY)
            else -> DateUtils.formatDateTime(context, t, DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_ABBREV_MONTH)
        }
        return "$prefix ${time(context, t)}"
    }

    fun sameDay(a: Long, b: Long): Boolean {
        val x = Calendar.getInstance().apply { timeInMillis = a }
        val y = Calendar.getInstance().apply { timeInMillis = b }
        return x.get(Calendar.YEAR) == y.get(Calendar.YEAR) && x.get(Calendar.DAY_OF_YEAR) == y.get(Calendar.DAY_OF_YEAR)
    }

    private fun sameYear(a: Long, b: Long) =
        Calendar.getInstance().apply { timeInMillis = a }.get(Calendar.YEAR) ==
            Calendar.getInstance().apply { timeInMillis = b }.get(Calendar.YEAR)

    /** Today/tomorrow at [hour]:00, whichever is next. */
    fun nextAt(hour: Int, minute: Int = 0): Long {
        val c = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, hour); set(Calendar.MINUTE, minute)
            set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
        }
        if (c.timeInMillis <= System.currentTimeMillis()) c.add(Calendar.DAY_OF_YEAR, 1)
        return c.timeInMillis
    }
}
