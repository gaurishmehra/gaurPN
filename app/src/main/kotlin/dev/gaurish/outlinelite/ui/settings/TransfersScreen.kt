package dev.gaurish.outlinelite.ui.settings

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.gaurish.outlinelite.data.TransferSettingsRepository
import kotlinx.coroutines.launch

/**
 * File-transfer behavior: auto-open on arrival and the transfer history
 * entry point (received files, retries, resends).
 */
@Composable
fun TransfersScreen(
    onBack: () -> Unit,
    onOpenHistory: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    val autoOpenReceived by TransferSettingsRepository.observeAutoOpen(context)
        .collectAsStateWithLifecycle(initialValue = true)

    SettingsScaffold(title = "Transfers", onBack = onBack) {
        SettingsRow(
            title = "Open received files automatically",
            summary = "Launch the viewer as soon as a file shared with \"Open in...\" finishes arriving",
        ) {
            GaurSwitch(autoOpenReceived) { enabled ->
                scope.launch { TransferSettingsRepository.setAutoOpen(context, enabled) }
            }
        }

        SettingsRow(
            title = "Transfer history",
            summary = "Reopen received files, retry failed sends, and resend links",
            onClick = onOpenHistory,
            trailing = { Chevron() },
        )
        Spacer(Modifier.height(20.dp))
    }
}
