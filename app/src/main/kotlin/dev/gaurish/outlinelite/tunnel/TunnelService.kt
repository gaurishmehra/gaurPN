package dev.gaurish.outlinelite.tunnel

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.VpnService
import android.os.Build
import android.service.quicksettings.TileService
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.glance.appwidget.GlanceAppWidgetManager
import dev.gaurish.outlinelite.MainActivity
import dev.gaurish.outlinelite.R
import dev.gaurish.outlinelite.data.AccessKey
import dev.gaurish.outlinelite.outlinev.Outlinev
import dev.gaurish.outlinelite.outlinev.Protector
import dev.gaurish.outlinelite.tile.QsTileService
import dev.gaurish.outlinelite.widget.OutlineWidget
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.util.concurrent.Executors

/**
 * The VPN service: owns the TUN device and wires it to the outlinev (Go) tunnel.
 *
 * Every socket the Go layer dials goes through [Protector.Protect], which is
 * VpnService.protect() — otherwise the tunnel's own traffic loops back into
 * the VPN and nothing ever loads.
 */
class TunnelService : VpnService() {

    private var goTunnel: dev.gaurish.outlinelite.tun2socks.Tunnel? = null
    private var tunPfd: ParcelFileDescriptor? = null
    private var activeConfig: Pair<String, AccessKey>? = null
    private val executor = Executors.newSingleThreadExecutor()

