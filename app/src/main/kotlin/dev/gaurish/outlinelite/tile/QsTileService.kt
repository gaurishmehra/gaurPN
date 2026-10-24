package dev.gaurish.outlinelite.tile

import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
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
