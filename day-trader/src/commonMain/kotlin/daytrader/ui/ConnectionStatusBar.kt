package daytrader.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import daytrader.broker.IbConnectionState
import daytrader.broker.IbGatewayConnection
import daytrader.ui.theme.GainGreen
import daytrader.ui.theme.LossRed
import daytrader.ui.theme.SurfaceDark
import daytrader.ui.theme.TextSecondary

@Composable
fun ConnectionStatusBar(
    ibGateway: IbGatewayConnection,
    globalAutoStartEnabled: Boolean,
    onGlobalAutoStartChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier
) {
    val state by ibGateway.state.collectAsState()

    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = brokerStatusLabel(state),
            style = MaterialTheme.typography.bodyMedium,
            color = brokerStatusColor(state)
        )
        Row(
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            GlobalAutoStartKillSwitch(
                enabled = globalAutoStartEnabled,
                onEnabledChange = onGlobalAutoStartChange
            )
            BrokerReconnectButton(
                state = state,
                onReconnect = ibGateway::reconnect
            )
        }
    }
}

@Composable
private fun BrokerReconnectButton(
    state: IbConnectionState,
    onReconnect: () -> Unit
) {
    if (state !is IbConnectionState.Disconnected && state !is IbConnectionState.Error) {
        return
    }
    val label = if (state is IbConnectionState.Disconnected) "Connect" else "Reconnect"
    Button(
        onClick = onReconnect,
        enabled = state !is IbConnectionState.Connecting,
        colors = ButtonDefaults.buttonColors(
            containerColor = SurfaceDark,
            contentColor = Color.White
        )
    ) {
        Text(label)
    }
}

private fun brokerStatusLabel(state: IbConnectionState): String = when (state) {
    IbConnectionState.Disconnected -> "Not Connected to Broker"
    IbConnectionState.Connecting -> "Connecting to Broker…"
    is IbConnectionState.Connected -> "Connected to Broker"
    is IbConnectionState.Error -> "Not Connected to Broker"
}

private fun brokerStatusColor(state: IbConnectionState): Color = when (state) {
    IbConnectionState.Disconnected -> TextSecondary
    IbConnectionState.Connecting -> Color(0xFFFFB300)
    is IbConnectionState.Connected -> GainGreen
    is IbConnectionState.Error -> LossRed
}
