package dev.gaurish.outlinelite

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.net.VpnService
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import java.io.File
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.windowsizeclass.ExperimentalMaterial3WindowSizeClassApi
import androidx.compose.material3.windowsizeclass.WindowWidthSizeClass
import androidx.compose.material3.windowsizeclass.calculateWindowSizeClass
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import dev.gaurish.outlinelite.data.AccessKey
import dev.gaurish.outlinelite.data.DnsMode
import dev.gaurish.outlinelite.data.KeyRepository
import dev.gaurish.outlinelite.data.PairingRequest
import dev.gaurish.outlinelite.data.SsKeyParser
import dev.gaurish.outlinelite.data.ThemeMode
import dev.gaurish.outlinelite.data.isValid
import dev.gaurish.outlinelite.data.maskedToken
import dev.gaurish.outlinelite.transfer.ClipboardSyncManager
import dev.gaurish.outlinelite.transfer.ClusterPeer
import dev.gaurish.outlinelite.transfer.MediaSessionBridge
import dev.gaurish.outlinelite.transfer.NotificationMirrorManager
import dev.gaurish.outlinelite.tunnel.TunnelPhase
import dev.gaurish.outlinelite.tunnel.TunnelService
import dev.gaurish.outlinelite.tunnel.TunnelState
import dev.gaurish.outlinelite.ui.BrowseScreen
import dev.gaurish.outlinelite.ui.HistoryScreen
import dev.gaurish.outlinelite.ui.HomeScreen
import dev.gaurish.outlinelite.ui.OutlineTheme
import dev.gaurish.outlinelite.ui.ShareTargetDialog
import dev.gaurish.outlinelite.ui.Screens
import dev.gaurish.outlinelite.ui.SplitTunnelScreen
import dev.gaurish.outlinelite.ui.TrustedDevicesScreen
import dev.gaurish.outlinelite.ui.settings.ClipboardSyncScreen
import dev.gaurish.outlinelite.ui.settings.DnsScreen
import dev.gaurish.outlinelite.ui.settings.MediaScreen
import dev.gaurish.outlinelite.ui.settings.NotificationsScreen
import dev.gaurish.outlinelite.ui.settings.SettingsScreen
import dev.gaurish.outlinelite.ui.settings.TransfersScreen
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3WindowSizeClassApi::class)
class MainActivity : ComponentActivity() {

    private var pendingKeyId: String? = null
    private var pendingSharedUris by mutableStateOf<List<Uri>>(emptyList())
    private var pendingSharedText by mutableStateOf<String?>(null)
    private var pendingOpenOnArrival by mutableStateOf(false)
    private var pendingPairing by mutableStateOf<PairingRequest?>(null)

