package dev.gaurish.outlinelite.transfer

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * Receives the Accept/Decline taps from the camera prompt and the Stop tap
 * from the streaming notification. The notification tap is the user
 * interaction that exempts the phone from the Android 14+ restriction on
 * starting a camera foreground service from the background, so Accept is
 * forwarded immediately; Decline answers with the existing sealed decline.
 * Stop is routed to the running service, which releases the request slot,
 * stops the camera, and removes its own notification.
 */
class CameraActionReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val appContext = context.applicationContext
        when (intent.action) {
            CameraRequestNotifications.ACTION_STOP -> {
                CameraStreamService.stop(appContext)
            }
            CameraRequestNotifications.ACTION_ACCEPT, CameraRequestNotifications.ACTION_DECLINE -> {
                val requestId = intent.getStringExtra(CameraRequestNotifications.EXTRA_REQUEST_ID)
                if (requestId.isNullOrBlank()) return
                val approved = intent.action == CameraRequestNotifications.ACTION_ACCEPT
                RelayManager.resolveCameraRequest(appContext, requestId, approved)
            }
            else -> Unit
        }
    }
}
