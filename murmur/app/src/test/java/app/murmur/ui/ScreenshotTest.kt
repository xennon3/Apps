package app.murmur.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import app.cash.paparazzi.DeviceConfig
import app.cash.paparazzi.Paparazzi
import app.murmur.data.Box
import app.murmur.data.Conversation
import app.murmur.data.Delivery
import app.murmur.data.Message
import app.murmur.data.Pending
import app.murmur.data.Sims
import com.android.resources.NightMode
import org.junit.Rule
import org.junit.Test

/** Renders Murmur's screens on the JVM (no phone needed). Run: ./gradlew recordPaparazziDebug */
class ScreenshotTest {

    @get:Rule
    val paparazzi = Paparazzi(deviceConfig = DeviceConfig.PIXEL_6.copy(nightMode = NightMode.NIGHT))

    @Test fun onboarding() = paparazzi.snapshot {
        MurmurTheme { OnboardingScreen(isDefault = false, askedForRole = false, canRead = false, onMakeDefault = {}, onContinue = {}) }
    }

    @Test fun conversation() = paparazzi.snapshot { MurmurTheme { FakeThread("Can we talk about tonight?") } }

    @Test fun heated() = paparazzi.snapshot { MurmurTheme { FakeThread("ARE YOU KIDDING ME?? You ALWAYS do this") } }

    @Test fun dualSimGroup() = paparazzi.snapshot { MurmurTheme { FakeThread("who's bringing chips?", dualSim = true) } }
}

private const val NOW = 1_760_800_000_000L

@Composable
private fun FakeThread(draft: String, dualSim: Boolean = false) {
    val convo = Conversation(1, listOf("+15551234567"), "Maya Chen", null, "", NOW, false, true, false)
    fun msg(i: Int, box: Box, body: String, minsAgo: Int, d: Delivery = Delivery.NONE) =
        Message("s$i", i.toLong(), box, if (box == Box.IN) "+15551234567" else "", body, NOW - minsAgo * 60_000L, d)
    val rows = listOf(
        msg(1, Box.IN, "are you still coming to the thing tonight?", 42),
        msg(2, Box.IN, "jamie says it starts at 8 now", 41),
        msg(3, Box.OUT, "yes!! leaving work at 6:30", 30, Delivery.DELIVERED),
        msg(4, Box.IN, "Your Acme verification code is 482913", 12),
        msg(5, Box.OUT, "wait that was not meant for me lol", 11, Delivery.DELIVERED),
    )
    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        Row(Modifier.padding(16.dp, 40.dp, 16.dp, 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Avatar(convo.title, convo.addresses[0], null, 38.dp)
            Spacer(Modifier.width(12.dp))
            Text(convo.title, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurface)
        }
        Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
            Text("Today", Modifier.fillMaxWidth().padding(8.dp), textAlign = TextAlign.Center,
                style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            rows.forEachIndexed { i, m ->
                val prev = rows.getOrNull(i - 1)
                val next = rows.getOrNull(i + 1)
                Bubble(
                    Item.Msg(m, prev?.box != m.box || m.key == "s4", next?.box != m.box || m.key == "s3"),
                    convo, convo.addresses[0], onLongPress = {},
                )
            }
            WaitingBubble(Pending(9, 1, "+15551234567", "see you there 🎉", NOW + 3_000, false), {}, {})
            WaitingBubble(Pending(10, 1, "+15551234567", "happy birthday!!! 🎂", NOW + 86_400_000, true), {}, {})
        }
        val sims = if (dualSim) listOf(
            Sims.Sim(1, 0, "SIM 1", "Verizon", 0xFF7C4DFF.toInt()),
            Sims.Sim(2, 1, "SIM 2", "Mint", 0xFF4DD0E1.toInt()),
        ) else emptyList()
        Composer(text = draft, onText = {}, enabled = true, onSend = {}, onSchedule = {}, sims = sims, subId = 2, group = dualSim)
    }
}