    private val consentLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK) {
            val keyId = pendingKeyId
            pendingKeyId = null
            lifecycleScope.launch {
                val key = if (keyId != null) {
                    val keys = KeyRepository.observeKeys(applicationContext).first()
                    keys.firstOrNull { it.id == keyId }
                } else {
                    KeyRepository.activeKey(applicationContext)
                }
                if (key != null) {
                    TunnelService.start(this@MainActivity, key)
                }
            }
        } else {
            pendingKeyId = null
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        pendingKeyId = savedInstanceState?.getString(KEY_PENDING_KEY_ID)
        pendingSharedUris = savedSharedUrisFrom(savedInstanceState)
            ?: sharedUrisFrom(intent)
        persistSharedUris(pendingSharedUris)
        pendingSharedText = savedInstanceState?.getString(KEY_PENDING_SHARED_TEXT)
            ?: sharedTextFrom(intent)
        pendingOpenOnArrival = savedInstanceState?.getBoolean(KEY_PENDING_OPEN_ON_ARRIVAL)
            ?: isOpenIntent(intent)

        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            ActivityCompat.requestPermissions(
                this, arrayOf(Manifest.permission.POST_NOTIFICATIONS), NOTIFICATION_REQUEST_CODE
            )
        }
        enableEdgeToEdge()
        handleSsIntent(intent)
        handlePairingIntent(intent)

        setContent {
            val themeMode by KeyRepository.observeThemeMode(applicationContext)
                .collectAsStateWithLifecycle(ThemeMode.SYSTEM)
            val isDark = when (themeMode) {
                ThemeMode.SYSTEM -> androidx.compose.foundation.isSystemInDarkTheme()
                ThemeMode.LIGHT -> false
                ThemeMode.DARK -> true
            }

            OutlineTheme(darkTheme = isDark) {
                Surface(modifier = androidx.compose.ui.Modifier.fillMaxSize()) {
                    val status by TunnelState.flow.collectAsStateWithLifecycle()
                    val keys by KeyRepository.observeKeys(applicationContext)
                        .collectAsStateWithLifecycle(emptyList())
                    val activeId by KeyRepository.observeActiveId(applicationContext)
                        .collectAsStateWithLifecycle(null)
                    val scope = rememberCoroutineScope()
                    val widthClass = calculateWindowSizeClass(this).widthSizeClass
                    var screen by rememberSaveable { mutableIntStateOf(Screens.HOME) }
                    var browsePeer by remember { mutableStateOf<ClusterPeer?>(null) }

                    when (screen) {
                        Screens.BROWSE -> {
                            val peer = browsePeer
                            if (peer == null) {
                                // A process restart can restore the screen id
                                // without the in-memory peer; go home instead
                                // of rendering an empty browser.
                                LaunchedEffect(Unit) { screen = Screens.HOME }
                            } else {
                                BackHandler { screen = Screens.HOME }
                                BrowseScreen(
                                    peer = peer,
                                    onBack = { screen = Screens.HOME },
                                )
                            }
                        }
                        Screens.TRANSFERS -> {
                            BackHandler { screen = Screens.SETTINGS }
                            TransfersScreen(
                                onBack = { screen = Screens.SETTINGS },
                                onOpenHistory = { screen = Screens.HISTORY },
                            )
                        }
                        Screens.MEDIA -> {
                            BackHandler { screen = Screens.SETTINGS }
                            MediaScreen(onBack = { screen = Screens.SETTINGS })
                        }
                        Screens.NOTIFICATIONS -> {
                            BackHandler { screen = Screens.SETTINGS }
                            NotificationsScreen(onBack = { screen = Screens.SETTINGS })
                        }
                        Screens.CLIPBOARD_SYNC -> {
                            BackHandler { screen = Screens.SETTINGS }
                            ClipboardSyncScreen(onBack = { screen = Screens.SETTINGS })
                        }
                        Screens.DNS -> {
                            BackHandler { screen = Screens.SETTINGS }
                            DnsScreen(onBack = { screen = Screens.SETTINGS })
                        }
                        Screens.TRUSTED_DEVICES -> {
                            BackHandler { screen = Screens.SETTINGS }
                            TrustedDevicesScreen(onBack = { screen = Screens.SETTINGS })
                        }
                        Screens.HISTORY -> {
                            BackHandler { screen = Screens.SETTINGS }
                            HistoryScreen(onBack = { screen = Screens.SETTINGS })
                        }
                        Screens.SPLIT_TUNNEL -> {
                            BackHandler { screen = Screens.SETTINGS }
                            SplitTunnelScreen(onBack = { screen = Screens.SETTINGS })
                        }
                        Screens.SETTINGS -> {
                            BackHandler { screen = Screens.HOME }
                            SettingsScreen(
                                onBack = { screen = Screens.HOME },
                                onOpenSplitTunnel = { screen = Screens.SPLIT_TUNNEL },
                                onOpenHistory = { screen = Screens.HISTORY },
                                onOpenTrustedDevices = { screen = Screens.TRUSTED_DEVICES },
                                onOpenDns = { screen = Screens.DNS },
                                onOpenClipboardSync = { screen = Screens.CLIPBOARD_SYNC },
                                onOpenNotifications = { screen = Screens.NOTIFICATIONS },
                                onOpenMedia = { screen = Screens.MEDIA },
                                onOpenTransfers = { screen = Screens.TRANSFERS },
                            )
                        }
                        else -> {
                            HomeScreen(
                                compact = widthClass == WindowWidthSizeClass.Compact,
                                status = status,
                                keys = keys,
                                activeId = activeId,
                                onConnectToggle = {
                                    when (status.phase) {
                                        TunnelPhase.DISCONNECTED -> {
                                            val key =
                                                keys.firstOrNull { it.id == activeId } ?: keys.firstOrNull()
                                            if (key == null) {
                                                Toast.makeText(
                                                    applicationContext,
                                                    getString(R.string.no_active_key),
                                                    Toast.LENGTH_SHORT
                                                ).show()
                                            } else {
                                                prepareAndStart(key)
                                            }
                                        }
                                        else -> TunnelService.stop(this)
                                    }
                                },
                                onSelect = { key ->
                                    scope.launch { KeyRepository.setActive(applicationContext, key.id) }
                                },
                                onRename = { key, newName ->
                                    scope.launch { KeyRepository.renameKey(applicationContext, key.id, newName) }
                                },
                                onDelete = { key ->
                                    scope.launch { KeyRepository.removeKey(applicationContext, key.id) }
                                },
                                onOpenSettings = { screen = Screens.SETTINGS },
                                onBrowsePeer = { peer ->
                                    browsePeer = peer
                                    screen = Screens.BROWSE
                                },
                            )
                        }
                    }

                    if (pendingSharedUris.isNotEmpty() || pendingSharedText != null) {
                        ShareTargetDialog(
                            uris = pendingSharedUris,
                            text = pendingSharedText,
                            openOnArrival = pendingOpenOnArrival,
                            onConnectToVpn = {
                                val key = keys.firstOrNull { it.id == activeId } ?: keys.firstOrNull()
                                if (key == null) {
                                    Toast.makeText(
                                        applicationContext,
                                        getString(R.string.no_active_key),
                                        Toast.LENGTH_SHORT,
                                    ).show()
                                } else {
                                    prepareAndStart(key)
                                }
                            },
                            onDismiss = {
                                pendingSharedUris = emptyList()
                                pendingSharedText = null
                            },
                        )
                    }

                    pendingPairing?.let { request ->
                        PairingConfirmDialog(
                            request = request,
                            onConfirm = {
                                pendingPairing = null
                                applyPairing(request)
                            },
                            onCancel = { pendingPairing = null },
                        )
                    }
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        // Picks up the overlay permission if the user granted it in Settings.
        ClipboardSyncManager.refresh(this)
        NotificationMirrorManager.refresh(this)
        MediaSessionBridge.refresh(this)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString(KEY_PENDING_KEY_ID, pendingKeyId)
        outState.putParcelableArrayList(KEY_PENDING_SHARED_URIS, ArrayList(pendingSharedUris))
        outState.putString(KEY_PENDING_SHARED_TEXT, pendingSharedText)
        outState.putBoolean(KEY_PENDING_OPEN_ON_ARRIVAL, pendingOpenOnArrival)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleSsIntent(intent)
        handlePairingIntent(intent)
        pendingSharedUris = sharedUrisFrom(intent)
        persistSharedUris(pendingSharedUris)
        pendingSharedText = sharedTextFrom(intent)
        pendingOpenOnArrival = isOpenIntent(intent)
    }

    /**
     * Keeps read access to shared content:// files across process restarts so
     * the history screen can retry a failed send. Providers that do not offer
     * persistable grants simply throw and are ignored.
     */
    private fun persistSharedUris(uris: List<Uri>) {
        uris.forEach { uri ->
            if (uri.scheme == "content") {
                runCatching {
                    contentResolver.takePersistableUriPermission(
                        uri,
                        Intent.FLAG_GRANT_READ_URI_PERMISSION,
                    )
                }
            }
        }
    }

    private fun prepareAndStart(key: AccessKey) {
        pendingKeyId = key.id
        lifecycleScope.launch {
            KeyRepository.setActive(this@MainActivity, key.id)
        }
        val consent: Intent? = VpnService.prepare(this)
        if (consent == null) {
            TunnelService.start(this, key)
        } else {
            consentLauncher.launch(consent)
        }
    }


    private fun handleSsIntent(intent: Intent?) {
        val data: String = intent?.data?.toString() ?: return
        if (!data.startsWith("ss://", ignoreCase = true)) return
        val key = SsKeyParser.parse(data)
        lifecycleScope.launch {
            if (key == null) {
                Toast.makeText(this@MainActivity, getString(R.string.key_invalid), Toast.LENGTH_SHORT).show()
            } else {
                val added = KeyRepository.addKey(this@MainActivity, key)
                if (added) KeyRepository.setActive(this@MainActivity, key.id)
                Toast.makeText(
                    this@MainActivity,
                    getString(if (added) R.string.key_added else R.string.key_exists),
                    Toast.LENGTH_SHORT
                ).show()
            }
        }
    }

    /**
     * Handles gaur://pair links produced by `gaur-relay -init`. The QR is
     * scanned with the system camera, so the app never needs camera
     * permission or an in-app scanner. The parsed request is only held in
     * [pendingPairing] until the user confirms it in
     * [PairingConfirmDialog]; nothing is written up front. The token is
     * never logged.
     */
    private fun handlePairingIntent(intent: Intent?) {
        val data = intent?.data ?: return
        if (!data.scheme.equals("gaur", ignoreCase = true) ||
            !data.host.equals("pair", ignoreCase = true)
        ) {
            return
        }
        val relay = data.getQueryParameter("relay")?.trim().orEmpty()
        val token = data.getQueryParameter("token")?.trim().orEmpty()
        val dns = data.getQueryParameter("dns")?.trim().orEmpty()
        val name = data.getQueryParameter("name")?.trim().orEmpty()
        if (token.isBlank()) {
            Toast.makeText(this, "Pairing code is missing the cluster token", Toast.LENGTH_LONG).show()
            return
        }
        val request = PairingRequest(relay = relay, token = token, dns = dns, name = name)
        if (!request.isValid()) {
            Toast.makeText(this, "Pairing code is malformed", Toast.LENGTH_LONG).show()
            return
        }
        pendingPairing = request
    }

    /** Applies a confirmed pairing request. */
    private fun applyPairing(request: PairingRequest) {
        lifecycleScope.launch {
            if (request.relay.isNotBlank()) {
                KeyRepository.setRelayAddress(this@MainActivity, request.relay)
            }
            KeyRepository.setClusterToken(this@MainActivity, request.token)
            if (request.dns.isNotBlank()) {
                KeyRepository.setDnsMode(this@MainActivity, DnsMode.ADGUARD)
                KeyRepository.setDnsServers(this@MainActivity, request.dns)
            }
            if (request.name.isNotBlank()) {
                KeyRepository.setDeviceName(this@MainActivity, request.name)
            }
            Toast.makeText(this@MainActivity, "Paired with cluster relay", Toast.LENGTH_SHORT).show()
        }
    }

    private fun sharedUrisFrom(intent: Intent?): List<Uri> {
        if (intent == null) return emptyList()
        val uris = when (intent.action) {
            Intent.ACTION_SEND -> {
                @Suppress("DEPRECATION")
                val streamUri = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
                } else {
                    intent.getParcelableExtra<Uri>(Intent.EXTRA_STREAM)
                }
                listOfNotNull(streamUri)
            }
            Intent.ACTION_SEND_MULTIPLE -> {
                @Suppress("DEPRECATION")
                val streamUris = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM, Uri::class.java)
                } else {
                    intent.getParcelableArrayListExtra<Uri>(Intent.EXTRA_STREAM)
                }
                streamUris ?: emptyList()
            }
            else -> emptyList()
        }
        if (uris.isNotEmpty()) return uris
        val clip = intent.clipData
        val clipUris = if (clip != null) {
            (0 until clip.itemCount).mapNotNull { clip.getItemAt(it)?.uri }
        } else {
            emptyList()
        }
        return clipUris.ifEmpty { listOfNotNull(intent.data) }
    }

    /**
     * Text and link shares arrive as ACTION_SEND with EXTRA_TEXT and no
     * stream. They are handled separately from file shares so a URL can be
     * sent without inventing a temporary file.
     */
    private fun sharedTextFrom(intent: Intent?): String? {
        if (intent?.action != Intent.ACTION_SEND) return null
        if (sharedUrisFrom(intent).isNotEmpty()) return null
        val text = intent.getStringExtra(Intent.EXTRA_TEXT)?.trim().orEmpty()
        return text.ifBlank { null }
    }

    /**
     * True when the activity was launched by an external share/open intent
     * (ACTION_SEND, ACTION_SEND_MULTIPLE, or a non-ss ACTION_VIEW), i.e. the
     * user picked gaurPN from "Open in..." / the Sharesheet. ss:// VIEW links
     * are access-key imports, not file opens.
     */
    private fun isOpenIntent(intent: Intent?): Boolean = when (intent?.action) {
        Intent.ACTION_SEND, Intent.ACTION_SEND_MULTIPLE -> true
        Intent.ACTION_VIEW -> {
            val scheme = intent.data?.scheme?.lowercase()
            scheme != "ss" && scheme != "gaur"
        }
        else -> false
    }

    private fun savedSharedUrisFrom(state: Bundle?): List<Uri>? {
        if (state == null) return null
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            state.getParcelableArrayList(KEY_PENDING_SHARED_URIS, Uri::class.java)
        } else {
            @Suppress("DEPRECATION")
            state.getParcelableArrayList<Uri>(KEY_PENDING_SHARED_URIS)
        }
    }

    private companion object {
        const val NOTIFICATION_REQUEST_CODE = 99
        const val KEY_PENDING_KEY_ID = "pending_key_id"
        const val KEY_PENDING_SHARED_URIS = "pending_shared_uris"
        const val KEY_PENDING_SHARED_TEXT = "pending_shared_text"
        const val KEY_PENDING_OPEN_ON_ARRIVAL = "pending_open_on_arrival"
    }
}

/**
 * Asks the user to confirm a gaur://pair request before it rewrites the
 * relay address, cluster token, DNS, and device name. Blank fields are
 * shown as "unchanged" because the confirm step only writes non-blank
 * values (except the token, which is always present at this point).
 */
@Composable
private fun PairingConfirmDialog(
    request: PairingRequest,
    onConfirm: () -> Unit,
    onCancel: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onCancel,
        title = { Text("Pair with cluster?") },
        text = {
            Column {
                PairingRow("Relay", request.relay.ifBlank { "unchanged" })
                PairingRow("Token", request.maskedToken())
                PairingRow("DNS", request.dns.ifBlank { "unchanged" })
                PairingRow("Device name", request.name.ifBlank { "unchanged" })
                Spacer(Modifier.height(12.dp))
                Text(
                    "Only continue if you scanned this QR yourself.",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.error,
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onConfirm) { Text("Pair") }
        },
        dismissButton = {
            TextButton(onClick = onCancel) { Text("Cancel") }
        },
    )
}

@Composable
private fun PairingRow(label: String, value: String) {
    Text(
        label,
        fontSize = 12.sp,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Text(value, fontSize = 14.sp)
    Spacer(Modifier.height(8.dp))
}
