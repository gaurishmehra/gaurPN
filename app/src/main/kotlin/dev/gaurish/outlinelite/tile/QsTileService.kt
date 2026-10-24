package dev.gaurish.outlinelite.tile

import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import dev.gaurish.outlinelite.R
import dev.gaurish.outlinelite.tunnel.TunnelPhase
import dev.gaurish.outlinelite.tunnel.TunnelService
import dev.gaurish.outlinelite.tunnel.TunnelState

/**
 * Quick-settings tile: mirrors tunnel state, toggles on click.
 * State refreshes driven by [TunnelService.notifyExternal].
 */
class QsTileService : TileService() {

    override fun onStartListening() {
        refresh()
    }

    override fun onClick() {
        TunnelService.toggle(applicationContext)
        refresh()
    }

    private fun refresh() {
        val tile = qsTile ?: return
        val s = TunnelState.status
        tile.state = when (s.phase) {
            TunnelPhase.CONNECTED -> Tile.STATE_ACTIVE
            else -> Tile.STATE_INACTIVE
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            runCatching {
                tile.subtitle = when (s.phase) {
                    TunnelPhase.CONNECTED -> s.activeName ?: getString(R.string.status_connected)
                    TunnelPhase.CONNECTING -> getString(R.string.status_connecting)
                    TunnelPhase.STOPPING -> getString(R.string.status_stopping)
                    TunnelPhase.DISCONNECTED -> s.activeName ?: getString(R.string.status_disconnected)
                }
            }
        }
        tile.updateTile()
    }
}
