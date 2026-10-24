package dev.gaurish.outlinelite.boot

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import dev.gaurish.outlinelite.data.KeyRepository
import dev.gaurish.outlinelite.tunnel.TunnelService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/** Boots straight into the tunnel when "connect on boot" is enabled. */
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                if (!KeyRepository.autostart(context)) return@launch
                val key = KeyRepository.activeKey(context) ?: return@launch
                TunnelService.start(context.applicationContext, key)
            } finally {
                pending.finish()
            }
        }
    }
}
