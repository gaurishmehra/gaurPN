package dev.gaurish.outlinelite

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.VpnService
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.material3.windowsizeclass.ExperimentalMaterial3WindowSizeClassApi
import androidx.compose.material3.windowsizeclass.WindowWidthSizeClass
import androidx.compose.material3.windowsizeclass.calculateWindowSizeClass
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.gaurish.outlinelite.data.AccessKey
import dev.gaurish.outlinelite.data.KeyRepository
import dev.gaurish.outlinelite.data.SsKeyParser
import dev.gaurish.outlinelite.tunnel.TunnelPhase
import dev.gaurish.outlinelite.tunnel.TunnelService
import dev.gaurish.outlinelite.tunnel.TunnelState
import dev.gaurish.outlinelite.ui.HomeScreen
import dev.gaurish.outlinelite.ui.OutlineTheme
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3WindowSizeClassApi::class)
class MainActivity : ComponentActivity() {

    private var pendingKey: AccessKey? = null

    private val consentLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val key = pendingKey
        pendingKey = null
        if (result.resultCode == RESULT_OK && key != null) {
            TunnelService.start(this, key)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
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
            OutlineTheme {
                Surface(modifier = androidx.compose.ui.Modifier.fillMaxSize()) {
                    val status by TunnelState.flow.collectAsStateWithLifecycle()
                    var keys by remember { mutableStateOf<List<AccessKey>>(emptyList()) }
                    var activeId by remember { mutableStateOf<String?>(null) }
                    val scope = rememberCoroutineScope()
                    val widthClass = calculateWindowSizeClass(this).widthSizeClass
                    LaunchedEffect(Unit) {
                        launch {
                            KeyRepository.observeKeys(applicationContext).collect { keys = it }
                        }
                        launch {
                            KeyRepository.observeActiveId(applicationContext).collect { activeId = it }
                        }
                    }

                    HomeScreen(
                        compact = widthClass == WindowWidthSizeClass.Compact,
                        status = status,
                        keys = keys,
                        activeId = activeId,
                        onConnectToggle = {
                            when (status.phase) {
                                TunnelPhase.DISCONNECTED -> {
                                    val key = keys.firstOrNull { it.id == activeId } ?: keys.firstOrNull()
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
                        onDelete = { key ->
                            scope.launch { KeyRepository.removeKey(applicationContext, key.id) }
                        },
                    )
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleSsIntent(intent)
    }

    private fun prepareAndStart(key: AccessKey) {
        pendingKey = key
        CoroutineScope(Dispatchers.Main.immediate).launch {
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
        CoroutineScope(Dispatchers.Main.immediate).launch {
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
    }
}
