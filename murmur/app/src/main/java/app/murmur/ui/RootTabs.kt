package app.murmur.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Chat
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

enum class Tab { MESSAGES, BRAIN }

/** The two home screens: your conversations, and a chat with Murmur Brain. */
@Composable
fun RootTabs(tab: Tab, onTab: (Tab) -> Unit, messages: @Composable () -> Unit, brain: @Composable () -> Unit) {
    Scaffold(
        bottomBar = {
            NavigationBar {
                NavigationBarItem(
                    selected = tab == Tab.MESSAGES, onClick = { onTab(Tab.MESSAGES) },
                    icon = { Icon(Icons.AutoMirrored.Outlined.Chat, null) }, label = { Text("Messages") },
                )
                NavigationBarItem(
                    selected = tab == Tab.BRAIN, onClick = { onTab(Tab.BRAIN) },
                    icon = { Icon(Icons.Outlined.AutoAwesome, null) }, label = { Text("Brain") },
                )
            }
        },
    ) { pad ->
        Box(Modifier.fillMaxSize().padding(bottom = pad.calculateBottomPadding())) {
            when (tab) {
                Tab.MESSAGES -> messages()
                Tab.BRAIN -> brain()
            }
        }
    }
}
