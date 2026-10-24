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
import androidx.glance.action.actionParametersOf
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.action.ActionCallback
import androidx.glance.appwidget.action.actionRunCallback
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.provideContent
import androidx.glance.background
import androidx.glance.layout.Alignment
import androidx.glance.layout.Box
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
        val accent = if (connected) Color(0xFF5BD9A3) else Color(0xFF98A2B0)

        provideContent {
            GlanceTheme {
                Column(
                    modifier = GlanceModifier
                        .fillMaxSize()
                        .background(ColorProvider(Color(0xE60F1219.toInt())))
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
                            ?: "No server selected",
                        style = TextStyle(
                            color = ColorProvider(Color(0xFFE7ECF3)),
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Bold,
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
                    Box(
                        modifier = GlanceModifier
                            .fillMaxWidth()
                            .cornerRadius(14.dp)
                            .background(ColorProvider(accent))
                            .clickable(actionRunCallback<ToggleAction>(actionParametersOf())),
                    ) {
                        Text(
                            if (connected) "Disconnect" else "Connect",
                            modifier = GlanceModifier.padding(horizontal = 16.dp, vertical = 10.dp),
                            style = TextStyle(
                                color = ColorProvider(Color(0xFF0B111B)),
                                fontSize = 14.sp,
                                fontWeight = FontWeight.Bold,
                            ),
                        )
                    }
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
