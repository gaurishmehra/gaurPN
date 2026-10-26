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
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.material3.windowsizeclass.ExperimentalMaterial3WindowSizeClassApi
import androidx.compose.material3.windowsizeclass.WindowWidthSizeClass
import androidx.compose.material3.windowsizeclass.calculateWindowSizeClass
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import dev.gaurish.outlinelite.data.AccessKey
import dev.gaurish.outlinelite.data.KeyRepository
import dev.gaurish.outlinelite.data.SsKeyParser
import dev.gaurish.outlinelite.data.ThemeMode
import dev.gaurish.outlinelite.tunnel.TunnelPhase
import dev.gaurish.outlinelite.tunnel.TunnelService
import dev.gaurish.outlinelite.tunnel.TunnelState
import dev.gaurish.outlinelite.ui.HomeScreen
import dev.gaurish.outlinelite.ui.OutlineTheme
import dev.gaurish.outlinelite.ui.SettingsScreen
import dev.gaurish.outlinelite.ui.SplitTunnelScreen
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3WindowSizeClassApi::class)
class MainActivity : ComponentActivity() {

    private var pendingKeyId: String? = null

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
                    var screen by rememberSaveable { mutableIntStateOf(0) } // 0 = home, 1 = settings, 2 = split tunneling

                    when (screen) {
                        2 -> {
                            BackHandler { screen = 1 }
                            SplitTunnelScreen(onBack = { screen = 1 })
                        }
                        1 -> {
                            BackHandler { screen = 0 }
                            SettingsScreen(
                                onBack = { screen = 0 },
                                onOpenSplitTunnel = { screen = 2 }
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
                                onOpenSettings = { screen = 1 },
                            )
                        }
                    }
                }
            }
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString(KEY_PENDING_KEY_ID, pendingKeyId)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleSsIntent(intent)
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

    private companion object {
        const val NOTIFICATION_REQUEST_CODE = 99
        const val KEY_PENDING_KEY_ID = "pending_key_id"
    }
}
