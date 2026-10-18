package app.murmur

import android.Manifest
import android.app.role.RoleManager
import android.content.Intent
import android.content.pm.PackageManager
import android.database.ContentObserver
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Telephony
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import app.murmur.data.Phone
import app.murmur.data.Prefs
import app.murmur.data.SmsRepository
import app.murmur.ui.ConversationsScreen
import app.murmur.ui.MurmurTheme
import app.murmur.ui.NewMessageScreen
import app.murmur.ui.OnboardingScreen
import app.murmur.ui.ScheduledScreen
import app.murmur.ui.SettingsScreen
import app.murmur.ui.ThreadScreen
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

sealed interface Screen {
    data object Onboarding : Screen
    data object List : Screen
    data class Thread(val threadId: Long, val prefill: String = "") : Screen
    data class New(val prefill: String = "") : Screen
    data object Settings : Screen
    data object Scheduled : Screen
}

class MainActivity : ComponentActivity() {

    private var stack by mutableStateOf(listOf<Screen>(Screen.List))

    /** Bumped whenever the SMS store changes, so every screen reloads. */
    private var version by mutableIntStateOf(0)
    private var isDefault by mutableStateOf(false)
    private var askedForRole by mutableStateOf(false)

    private val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
        override fun onChange(selfChange: Boolean) { version++ }
    }

    private val roleLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        askedForRole = true
        refreshDefault()
        if (isDefault) askForTheRest()
    }

    private val permsLauncher = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        SmsRepository(this).clearContactCache()
        version++
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        refreshDefault()
        if (!canRead()) stack = listOf(Screen.Onboarding)
        else if (isDefault && savedInstanceState == null) askForTheRest() // picks up permissions added in updates
        handle(intent)
        contentResolver.registerContentObserver(Uri.parse("content://mms-sms/"), true, observer)
        contentResolver.registerContentObserver(Telephony.Sms.CONTENT_URI, true, observer)

        setContent {
            MurmurTheme {
                val top = stack.last()
                BackHandler(enabled = stack.size > 1) { pop() }
                AnimatedContent(
                    targetState = top,
                    transitionSpec = {
                        val forward = targetState !is Screen.List && targetState !is Screen.Onboarding
                        if (forward) {
                            (slideInHorizontally(tween(260)) { it / 3 } + fadeIn(tween(200))) togetherWith
                                (slideOutHorizontally(tween(260)) { -it / 6 } + fadeOut(tween(160)))
                        } else {
                            (slideInHorizontally(tween(260)) { -it / 6 } + fadeIn(tween(200))) togetherWith
                                (slideOutHorizontally(tween(260)) { it / 3 } + fadeOut(tween(160)))
                        }
                    },
                    label = "nav",
                ) { screen ->
                    when (screen) {
                        Screen.Onboarding -> OnboardingScreen(
                            isDefault = isDefault,
                            askedForRole = askedForRole,
                            canRead = canRead(),
                            onMakeDefault = ::requestRole,
                            onContinue = {
                                askForTheRest()
                                stack = listOf(Screen.List)
                            },
                        )
                        Screen.List -> ConversationsScreen(
                            version = version,
                            isDefault = isDefault,
                            onOpen = { push(Screen.Thread(it)) },
                            onNew = { push(Screen.New()) },
                            onSettings = { push(Screen.Settings) },
                            onScheduled = { push(Screen.Scheduled) },
                            onMakeDefault = { stack = listOf(Screen.Onboarding) },
                        )
                        is Screen.Thread -> ThreadScreen(
                            threadId = screen.threadId,
                            prefill = screen.prefill,
                            version = version,
                            isDefault = isDefault,
                            onBack = ::pop,
                        )
                        is Screen.New -> NewMessageScreen(
                            prefill = screen.prefill,
                            onBack = ::pop,
                            onPick = { address -> openAddress(address, screen.prefill, replace = true) },
                        )
                        Screen.Scheduled -> ScheduledScreen(onBack = ::pop, onOpen = { push(Screen.Thread(it)) })
                        Screen.Settings -> SettingsScreen(
                            isDefault = isDefault,
                            onMakeDefault = ::requestRole,
                            onBack = ::pop,
                        )
                    }
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        refreshDefault()
        version++
    }

    override fun onDestroy() {
        contentResolver.unregisterContentObserver(observer)
        super.onDestroy()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handle(intent)
    }

    private fun push(s: Screen) { stack = stack + s }

    private fun pop() {
        if (stack.size > 1) stack = stack.dropLast(1) else finish()
    }

    private fun handle(intent: Intent?) {
        intent ?: return
        when (intent.action) {
            OPEN_THREAD -> {
                val t = intent.getLongExtra("thread", -1)
                if (t >= 0 && canRead()) stack = listOf(Screen.List, Screen.Thread(t))
            }
            Intent.ACTION_SENDTO, Intent.ACTION_VIEW -> {
                val data = intent.data ?: return
                val ssp = data.schemeSpecificPart.orEmpty()
                val number = ssp.substringBefore('?').split(',', ';').firstOrNull()?.trim().orEmpty()
                val body = intent.getStringExtra("sms_body")
                    ?: intent.getStringExtra(Intent.EXTRA_TEXT)
                    ?: Uri.parse("x:?" + ssp.substringAfter('?', "")).getQueryParameter("body")
                    ?: ""
                if (number.isNotBlank() && canRead()) openAddress(number, body, replace = false)
                else if (canRead()) stack = listOf(Screen.List, Screen.New(body))
            }
            Intent.ACTION_SEND -> {
                val text = intent.getStringExtra(Intent.EXTRA_TEXT).orEmpty()
                val data = intent.data
                if (data != null && canRead()) {
                    val number = data.schemeSpecificPart.orEmpty().substringBefore('?')
                    if (number.isNotBlank()) { openAddress(number, text, replace = false); return }
                }
                if (canRead()) stack = listOf(Screen.List, Screen.New(text))
            }
        }
    }

    private fun openAddress(address: String, prefill: String, replace: Boolean) {
        CoroutineScope(Dispatchers.Main).launch {
            val t = withContext(Dispatchers.IO) {
                runCatching { SmsRepository(this@MainActivity).threadIdFor(Phone.digits(address).ifEmpty { address }) }.getOrNull()
            } ?: return@launch
            if (prefill.isNotBlank()) Prefs(this@MainActivity).setDraft(t, prefill)
            stack = if (replace) stack.dropLast(1) + Screen.Thread(t, prefill)
            else listOf(Screen.List, Screen.Thread(t, prefill))
        }
    }

    private fun canRead() =
        ContextCompat.checkSelfPermission(this, Manifest.permission.READ_SMS) == PackageManager.PERMISSION_GRANTED

    private fun refreshDefault() {
        isDefault = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            getSystemService(RoleManager::class.java).isRoleHeld(RoleManager.ROLE_SMS)
        } else {
            Telephony.Sms.getDefaultSmsPackage(this) == packageName
        }
    }

    @Suppress("DEPRECATION")
    private fun requestRole() {
        val intent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            getSystemService(RoleManager::class.java).createRequestRoleIntent(RoleManager.ROLE_SMS)
        } else {
            Intent(Telephony.Sms.Intents.ACTION_CHANGE_DEFAULT)
                .putExtra(Telephony.Sms.Intents.EXTRA_PACKAGE_NAME, packageName)
        }
        roleLauncher.launch(intent)
    }

    private fun askForTheRest() {
        val want = buildList {
            add(Manifest.permission.READ_CONTACTS)
            add(Manifest.permission.READ_PHONE_STATE)
            add(Manifest.permission.READ_PHONE_NUMBERS)
            if (!isDefault) { add(Manifest.permission.READ_SMS); add(Manifest.permission.SEND_SMS) }
            if (Build.VERSION.SDK_INT >= 33) add(Manifest.permission.POST_NOTIFICATIONS)
        }.filter { ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED }
        if (want.isNotEmpty()) permsLauncher.launch(want.toTypedArray())
    }

    companion object {
        const val OPEN_THREAD = "app.murmur.OPEN_THREAD"
    }
}
