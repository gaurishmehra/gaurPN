package dev.gaurish.outlinelite.transfer

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.lifecycleScope
import dev.gaurish.outlinelite.ui.OutlineTheme
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Consent surface for a peer's file request. Opened from the request
 * notification; the user picks files with the system document picker and
 * they are sent back to the requester with the request's grant echoed in the
 * offer.
 *
 * Not exported and singleTask: only the app's own notification can start it,
 * and a second launch reuses the instance instead of stacking pickers.
 */
class RequestPickerActivity : ComponentActivity() {

    private data class PendingRequest(
        val requestId: String,
        val requesterId: String,
        val requesterName: String,
        val grant: String,
        val expiresTs: Long,
    )

    private var request by mutableStateOf<PendingRequest?>(null)
    private var sending by mutableStateOf(false)

    /** True once the request was answered (files handed off or a decline sent). */
    private var handled = false

    /** True only in the instance that acquired the process-wide guard. */
    private var ownsGuard = false

    private val pickFiles = registerForActivityResult(
        ActivityResultContracts.OpenMultipleDocuments()
    ) { uris: List<Uri> ->
        val current = request
        if (current == null) {
            finish()
            return@registerForActivityResult
        }
        if (uris.isEmpty()) {
            // The user backed out of the document picker; stay on the
            // consent screen unless the request has expired meanwhile.
            if (RequestCodec.isExpired(current.expiresTs)) {
                decline(current, DeclineReason.EXPIRED)
            }
            return@registerForActivityResult
        }
        if (RequestCodec.isExpired(current.expiresTs)) {
            decline(current, DeclineReason.EXPIRED)
            return@registerForActivityResult
        }
        sending = true
        lifecycleScope.launch {
            // The notification extras are a snapshot: the request may have
            // been superseded or expired while the system picker was open,
            // and the trust list may have changed. Re-check both against the
            // live lease before handing anything to the sender.
            val stillValid = RelayManager.isRequestActiveAndTrusted(
                this@RequestPickerActivity, current.requestId,
            )
            if (!stillValid) {
                sending = false
                decline(
                    current,
                    if (RequestCodec.isExpired(current.expiresTs)) {
                        DeclineReason.EXPIRED
                    } else {
                        DeclineReason.DECLINED
                    },
                )
                return@launch
            }
            if (isFinishing || isDestroyed) return@launch
            persistReadAccess(uris)
            handled = true
            RelayManager.sendFilesFromRequest(
                this@RequestPickerActivity, current.requesterId, current.requestId, current.grant, uris,
            )
            // Keep the "Sending..." state on screen briefly so the hand-off is
            // visible before the activity closes.
            delay(SENDING_VISIBLE_MS)
            finish()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (!running.compareAndSet(false, true)) {
            finish()
            return
        }
        ownsGuard = true
        val parsed = requestFrom(intent)
        if (parsed == null) {
            finish()
            return
        }
        if (RequestCodec.isExpired(parsed.expiresTs)) {
            decline(parsed, DeclineReason.EXPIRED)
            return
        }
        request = parsed
        enableEdgeToEdge()
        setContent {
            OutlineTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background,
                ) {
                    val active = request
                    if (active != null) {
                        RequestPickerContent(
                            requesterName = active.requesterName,
                            sending = sending,
                            onChooseFiles = { pickFiles.launch(arrayOf("*/*")) },
                            onDecline = {
                                val reason = if (RequestCodec.isExpired(active.expiresTs)) {
                                    DeclineReason.EXPIRED
                                } else {
                                    DeclineReason.DECLINED
                                }
                                decline(active, reason)
                            },
                        )
                    }
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        // A second request must not swap the UI while the first send is in
        // flight; its notification stays pending and can be tapped later.
        if (sending) return
        setIntent(intent)
        val parsed = requestFrom(intent) ?: return
        if (RequestCodec.isExpired(parsed.expiresTs)) {
            decline(parsed, DeclineReason.EXPIRED)
            return
        }
        request = parsed
        handled = false
    }

    override fun onDestroy() {
        if (ownsGuard) {
            running.set(false)
            // Backing out of the consent screen is a decline, not a
            // five-minute lockout. A configuration change (isFinishing ==
            // false) keeps the request alive.
            if (!handled && isFinishing) {
                request?.let { decline(it, DeclineReason.DECLINED) }
            }
        }
        super.onDestroy()
    }

    private fun decline(current: PendingRequest, reason: DeclineReason) {
        handled = true
        RelayManager.declineRequest(this, current.requesterId, current.requestId, reason)
        finish()
    }

    /**
     * Keeps read access to the picked files for the duration of the send,
     * matching MainActivity.persistSharedUris. Providers without persistable
     * grants throw and are ignored.
     */
    private fun persistReadAccess(uris: List<Uri>) {
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

    private fun requestFrom(intent: Intent?): PendingRequest? {
        if (intent == null) return null
        val requestId = intent.getStringExtra(EXTRA_REQUEST_ID) ?: return null
        val requesterId = intent.getStringExtra(EXTRA_REQUESTER_ID) ?: return null
        val grant = intent.getStringExtra(EXTRA_GRANT) ?: return null
        val kind = RequestKind.fromWire(intent.getStringExtra(EXTRA_KIND) ?: "") ?: return null
        if (!RequestCodec.isRequestId(requestId) || !RequestCodec.isDeviceId(requesterId) ||
            !RequestCodec.isGrant(grant) || kind != RequestKind.FILES
        ) {
            return null
        }
        // Same clamp as parseRequest: a far-future expiry is malformed, not a
        // request to park on. An already-expired one is handled by the caller
        // so it can still decline instead of silently finishing.
        val expiresTs = intent.getLongExtra(EXTRA_EXPIRES_TS, 0L)
        if (expiresTs <= 0L || RequestCodec.isBeyondRequestWindow(expiresTs)) return null
        val name = singleLineDisplayName(intent.getStringExtra(EXTRA_REQUESTER_NAME) ?: "")
            .takeIf { it.isNotEmpty() } ?: "A cluster device"
        return PendingRequest(
            requestId = requestId,
            requesterId = requesterId,
            requesterName = name,
            grant = grant,
            expiresTs = expiresTs,
        )
    }

    companion object {
        internal const val EXTRA_REQUEST_ID = "request_id"
        internal const val EXTRA_REQUESTER_ID = "requester_id"
        internal const val EXTRA_REQUESTER_NAME = "requester_name"
        internal const val EXTRA_GRANT = "grant"
        internal const val EXTRA_EXPIRES_TS = "expires_ts"
        internal const val EXTRA_KIND = "kind"

        private const val SENDING_VISIBLE_MS = 600L

        /** Guards against two picker instances stacking. */
        private val running = AtomicBoolean(false)
    }
}

@Composable
private fun RequestPickerContent(
    requesterName: String,
    sending: Boolean,
    onChooseFiles: () -> Unit,
    onDecline: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 28.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        if (sending) {
            CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.height(18.dp))
            Text(
                "Sending...",
                fontSize = 16.sp,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onBackground,
            )
        } else {
            Text(
                "$requesterName requests a file",
                fontSize = 20.sp,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center,
                color = MaterialTheme.colorScheme.onBackground,
            )
            Spacer(Modifier.height(10.dp))
            Text(
                "Choose what to send back. Files travel inside the encrypted " +
                    "tunnel and are saved to the requester's Downloads folder.",
                fontSize = 13.sp,
                lineHeight = 19.sp,
                textAlign = TextAlign.Center,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(28.dp))
            Button(
                onClick = onChooseFiles,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("Choose files", fontSize = 14.sp)
            }
            Spacer(Modifier.height(10.dp))
            OutlinedButton(
                onClick = onDecline,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("Decline", fontSize = 14.sp)
            }
        }
    }
}
