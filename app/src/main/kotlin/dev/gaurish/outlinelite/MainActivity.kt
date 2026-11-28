package dev.gaurish.outlinelite

import android.Manifest
import android.app.Activity
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
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.ContentTransform
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.draw.drawBehind
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.NavigationRailItemDefaults
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.windowsizeclass.ExperimentalMaterial3WindowSizeClassApi
import androidx.compose.material3.windowsizeclass.WindowWidthSizeClass
import androidx.compose.material3.windowsizeclass.calculateWindowSizeClass
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
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
import dev.gaurish.outlinelite.transfer.CameraRequestNotifications
import dev.gaurish.outlinelite.transfer.RelayManager
import dev.gaurish.outlinelite.transfer.MediaSessionBridge
import dev.gaurish.outlinelite.transfer.NotificationMirrorManager
import dev.gaurish.outlinelite.tunnel.TunnelPhase
import dev.gaurish.outlinelite.tunnel.TunnelService
import dev.gaurish.outlinelite.tunnel.TunnelState
import dev.gaurish.outlinelite.ui.BrowseScreen
import dev.gaurish.outlinelite.ui.BrowseSort
import dev.gaurish.outlinelite.ui.CameraPreviewScreen
import dev.gaurish.outlinelite.ui.HistoryScreen
import dev.gaurish.outlinelite.ui.HomeScreen
import dev.gaurish.outlinelite.ui.Motion
import dev.gaurish.outlinelite.ui.OutlineTheme
import dev.gaurish.outlinelite.ui.ServersScreen
import dev.gaurish.outlinelite.ui.ShareTargetDialog
import dev.gaurish.outlinelite.ui.Screens
import dev.gaurish.outlinelite.ui.SplitTunnelScreen
import dev.gaurish.outlinelite.ui.TransferOverlay
import dev.gaurish.outlinelite.ui.TrustedDevicesScreen
import dev.gaurish.outlinelite.ui.settings.CameraScreen
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

    /** A camera notification asked for the preview screen; consumed by the composition. */
    private var pendingRouteCamera by mutableStateOf(false)

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
        // A camera notification tap survives a recreation that happens
        // between the tap and the first frame of the preview screen.
        pendingRouteCamera = savedInstanceState?.getBoolean(KEY_PENDING_ROUTE_CAMERA)
            ?: routeCameraFrom(intent)

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
                val view = LocalView.current
                SideEffect {
                    val activity = view.context as? Activity ?: return@SideEffect
                    val controller = WindowCompat.getInsetsController(activity.window, view)
                    controller.isAppearanceLightStatusBars = !isDark
                    controller.isAppearanceLightNavigationBars = !isDark
                }
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background,
                ) {
                    val status by TunnelState.flow.collectAsStateWithLifecycle()
                    val keysState by KeyRepository.observeKeys(applicationContext)
                        .collectAsStateWithLifecycle(initialValue = null)
                    val keys = keysState.orEmpty()
                    val activeId by KeyRepository.observeActiveId(applicationContext)
                        .collectAsStateWithLifecycle(null)
                    val scope = rememberCoroutineScope()
                    val configuration = LocalConfiguration.current
                    val windowSize = calculateWindowSizeClass(this)
                    val widthClass = windowSize.widthSizeClass
                    val useRail = widthClass != WindowWidthSizeClass.Compact
                    val homeCompact =
                        widthClass != WindowWidthSizeClass.Expanded ||
                            configuration.screenHeightDp < 560
                    var screen by rememberSaveable { mutableIntStateOf(Screens.HOME) }
                    var historyOrigin by rememberSaveable { mutableIntStateOf(Screens.HOME) }
                    var addServerOnOpen by remember { mutableStateOf(false) }
                    var browseSort by rememberSaveable { mutableStateOf(BrowseSort.NAME) }
                    var browseAscending by rememberSaveable { mutableStateOf(true) }
                    var browsePeer by remember { mutableStateOf<ClusterPeer?>(null) }
                    val displayedScreen =
                        if (screen == Screens.BROWSE && browsePeer == null) Screens.HOME else screen
                    val showNavigation = displayedScreen != Screens.BROWSE
                    val selectedTab = tabOf(displayedScreen)
                    val onSelectTab: (Int) -> Unit = { target ->
                        if (target == Screens.HISTORY) historyOrigin = Screens.HOME
                        screen = target
                    }

                    // The camera notifications deep-link here: a fresh
                    // launch carries the flag in, a live activity sets it in
                    // onNewIntent, and either way it routes once, to the
                    // preview screen.
                    LaunchedEffect(pendingRouteCamera) {
                        if (pendingRouteCamera) {
                            screen = Screens.CAMERA_PREVIEW
                            pendingRouteCamera = false
                        }
                    }

                    BackHandler(
                        enabled = displayedScreen != Screens.HOME && displayedScreen != Screens.BROWSE,
                    ) {
                        screen = when (displayedScreen) {
                            Screens.SETTINGS, Screens.SERVERS -> Screens.HOME
                            Screens.HISTORY -> historyOrigin
                            Screens.CAMERA_PREVIEW -> Screens.CAMERA
                            else -> Screens.SETTINGS
                        }
                    }

                    val content: @Composable () -> Unit = {
                    AnimatedContent(
                        targetState = displayedScreen,
                        transitionSpec = {
                            screenTransition(from = initialState, to = targetState)
                        },
                        label = "screen",
                        modifier = Modifier
                            .fillMaxSize()
                            .clipToBounds(),
                    ) { current ->
                        when (current) {
                        Screens.BROWSE -> {
                            val peer = browsePeer
                            if (peer != null) {
                                BrowseScreen(
                                    peer = peer,
                                    sort = browseSort,
                                    ascending = browseAscending,
                                    onSortChange = { key, asc ->
                                        browseSort = key
                                        browseAscending = asc
                                    },
                                    onBack = { screen = Screens.HOME },
                                )
                            }
                        }
                        Screens.TRANSFERS -> {
                            TransfersScreen(
                                onBack = { screen = Screens.SETTINGS },
                                onOpenHistory = {
                                    historyOrigin = Screens.TRANSFERS
                                    screen = Screens.HISTORY
                                },
                            )
                        }
                        Screens.CAMERA -> {
                            CameraScreen(
                                onBack = { screen = Screens.SETTINGS },
                                onOpenPreview = { screen = Screens.CAMERA_PREVIEW },
                            )
                        }
                        Screens.CAMERA_PREVIEW -> {
                            CameraPreviewScreen(onBack = { screen = Screens.CAMERA })
                        }
                        Screens.MEDIA -> {
                            MediaScreen(onBack = { screen = Screens.SETTINGS })
                        }
                        Screens.NOTIFICATIONS -> {
                            NotificationsScreen(onBack = { screen = Screens.SETTINGS })
                        }
                        Screens.CLIPBOARD_SYNC -> {
                            ClipboardSyncScreen(onBack = { screen = Screens.SETTINGS })
                        }
                        Screens.DNS -> {
                            DnsScreen(onBack = { screen = Screens.SETTINGS })
                        }
                        Screens.TRUSTED_DEVICES -> {
                            TrustedDevicesScreen(onBack = { screen = Screens.SETTINGS })
                        }
                        Screens.HISTORY -> {
                            HistoryScreen(onBack = { screen = historyOrigin })
                        }
                        Screens.SPLIT_TUNNEL -> {
                            SplitTunnelScreen(onBack = { screen = Screens.SETTINGS })
                        }
                        Screens.SERVERS -> {
                            ServersScreen(
                                keys = keys,
                                loading = keysState == null,
                                activeId = activeId,
                                onBack = { screen = Screens.HOME },
                                onAddKey = { key ->
                                    scope.launch {
                                        val added = KeyRepository.addKey(applicationContext, key)
                                        if (added) {
                                            KeyRepository.setActive(applicationContext, key.id)
                                        }
                                        Toast.makeText(
                                            applicationContext,
                                            getString(
                                                if (added) R.string.key_added else R.string.key_exists
                                            ),
                                            Toast.LENGTH_SHORT,
                                        ).show()
                                    }
                                },
                                onSelect = { key ->
                                    scope.launch { KeyRepository.setActive(applicationContext, key.id) }
                                },
                                onRename = { key, newName ->
                                    scope.launch { KeyRepository.renameKey(applicationContext, key.id, newName) }
                                },
                                onDelete = { key ->
                                    // Deleting the key that is carrying the
                                    // tunnel would leave the UI pointing at a
                                    // server the engine is no longer using.
                                    if (status.activeId == key.id &&
                                        status.phase != TunnelPhase.DISCONNECTED
                                    ) {
                                        TunnelService.stop(this@MainActivity)
                                    }
                                    scope.launch {
                                        KeyRepository.removeKey(applicationContext, key.id)
                                    }
                                },
                                connected = status.phase == TunnelPhase.CONNECTED,
                                startInAdd = addServerOnOpen,
                                onStartInAddConsumed = { addServerOnOpen = false },
                            )
                        }
                        Screens.SETTINGS -> {
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
                                onOpenCamera = { screen = Screens.CAMERA },
                            )
                        }
                        else -> {
                            HomeScreen(
                                compact = homeCompact,
                                status = status,
                                keys = keys,
                                keysLoaded = keysState != null,
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
                                        else -> TunnelService.stop(this@MainActivity)
                                    }
                                },
                                onAddServer = {
                                    addServerOnOpen = true
                                    screen = Screens.SERVERS
                                },
                                onOpenHistory = {
                                    historyOrigin = Screens.HOME
                                    screen = Screens.HISTORY
                                },
                                onOpenSettings = { screen = Screens.SETTINGS },
                                onBrowsePeer = { peer ->
                                    browsePeer = peer
                                    screen = Screens.BROWSE
                                },
                                onOpenCameraPreview = { screen = Screens.CAMERA_PREVIEW },
                            )
                        }
                        }
                    }
                    }

                    if (useRail) {
                        Row(Modifier.fillMaxSize()) {
                            AnimatedVisibility(
                                visible = showNavigation,
                                // The size change is instant on purpose: animating
                                // a layout dimension re-measures the whole screen
                                // on every frame, which is what a dropped frame
                                // during navigation actually is. The fade carries
                                // the arrival; the size does not participate.
                                enter = expandHorizontally(
                                    animationSpec = tween(0),
                                    expandFrom = Alignment.Start,
                                ) + fadeIn(tween(Motion.Base, easing = Motion.EmphasizedDecelerate)),
                                exit = shrinkHorizontally(
                                    animationSpec = tween(0),
                                    shrinkTowards = Alignment.Start,
                                ) + fadeOut(tween(Motion.Quick, easing = Motion.EmphasizedAccelerate)),
                            ) {
                                AppNavigation(
                                    selected = selectedTab,
                                    useRail = true,
                                    onSelect = onSelectTab,
                                )
                            }
                            // The rail owns its own vertical insets; the screen
                            // beside it still has to clear the gesture bar.
                            Box(
                                Modifier
                                    .weight(1f)
                                    .fillMaxHeight()
                                    .navigationBarsPadding()
                            ) { content() }
                        }
                    } else {
                        Column(Modifier.fillMaxSize()) {
                            Box(Modifier.weight(1f).fillMaxSize()) { content() }
                            AnimatedVisibility(
                                visible = showNavigation,
                                enter = expandVertically(
                                    animationSpec = tween(0),
                                    expandFrom = Alignment.Bottom,
                                ) + fadeIn(tween(Motion.Base, easing = Motion.EmphasizedDecelerate)),
                                exit = shrinkVertically(
                                    animationSpec = tween(0),
                                    shrinkTowards = Alignment.Bottom,
                                ) + fadeOut(tween(Motion.Quick, easing = Motion.EmphasizedAccelerate)),
                            ) {
                                AppNavigation(
                                    selected = selectedTab,
                                    useRail = false,
                                    onSelect = onSelectTab,
                                )
                            }
                        }
                    }

                    TransferOverlay()

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
        outState.putBoolean(KEY_PENDING_ROUTE_CAMERA, pendingRouteCamera)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleSsIntent(intent)
        handlePairingIntent(intent)
        val uris = sharedUrisFrom(intent)
        val text = sharedTextFrom(intent)
        // A launcher tap or a key import must not dismiss a share that is
        // already waiting for a destination.
        if (uris.isNotEmpty() || text != null) {
            pendingSharedUris = uris
            persistSharedUris(uris)
            pendingSharedText = text
            pendingOpenOnArrival = isOpenIntent(intent)
        }
        // The activity is alive; the camera route still has to change the
        // composed screen.
        if (routeCameraFrom(intent)) pendingRouteCamera = true
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
            RelayManager.onSettingsChanged(this@MainActivity)
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

    /**
     * True when the intent carries the camera notifications' route extra:
     * both the prompt and the streaming notification open the preview
     * screen, where the accept card or the live controls are.
     */
    private fun routeCameraFrom(intent: Intent?): Boolean =
        intent?.getBooleanExtra(CameraRequestNotifications.EXTRA_ROUTE_CAMERA, false) == true

    private companion object {
        const val NOTIFICATION_REQUEST_CODE = 99
        const val KEY_PENDING_KEY_ID = "pending_key_id"
        const val KEY_PENDING_SHARED_URIS = "pending_shared_uris"
        const val KEY_PENDING_SHARED_TEXT = "pending_shared_text"
        const val KEY_PENDING_OPEN_ON_ARRIVAL = "pending_open_on_arrival"
        const val KEY_PENDING_ROUTE_CAMERA = "pending_route_camera"
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

private data class NavItem(val screen: Int, val label: String, val icon: Int)

private val navItems = listOf(
    NavItem(Screens.HOME, "Home", R.drawable.ic_home),
    NavItem(Screens.SERVERS, "Servers", R.drawable.ic_server),
    NavItem(Screens.HISTORY, "History", R.drawable.ic_activity),
    NavItem(Screens.SETTINGS, "Settings", R.drawable.ic_settings),
)

private fun tabOf(screen: Int): Int = when (screen) {
    Screens.HOME, Screens.BROWSE -> Screens.HOME
    Screens.SERVERS -> Screens.SERVERS
    Screens.HISTORY -> Screens.HISTORY
    else -> Screens.SETTINGS
}

/** Left-to-right position of each top-level tab, used for transition direction. */
private fun tabOrder(screen: Int): Int = when (tabOf(screen)) {
    Screens.HOME -> 0
    Screens.SERVERS -> 1
    Screens.HISTORY -> 2
    else -> 3
}

/**
 * Hierarchy depth, used only to pick a transition direction. Siblings at the
 * same depth crossfade in place; parents and children slide, so the back
 * gesture retraces the path the user took forward.
 */
private fun screenDepth(screen: Int): Int = when (screen) {
    Screens.HOME -> 0
    Screens.SERVERS, Screens.HISTORY, Screens.SETTINGS -> 1
    else -> 2
}

/**
 * Every transition here is built on one rule: a screen is only half-visible for
 * a very short time. A long crossfade leaves two screens legible at once, which
 * reads as a ghost rather than as a movement, and it also keeps two full-screen
 * surfaces on the GPU for the whole animation. So the travel is long and the
 * fade is short: by the time the incoming screen has slid a fifth of the way it
 * is already opaque, and the outgoing one is gone before it can show through.
 */
private fun AnimatedContentTransitionScope<Int>.screenTransition(
    from: Int,
    to: Int,
): ContentTransform {
    // 110 ms is short enough that the incoming surface is opaque before the eye
    // can resolve the outgoing screen behind it, but not so short that the
    // arrival looks like a cut.
    val fadeInFast = tween<Float>(110, easing = Motion.EmphasizedDecelerate)
    val fadeOutFast = tween<Float>(100, easing = Motion.EmphasizedAccelerate)

    // A top-level tab switch travels along the tab order. A quarter of the width
    // is enough for the direction to be unambiguous even at half animator scale.
    val fromTab = tabOrder(from)
    val toTab = tabOrder(to)
    if (fromTab != toTab) {
        val direction = if (toTab > fromTab) 1 else -1
        val enter = fadeIn(fadeInFast) +
            slideInHorizontally(
                animationSpec = tween(Motion.Screen, easing = Motion.EmphasizedDecelerate),
                initialOffsetX = { width -> direction * (width / 4) },
            )
        val exit = fadeOut(fadeOutFast) +
            slideOutHorizontally(
                animationSpec = tween(Motion.Exit, easing = Motion.EmphasizedAccelerate),
                targetOffsetX = { width -> -direction * (width / 12) },
            )
        return enter togetherWith exit
    }

    val depthChange = screenDepth(to) - screenDepth(from)
    if (depthChange == 0) {
        // Siblings: fade-through, not crossfade. The outgoing surface is gone
        // before the incoming one starts, so the two are never both readable.
        val enter = fadeIn(
            tween(Motion.Base, delayMillis = 110, easing = Motion.EmphasizedDecelerate)
        ) + scaleIn(
            initialScale = 0.98f,
            animationSpec = tween(Motion.Medium, delayMillis = 110, easing = Motion.EmphasizedDecelerate),
        )
        val exit = fadeOut(tween(110, easing = Motion.EmphasizedAccelerate)) +
            scaleOut(
                targetScale = 1.01f,
                animationSpec = tween(110, easing = Motion.EmphasizedAccelerate),
            )
        return enter togetherWith exit
    }

    // Hierarchy: the child slides over its parent while the parent sinks back.
    val direction = if (depthChange > 0) 1 else -1
    val enter = fadeIn(fadeInFast) +
        slideInHorizontally(
            animationSpec = tween(Motion.Screen, easing = Motion.EmphasizedDecelerate),
            initialOffsetX = { width -> direction * (width / 5) },
        ) + scaleIn(
        initialScale = 0.985f,
        animationSpec = tween(Motion.Screen, easing = Motion.EmphasizedDecelerate),
    )
    val exit = fadeOut(fadeOutFast) +
        slideOutHorizontally(
            animationSpec = tween(Motion.Exit, easing = Motion.EmphasizedAccelerate),
            targetOffsetX = { width -> -direction * (width / 14) },
        ) + scaleOut(
        targetScale = 1.01f,
        animationSpec = tween(Motion.Exit, easing = Motion.EmphasizedAccelerate),
    )
    return enter togetherWith exit
}

@Composable
private fun AppNavigation(
    selected: Int,
    useRail: Boolean,
    onSelect: (Int) -> Unit,
) {
    // The bar is a plane, not a stripe: same container family as a card, with
    // one hairline where the screen meets it, and the accent only inside the
    // selected indicator.
    val navColors = NavigationBarItemDefaults.colors(
        selectedIconColor = MaterialTheme.colorScheme.onPrimaryContainer,
        selectedTextColor = MaterialTheme.colorScheme.onSurface,
        indicatorColor = MaterialTheme.colorScheme.primaryContainer,
        unselectedIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
        unselectedTextColor = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    // Read outside the draw phase: a DrawScope lambda is not composable.
    val hairline = MaterialTheme.colorScheme.outlineVariant
    val railColors = NavigationRailItemDefaults.colors(
        selectedIconColor = MaterialTheme.colorScheme.onPrimaryContainer,
        selectedTextColor = MaterialTheme.colorScheme.onSurface,
        indicatorColor = MaterialTheme.colorScheme.primaryContainer,
        unselectedIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
        unselectedTextColor = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    if (useRail) {
        NavigationRail(
            modifier = Modifier
                .fillMaxHeight()
                .width(96.dp),
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        ) {
            Spacer(Modifier.height(12.dp))
            navItems.forEach { item ->
                NavigationRailItem(
                    selected = selected == item.screen,
                    onClick = { onSelect(item.screen) },
                    icon = {
                        Icon(
                            painter = painterResource(item.icon),
                            contentDescription = item.label,
                        )
                    },
                    label = { Text(item.label) },
                    alwaysShowLabel = true,
                    colors = railColors,
                )
            }
        }
    } else {
        NavigationBar(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
            modifier = Modifier.drawBehind {
                drawLine(
                    color = hairline,
                    start = Offset(0f, 0.5f),
                    end = Offset(size.width, 0.5f),
                    strokeWidth = 1.dp.toPx(),
                )
            },
        ) {
            navItems.forEach { item ->
                NavigationBarItem(
                    selected = selected == item.screen,
                    onClick = { onSelect(item.screen) },
                    icon = {
                        Icon(
                            painter = painterResource(item.icon),
                            contentDescription = item.label,
                        )
                    },
                    label = { Text(item.label) },
                    colors = navColors,
                )
            }
        }
    }
}
