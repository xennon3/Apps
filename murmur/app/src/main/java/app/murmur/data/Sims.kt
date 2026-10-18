package app.murmur.data

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.telephony.SubscriptionManager
import android.telephony.TelephonyManager
import androidx.core.content.ContextCompat

/** The phone's active SIMs, for dual-SIM phones. */
object Sims {
    data class Sim(val subId: Int, val slot: Int, val label: String, val carrier: String, val color: Int)

    private fun allowed(context: Context, perm: String) =
        ContextCompat.checkSelfPermission(context, perm) == PackageManager.PERMISSION_GRANTED

    @SuppressLint("MissingPermission")
    fun active(context: Context): List<Sim> {
        if (!allowed(context, Manifest.permission.READ_PHONE_STATE)) return emptyList()
        return runCatching {
            context.getSystemService(SubscriptionManager::class.java).activeSubscriptionInfoList.orEmpty()
                .sortedBy { it.simSlotIndex }
                .map {
                    Sim(
                        subId = it.subscriptionId,
                        slot = it.simSlotIndex,
                        label = "SIM ${it.simSlotIndex + 1}",
                        carrier = it.displayName?.toString().orEmpty(),
                        color = it.iconTint,
                    )
                }
        }.getOrDefault(emptyList())
    }

    fun defaultSms(): Int = SubscriptionManager.getDefaultSmsSubscriptionId()

    /** This phone's own numbers, so group messages don't list you as a member. */
    @SuppressLint("MissingPermission", "HardwareIds")
    fun ownNumbers(context: Context): Set<String> {
        val out = HashSet<String>()
        runCatching {
            val sm = context.getSystemService(SubscriptionManager::class.java)
            for (s in active(context)) {
                val n = if (Build.VERSION.SDK_INT >= 33 && allowed(context, Manifest.permission.READ_PHONE_NUMBERS)) {
                    sm.getPhoneNumber(s.subId)
                } else {
                    @Suppress("DEPRECATION") sm.activeSubscriptionInfoList?.firstOrNull { it.subscriptionId == s.subId }?.number
                }
                if (!n.isNullOrBlank()) out += n
            }
            if (allowed(context, Manifest.permission.READ_PHONE_NUMBERS) || allowed(context, Manifest.permission.READ_PHONE_STATE)) {
                @Suppress("DEPRECATION")
                context.getSystemService(TelephonyManager::class.java).line1Number?.takeIf { it.isNotBlank() }?.let { out += it }
            }
        }
        return out.map(::key).filter { it.isNotEmpty() }.toSet()
    }

    /** Last 10 digits: enough to tell "+1 555 123 4567" and "(555) 123-4567" are the same phone. */
    fun key(number: String): String = number.filter { it.isDigit() }.takeLast(10)
}
