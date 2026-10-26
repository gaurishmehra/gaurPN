package dev.gaurish.outlinelite.tunnel

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.service.quicksettings.TileService
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import dev.gaurish.outlinelite.MainActivity
import dev.gaurish.outlinelite.R
import dev.gaurish.outlinelite.data.AccessKey
import dev.gaurish.outlinelite.data.KeyRepository
import dev.gaurish.outlinelite.data.SplitTunnelRepository
import dev.gaurish.outlinelite.outlinev.Outlinev
import dev.gaurish.outlinelite.outlinev.Protector
import dev.gaurish.outlinelite.tile.QsTileService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * The VPN service: owns the TUN device and wires it to the outlinev (Go) tunnel.
 *
 * Every socket the Go layer dials goes through [Protector.Protect], which is
 * VpnService.protect() — otherwise the tunnel's own traffic loops back into
 * the VPN and nothing ever loads.
 *
 * Outside the app, the notification (and the QS tile) mirror the phase. The
 * Go tunnel never self-disconnects (`isConnected()` stays true while the
 * process lives), so liveness is measured externally: a default-network
 * callback rebuilds the tunnel on network switches, and a periodic protected
 * reachability probe catches silent server-side drops.
 */
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class TunnelService : VpnService() {

    private var goTunnel: dev.gaurish.outlinelite.tun2socks.Tunnel? = null
    private var tunPfd: ParcelFileDescriptor? = null
    private var activeConfig: Pair<String, String>? = null
    private val tunnelDispatcher = Dispatchers.IO.limitedParallelism(1)
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var watchdog: Job? = null
    private var netCallback: ConnectivityManager.NetworkCallback? = null
    private var currentNetwork: Network? = null
    private var debounceJob: Job? = null
    private var userStopped = false
    private var failedAttempts = 0
    private var probeFailures = 0

    /**
     * Identity of the current lifecycle. Every teardown (stop, fail, bounce,
     * fresh start) increments it, and every async completion (a connect that
     * finished, a scheduled retry or probe result) must still match it to be
     * applied. This is what keeps overlapping attempts — tile during connect,
     * a retry racing a fresh start — from re-writing settled state.
     */
    @Volatile
    private var generation = 0

    private val protector: Protector = object : Protector {
        override fun protect(socket: Int): Boolean = this@TunnelService.protect(socket)
        override fun getResolvers(): String = systemResolverCsv()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.i(TAG, "onStartCommand: action=${intent?.action ?: "<null>"} gen=$generation")
        when (intent?.action) {
            ACTION_STOP -> {
                stopTunnel()
                return START_NOT_STICKY
            }
            ACTION_TOGGLE_LAN -> {
                val cfg = activeConfig
                if (cfg != null && TunnelState.status.phase == TunnelPhase.CONNECTED) {
                    val newLan = !TunnelState.status.allowLanAccess
                    TunnelState.update { it.copy(allowLanAccess = newLan) }
                    generation += 1
                    val gen = generation
                    cleanupTunnel()
                    serviceScope.launch(tunnelDispatcher) {
                        runCatching { connectTunnel(cfg.first, gen) }
                            .onFailure { t -> fail(t.logMessage()) }
                    }
                }
                return START_NOT_STICKY
            }
            ACTION_START -> {
                val config = intent.getStringExtra(EXTRA_CONFIG_JSON)
                val name = intent.getStringExtra(EXTRA_NAME) ?: "Server"
                if (config == null) {
                    stopTunnel()
                    return START_NOT_STICKY
                }
                if (goTunnel != null &&
                    TunnelState.status.phase == TunnelPhase.CONNECTED &&
                    activeConfig?.first == config
                ) {
                    return START_REDELIVER_INTENT // already up with the same server
                }
                userStopped = false
                generation += 1 // invalidates any in-flight connect, retry or probe
                failedAttempts = 0
                probeFailures = 0
                activeConfig = config to name
                TunnelState.update {
                    it.copy(
                        phase = TunnelPhase.CONNECTING,
                        lastError = null,
                        activeName = name,
                        connectedAt = 0L,
                        allowLanAccess = false
                    )
                }
                startForeground(NOTIFICATION_ID, buildNotification())
                notifyExternal(this)
                val configFinal = config
                val gen = generation
                serviceScope.launch(tunnelDispatcher) {
                    runCatching { connectTunnel(configFinal, gen) }
                        .onFailure { t -> fail(t.logMessage()) }
                }
            }
        }
        return START_REDELIVER_INTENT
    }

    private suspend fun connectTunnel(configJson: String, gen: Int) {
        Log.i(TAG, "connect start (gen $gen, lan=${TunnelState.status.allowLanAccess})")
        val builder = Builder()
            .setSession("gaurPN")
            .setMtu(MTU)
            .addAddress("26.26.26.1", 32)
            .addAddress("fd00::1", 128)
            .addDnsServer("8.8.8.8")
            .addDnsServer("1.1.1.1")
            .addDnsServer("2001:4860:4860::8888")
            .addDnsServer("2606:4700:4700::1111")

        LanBypassRoutes.applyRoutes(builder, TunnelState.status.allowLanAccess)

        // Per-app split tunneling
        val excluded = runCatching {
            SplitTunnelRepository.getExcludedPackages(this@TunnelService)
        }.getOrDefault(emptySet())
        for (pkg in excluded) {
            runCatching { builder.addDisallowedApplication(pkg) }
        }

        val pfd = builder.establish()
        if (pfd == null) {
            userStopped = true // consent not granted; retrying would be futile
            fail("VPN permission was denied")
            return
        }
        tunPfd = pfd
        // Go does not own the fd: the caller closes it (see the AAR docs).
        val tunnel = Outlinev.connectTunnel(pfd.getFd().toLong(), configJson, protector, true)
        if (gen != generation || userStopped) {
            Log.i(TAG, "connect superseded (gen $gen) — releasing, current gen $generation")
            runCatching { tunnel.disconnect() }
            runCatching { pfd.close() }
            return
        }
        goTunnel = tunnel
        failedAttempts = 0
        probeFailures = 0
        TunnelState.update {
            it.copy(
                phase = TunnelPhase.CONNECTED,
                connectedAt = SystemClock.elapsedRealtime(),
                lastError = null
            )
        }
        Log.i(TAG, "connected (gen $gen)")
        pushNotification()
        startNetworkMonitoring()
        startWatchdog()
        dev.gaurish.outlinelite.transfer.RelayManager.start(this@TunnelService)
    }

    /** Detects a dropped tunnel and probes server reachability; routes both through [fail] to re-establish. */
    private fun startWatchdog() {
        watchdog?.cancel()
        val gen = generation
        watchdog = serviceScope.launch {
            var tick = 0
            while (true) {
                delay(WATCHDOG_INTERVAL_MS)
                if (userStopped || gen != generation) return@launch
                if (TunnelState.status.phase != TunnelPhase.CONNECTED) return@launch
                val t = goTunnel
                val alive = t != null && runCatching { t.isConnected() }.getOrDefault(false)
                if (!alive) {
                    fail("Tunnel dropped — reconnecting…")
                    return@launch
                }
                tick += 1
                if (tick % PROBE_EVERY_TICKS == 0) {
                    val cfg = activeConfig ?: return@launch
                    probeServer(cfg, gen)
                }
            }
        }
    }

    /**
     * A failed/dropped tunnel. When "keep connection alive" is on and the stop was
     * not user-initiated, the tunnel is re-established with a short back-off burst;
     * if that exhausts, it degrades to a slow recovery cadence instead of quitting.
     */
    private fun fail(message: String) {
        Log.i(TAG, "fail: $message (gen $generation, attempt ${failedAttempts + 1})")
        watchdog?.cancel()
        watchdog = null
        debounceJob?.cancel()
        debounceJob = null
        stopNetworkMonitoring()
        generation += 1
        val gen = generation
        cleanupTunnel()
        val cfg = activeConfig
        if (userStopped || cfg == null) {
            giveUp(message)
            return
        }
        val wait =
            if (failedAttempts >= BACKOFF_MS.size) RETRY_CEILING_MS
            else BACKOFF_MS[failedAttempts]
        failedAttempts += 1
        serviceScope.launch {
            val keepAlive = runCatching {
                KeyRepository.autoReconnect(this@TunnelService)
            }.getOrDefault(true)
            if (!keepAlive || userStopped || gen != generation) {
                if (!keepAlive && gen == generation) giveUp(message)
                return@launch
            }
            TunnelState.update {
                it.copy(phase = TunnelPhase.CONNECTING, connectedAt = 0L, lastError = message)
            }
            pushNotification()
            notifyExternal(this@TunnelService)
            delay(wait)
            if (userStopped || gen != generation) return@launch
            Log.i(TAG, "retry: re-establishing (gen $gen)")
            reestablish(cfg, message, gen)
        }
    }

    /** Drops and re-establishes the same server with current network state. */
    private fun reestablish(cfg: Pair<String, String>, reason: String?, gen: Int) {
        if (userStopped || gen != generation) return
        TunnelState.update {
            it.copy(
                phase = TunnelPhase.CONNECTING,
                activeName = cfg.second,
                connectedAt = 0L,
                lastError = reason
            )
        }
        pushNotification()
        notifyExternal(this)
        serviceScope.launch(tunnelDispatcher) {
            runCatching { connectTunnel(cfg.first, gen) }
                .onFailure { t -> fail(t.logMessage()) }
        }
    }

    // ---------- network monitoring ----------

    private fun updateUnderlyingNetworks(network: Network?) {
        runCatching {
            if (network != null) {
                setUnderlyingNetworks(arrayOf(network))
            } else {
                setUnderlyingNetworks(null)
            }
        }
    }

    /**
     * Watches for real internet networks while the tunnel is up.
     * Uses active network tracking and a 1s debounce to rebuild quickly on genuine network transitions.
     */
    private fun startNetworkMonitoring() {
        runCatching {
            stopNetworkMonitoring()
            val cm = getSystemService(ConnectivityManager::class.java) ?: return
            currentNetwork = cm.activeNetwork
            updateUnderlyingNetworks(currentNetwork)
            val request = NetworkRequest.Builder()
                .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                .addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
                .build()
            val callback = object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) {
                    if (network != currentNetwork) {
                        currentNetwork = network
                        updateUnderlyingNetworks(network)
                        scheduleDebouncedBounce()
                    }
                }
                override fun onLost(network: Network) {
                    if (network == currentNetwork) {
                        currentNetwork = null
                        updateUnderlyingNetworks(null)
                        scheduleDebouncedBounce()
                    }
                }
            }
            cm.registerNetworkCallback(request, callback)
            netCallback = callback
        }
    }

    private fun stopNetworkMonitoring() {
        val callback = netCallback ?: return
        netCallback = null
        currentNetwork = null
        runCatching {
            getSystemService(ConnectivityManager::class.java)?.unregisterNetworkCallback(callback)
        }
    }

    private fun scheduleDebouncedBounce() {
        debounceJob?.cancel()
        debounceJob = serviceScope.launch {
            delay(1_000L) // 1s debounce to allow routing to settle
            if (userStopped || TunnelState.status.phase != TunnelPhase.CONNECTED) return@launch
            val cfg = activeConfig ?: return@launch
            watchdog?.cancel()
            watchdog = null
            failedAttempts = 0 // a network change is not a failure
            probeFailures = 0
            generation += 1
            val gen = generation
            Log.i(TAG, "bounce: network change — rebuilding tunnel (gen $gen)")
            cleanupTunnel()
            reestablish(cfg, null, gen)
        }
    }

    // ---------- server reachability probe ----------

    private fun probeServer(cfg: Pair<String, String>, gen: Int) {
        serviceScope.launch(Dispatchers.IO) {
            val ok = runCatching {
                Outlinev.checkServerReachable(cfg.first, protector)
            }.isSuccess
            if (userStopped || gen != generation ||
                TunnelState.status.phase != TunnelPhase.CONNECTED
            ) {
                return@launch
            }
            if (ok) {
                probeFailures = 0
                return@launch
            }
            probeFailures += 1
            if (probeFailures >= PROBE_FAILURES_LIMIT) {
                probeFailures = 0
                fail("Server unreachable — reconnecting…")
            }
        }
    }

    // ---------- teardown ----------

    private fun stopTunnel() {
        userStopped = true
        generation += 1
        watchdog?.cancel()
        watchdog = null
        debounceJob?.cancel()
        debounceJob = null
        stopNetworkMonitoring()
        TunnelState.update { it.copy(phase = TunnelPhase.STOPPING) }
        cleanupTunnel()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
        TunnelState.update {
            it.copy(
                phase = TunnelPhase.DISCONNECTED,
                connectedAt = 0L,
                allowLanAccess = false
            )
        }
        notifyExternal(this)
    }

    /** Final disconnect: clears state and removes the service surface. */
    private fun giveUp(message: String) {
        TunnelState.update {
            it.copy(
                phase = TunnelPhase.DISCONNECTED,
                lastError = message,
                connectedAt = 0L,
                allowLanAccess = false
            )
        }
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
        notifyExternal(this)
    }

    /** Closes the Go tunnel and the TUN file descriptor. */
    private fun cleanupTunnel() {
        updateUnderlyingNetworks(null)
        runCatching { goTunnel?.disconnect() }
        goTunnel = null
        tunPfd?.let { fd -> runCatching { fd.close() } }
        tunPfd = null
        dev.gaurish.outlinelite.transfer.RelayManager.stop()
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
        watchdog?.cancel()
        debounceJob?.cancel()
        stopNetworkMonitoring()
        serviceScope.cancel()
        cleanupTunnel()
        super.onDestroy()
    }

    // ---------- protection helpers ----------

    /** Comma-separated system DNS list for the Go resolver (excluding VPN virtual interface). */
    private fun systemResolverCsv(): String {
        val out = LinkedHashSet<String>()
        runCatching {
            val cm = getSystemService(ConnectivityManager::class.java)
            cm?.allNetworks?.forEach { network ->
                val caps = cm.getNetworkCapabilities(network)
                if (caps != null && !caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) {
                    val lp: LinkProperties? = cm.getLinkProperties(network)
                    lp?.dnsServers?.forEach { addr -> addr.hostAddress?.let { out.add(it) } }
                }
            }
        }
        if (out.isEmpty()) out.add("8.8.8.8")
        return out.joinToString(",")
    }

    // ---------- notification ----------

    /**
     * Posts the foreground notification. The notification uses the native system chronometer
     * when connected, rendering ticking elapsed uptime in the notification shade with 0 CPU wakeups.
     */
    private fun pushNotification() {
        runCatching {
            getSystemService(NotificationManager::class.java)
                ?.notify(NOTIFICATION_ID, buildNotification())
        }
    }

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
        val isConnected = TunnelState.status.phase == TunnelPhase.CONNECTED
        val connectedAt = TunnelState.status.connectedAt

        val builder = NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_key)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(notificationBody())
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setContentIntent(contentIntent)
            .addAction(0, getString(R.string.action_disconnect), stopIntent)

        if (isConnected && connectedAt > 0L) {
            val uptimeBase = System.currentTimeMillis() - (SystemClock.elapsedRealtime() - connectedAt)
            builder.setUsesChronometer(true)
                .setWhen(uptimeBase)
                .setShowWhen(true)
        } else {
            builder.setShowWhen(false)
        }

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
        private const val TAG = "gaurPN"

        const val ACTION_START = "dev.gaurish.outlinelite.action.START"
        const val ACTION_STOP = "dev.gaurish.outlinelite.action.STOP"
        const val ACTION_TOGGLE_LAN = "dev.gaurish.outlinelite.action.TOGGLE_LAN"
        private const val EXTRA_CONFIG_JSON = "config_json"
        private const val EXTRA_NAME = "name"
        private val WATCHDOG_INTERVAL_MS = 10_000L
        private val PROBE_EVERY_TICKS = 30 // 10 s watchdog ticks → probe ~ every 5 min
        private const val PROBE_FAILURES_LIMIT = 2
        private val BACKOFF_MS = longArrayOf(2_000L, 5_000L, 10_000L, 20_000L, 30_000L)
        private const val RETRY_CEILING_MS = 600_000L // keep-alive recovery cadence

        private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

        fun start(context: Context, key: AccessKey) {
            val intent = Intent(context, TunnelService::class.java)
                .setAction(ACTION_START)
                .putExtra(EXTRA_CONFIG_JSON, key.configJson())
                .putExtra(EXTRA_NAME, key.name)
            ContextCompat.startForegroundService(context, intent)
        }

        /** Push the current state to the QS tile. */
        fun notifyExternal(context: Context) {
            runCatching {
                TileService.requestListeningState(
                    context, ComponentName(context, QsTileService::class.java)
                )
            }
        }

        fun stop(context: Context) {
            context.startService(Intent(context, TunnelService::class.java).setAction(ACTION_STOP))
        }

        fun toggleLanAccess(context: Context) {
            context.startService(Intent(context, TunnelService::class.java).setAction(ACTION_TOGGLE_LAN))
        }

        /**
         * Phase-aware toggle for entry points that may not know the current
         * state (QS tile): running → stop; idle → connect with the active key.
         */
        fun requestToggle(context: Context) {
            appScope.launch {
                val phase = TunnelState.status.phase
                if (phase != TunnelPhase.DISCONNECTED) {
                    stop(context)
                    return@launch
                }
                val key = runCatching { KeyRepository.activeKey(context) }.getOrNull()
                if (key != null) start(context, key)
            }
        }
    }
}
