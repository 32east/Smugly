package app.smugly.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.VpnService
import androidx.core.content.ContextCompat
import app.smugly.ConfigStore
import app.smugly.needsVpnService
import app.smugly.util.AppLog

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action !in BOOT_ACTIONS) return
        AppLog.init(context)
        val global = ConfigStore.loadGlobalSettings(context)
        if (!global.autoConnectOnBoot) return
        val config = ConfigStore.effectiveConfig(context)
        if (!config.needsVpnService()) {
            AppLog.w(TAG, "auto-connect skipped: proxy mode runs inside the app, not as a service")
            return
        }
        val consent = if (global.rootMode.isActive) null else runCatching { VpnService.prepare(context) }.getOrNull()
        if (consent != null) {
            AppLog.w(TAG, "auto-connect skipped: VPN permission has not been granted yet")
            return
        }
        AppLog.i(TAG, "auto-connect after ${intent.action} protocol=${config.protocol}")
        runCatching {
            ContextCompat.startForegroundService(
                context,
                Intent(context, TinyVpnService::class.java)
                    .setAction(TinyVpnService.ACTION_START)
                    .putExtra(TinyVpnService.EXTRA_FOREGROUND, true)
            )
        }.onFailure { AppLog.e(TAG, "auto-connect failed to start the service", it) }
    }

    companion object {
        private const val TAG = "BootReceiver"
        val BOOT_ACTIONS = setOf(
            Intent.ACTION_BOOT_COMPLETED,
            "android.intent.action.QUICKBOOT_POWERON",
            "com.htc.intent.action.QUICKBOOT_POWERON"
        )
    }
}
