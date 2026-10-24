package dev.gaurish.outlinelite.widget

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.GlanceTheme
import androidx.glance.action.ActionParameters
import androidx.glance.action.actionParameters
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.action.ActionCallback
import androidx.glance.appwidget.action.actionRunCallback
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.provideContent
import androidx.glance.background
import androidx.glance.layout.Alignment
import androidx.glance.layout.Column
import androidx.glance.layout.Row
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.padding
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import androidx.glance.unit.ColorProvider
import dev.gaurish.outlinelite.R
import dev.gaurish.outlinelite.tunnel.TunnelPhase
import dev.gaurish.outlinelite.tunnel.TunnelService
import dev.gaurish.outlinelite.tunnel.TunnelState

/** Home-screen widget: connection status + one-tap toggle. */
class OutlineWidget : GlanceAppWidget() {

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val status = TunnelState.status
        val connected = status.phase == TunnelPhase.CONNECTED
        val accent = if (connected) Color(0xFF39D98A) else Color(0xFFF5A524)

        provideContent {
            GlanceTheme {
                Column(
                    modifier = GlanceModifier
                        .fillMaxSize()
                        .background(ColorProvider(Color(0xE6101825.toInt())))
                        .cornerRadius(20.dp)
                        .padding(12.dp)
                ) {
                    Text(
                        context.getString(R.string.app_name),
                        style = TextStyle(
                            color = ColorProvider(Color(0xFF9AA8BC)),
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Medium,
                        ),
                    )
                    androidx.glance.layout.Spacer(modifier = GlanceModifier.padding(2.dp))
                    Text(
                        status.activeName
                            ?: context.getString(R.string.status_disconnected).replace("Not ", ""),
                        style = TextStyle(
                            color = ColorProvider(Color(0xFFE7ECF3)),
                            fontSize = 14.sp,
                            fontWeight = FontWeight.SemiBold,
                        ),
                    )
                    Text(
                        when (status.phase) {
                            TunnelPhase.CONNECTED -> "Connected"
                            TunnelPhase.CONNECTING -> "Connecting…"
                            TunnelPhase.STOPPING -> "Stopping…"
                            TunnelPhase.DISCONNECTED -> "Disconnected"
                        },
                        style = TextStyle(
                            color = ColorProvider(accent),
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Medium,
                        ),
                    )
                    androidx.glance.layout.Spacer(modifier = GlanceModifier.defaultWeight())
                    Button(
                        text = if (connected) "Disconnect" else "Connect",
                        textColor = ColorProvider(Color(0xFF221602)),
                        backgroundColor = ColorProvider(accent),
                        modifier = GlanceModifier.fillMaxWidth(),
                        action = actionRunCallback<ToggleAction>(actionParametersOf()),
                    )
                }
            }
        }
    }
}

class ToggleAction : ActionCallback {
    override suspend fun onAction(
        context: Context,
        glanceId: GlanceId,
        parameters: ActionParameters,
    ) {
        TunnelService.toggle(context)
        TunnelService.notifyExternal(context)
    }
}

class WidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget get() = OutlineWidget()
}