    private val protector: Protector = object : Protector {
        override fun protect(socket: Int): Boolean = this@TunnelService.protect(socket)
        override fun getResolvers(): String = systemResolverCsv()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopTunnel()
                return START_NOT_STICKY
            }
            ACTION_START -> {
                val config = intent.getStringExtra(EXTRA_CONFIG_JSON)
                val name = intent.getStringExtra(EXTRA_NAME) ?: "Server"
                if (config == null) {
                    stopTunnel()
                    return START_NOT_STICKY
                }
                TunnelState.update {
                    it.copy(
                        phase = TunnelPhase.CONNECTING,
                        lastError = null,
                        activeName = name,
                        connectedAt = 0L
                    )
                }
                startForeground(NOTIFICATION_ID, buildNotification())
                notifyExternal(this)
                val configFinal = config
                executor.submit {
                    runCatching { connectTunnel(configFinal) }
                        .onFailure { t -> fail(t.logMessage()) }
                }
            }
        }
        return START_REDELIVER_INTENT
    }

    private fun connectTunnel(configJson: String) {
        val builder = Builder()
            .setSession("Outline Lite")
            .setMtu(MTU)
            .addAddress("26.26.26.1", 32)
            .addRoute("0.0.0.0", 0)
            .addDnsServer("8.8.8.8")
            .addDnsServer("1.1.1.1")
        val pfd = builder.establish()
        if (pfd == null) {
            fail("VPN permission was denied")
            return
        }
        tunPfd = pfd
        // Go does not own the fd: the caller closes it (see the AAR docs).
        val tunnel = Outlinev.connectTunnel(pfd.getFd().toLong(), configJson, protector, true)
        goTunnel = tunnel
        TunnelState.update {
            it.copy(
                phase = TunnelPhase.CONNECTED,
                connectedAt = SystemClock.elapsedRealtime(),
                lastError = null
            )
        }
        notifyState()
    }

    private fun stopTunnel() {
        TunnelState.update { it.copy(phase = TunnelPhase.STOPPING) }
        runCatching { goTunnel?.disconnect() }
        goTunnel = null
        tunPfd?.let { fd -> runCatching { fd.close() } }
        tunPfd = null
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
        TunnelState.update { it.copy(phase = TunnelPhase.DISCONNECTED, connectedAt = 0L) }
        notifyExternal(this)
    }

    private fun fail(message: String) {
        TunnelState.update {
            it.copy(phase = TunnelPhase.DISCONNECTED, lastError = message, connectedAt = 0L)
        }
        runCatching { tunPfd?.close() }
        tunPfd = null
        goTunnel = null
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
        notifyExternal(this)
    }

    private fun Throwable.logMessage(): String {
        var t: Throwable? = this
        while (t != null) {
            t.message?.let { m -> if (m.isNotBlank()) return m }
            t = t.cause
        }
        return "Unknown tunnel error"
    }

    override fun onRevoke() {
        stopTunnel()
    }

    override fun onDestroy() {
        runCatching { goTunnel?.disconnect() }
        runCatching { tunPfd?.close() }
        executor.shutdown()
        super.onDestroy()
    }

    // ---------- protection helpers ----------

    /** Comma-separated system DNS list for the Go resolver (protected lookups). */
    private fun systemResolverCsv(): String {
        val out = LinkedHashSet<String>()
        runCatching {
            val cm = getSystemService(ConnectivityManager::class.java)
            cm?.allNetworks?.forEach { network ->
                val lp: LinkProperties? = cm.getLinkProperties(network)
                lp?.dnsServers?.forEach { addr -> addr.hostAddress?.let { out.add(it) } }
            }
        }
        if (out.isEmpty()) out.add("8.8.8.8")
        return out.joinToString(",")
    }

    // ---------- notification ----------

    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = getSystemService(NotificationManager::class.java)
            if (nm?.getNotificationChannel(CHANNEL) == null) {
                nm?.createNotificationChannel(
                    NotificationChannel(CHANNEL, getString(R.string.app_name), NotificationManager.IMPORTANCE_LOW)
                )
            }
        }
    }

    private fun buildNotification(): Notification {
        ensureChannel()
        val contentIntent = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val stopIntent = PendingIntent.getService(
            this, 1, Intent(this, TunnelService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val builder = NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_launcher)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(notificationBody())
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setContentIntent(contentIntent)
            .addAction(0, getString(R.string.action_disconnect), stopIntent)
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            builder.setChannelId(CHANNEL).build()
        } else {
            @Suppress("DEPRECATION")
            builder.setPriority(NotificationCompat.PRIORITY_LOW).build()
        }
    }

    private fun notificationBody(): String {
        return when (TunnelState.status.phase) {
            TunnelPhase.CONNECTED -> getString(R.string.notif_connected, TunnelState.status.activeName ?: "")
            TunnelPhase.CONNECTING -> getString(R.string.notif_connecting)
            else -> getString(R.string.app_name)
        }
    }

    companion object {
        private const val CHANNEL = "outline_lite_tunnel"
        private const val NOTIFICATION_ID = 4041
        private const val MTU = 1400

        const val ACTION_START = "dev.gaurish.outlinelite.action.START"
        const val ACTION_STOP = "dev.gaurish.outlinelite.action.STOP"
        private const val EXTRA_CONFIG_JSON = "config_json"
        private const val EXTRA_NAME = "name"

        private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

        fun start(context: Context, key: AccessKey) {
            val intent = Intent(context, TunnelService::class.java)
                .setAction(ACTION_START)
                .putExtra(EXTRA_CONFIG_JSON, key.configJson())
                .putExtra(EXTRA_NAME, key.name)
            ContextCompat.startForegroundService(context, intent)
        }

        /** Push the current state to the QS tile and home-screen widgets. */
        fun notifyExternal(context: Context) {
            appScope.launch {
                runCatching {
                    val ids = GlanceAppWidgetManager(context).getGlanceIds(OutlineWidget::class.java)
                    ids.forEach { id -> OutlineWidget().update(context, id) }
                }
                runCatching {
                    TileService.requestListeningState(context, ComponentName(context, QsTileService::class.java))
                }
            }
        }

        fun stop(context: Context) {
            context.startService(Intent(context, TunnelService::class.java).setAction(ACTION_STOP))
        }

        /** Connect/disconnect with the currently active key. */
        fun toggle(context: Context) {
            appScope.launch {
                val key = runCatching { dev.gaurish.outlinelite.data.KeyRepository.activeKey(context) }.getOrNull()
                if (key != null) start(context, key) else stop(context)
            }
        }
    }
}
