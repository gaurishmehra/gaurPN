package dev.gaurish.outlinelite.tile

import android.app.PendingIntent
import android.content.Intent
import android.net.VpnService
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import dev.gaurish.outlinelite.MainActivity
import dev.gaurish.outlinelite.R
import dev.gaurish.outlinelite.tunnel.TunnelPhase
import dev.gaurish.outlinelite.tunnel.TunnelService
import dev.gaurish.outlinelite.tunnel.TunnelState

/**
 * Quick-settings tile: mirrors tunnel state, toggles the connection.
 *
 * Click rewrites the tile state optimistically — the next real state change
 * replays through [TunnelService.notifyExternal] (requestListeningState),
 * which triggers [onStartListening] → [refresh].
 */
class QsTileService : TileService() {

    override fun onStartListening() {
        refresh()
    }

    override fun onTileAdded() {
        refresh()
    }

    override fun onClick() {
        val tile = qsTile ?: return
        val running = TunnelState.status.phase != TunnelPhase.DISCONNECTED

        // Check if VPN consent has ever been granted
        val consentIntent = VpnService.prepare(this)
        if (consentIntent != null && !running) {
            val intent = Intent(this, MainActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                val pendingIntent = PendingIntent.getActivity(
                    this, 0, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
                )
                startActivityAndCollapse(pendingIntent)
            } else {
                @Suppress("DEPRECATION")
                startActivityAndCollapse(intent)
            }
            return
        }

        tile.state = if (running) Tile.STATE_INACTIVE else Tile.STATE_ACTIVE
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            runCatching {
                tile.subtitle = getString(
                    if (running) R.string.status_stopping else R.string.status_connecting
                )
            }
        }
        tile.updateTile()
        TunnelService.requestToggle(applicationContext)
    }

    private fun refresh() {
        val tile = qsTile ?: return
        val s = TunnelState.status
        tile.state = when (s.phase) {
            TunnelPhase.CONNECTED, TunnelPhase.CONNECTING -> Tile.STATE_ACTIVE
            else -> Tile.STATE_INACTIVE
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            runCatching {
                tile.subtitle = when (s.phase) {
                    TunnelPhase.CONNECTED -> s.activeName ?: getString(R.string.status_connected)
                    TunnelPhase.CONNECTING -> getString(R.string.status_connecting)
                    TunnelPhase.STOPPING -> getString(R.string.status_stopping)
                    TunnelPhase.DISCONNECTED ->
                        s.activeName ?: getString(R.string.status_disconnected)
                }
            }
        }
        tile.updateTile()
    }
}
