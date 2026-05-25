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
import daytrader.gateway.BrokerGateway
import daytrader.gateway.BrokerKind
import daytrader.gateway.GatewayConnectionState
import daytrader.ui.theme.GainGreen
import daytrader.ui.theme.LossRed
import daytrader.ui.theme.SurfaceDark
import daytrader.ui.theme.TextSecondary

@Composable
fun ConnectionStatusBar(
    brokerGateway: BrokerGateway,
    brokerKind: BrokerKind,
    globalAutoStartEnabled: Boolean,
    onGlobalAutoStartChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier
) {
    val state by brokerGateway.connectionState.collectAsState()

    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            BrokerModeBadge(brokerId = brokerGateway.brokerId, brokerKind = brokerKind)
            Text(
                text = brokerStatusLabel(state, brokerKind),
                style = MaterialTheme.typography.bodyMedium,
                color = brokerStatusColor(state)
            )
        }
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
                onReconnect = brokerGateway::reconnect
            )
        }
    }
}

@Composable
private fun BrokerReconnectButton(
    state: GatewayConnectionState,
    onReconnect: () -> Unit
) {
    if (state !is GatewayConnectionState.Disconnected && state !is GatewayConnectionState.Error) {
        return
    }
    val label = if (state is GatewayConnectionState.Disconnected) "Connect" else "Reconnect"
    Button(
        onClick = onReconnect,
        enabled = state !is GatewayConnectionState.Connecting,
        colors = ButtonDefaults.buttonColors(
            containerColor = SurfaceDark,
            contentColor = Color.White
        )
    ) {
        Text(label)
    }
}

private fun brokerStatusLabel(state: GatewayConnectionState, brokerKind: BrokerKind): String {
    val brokerName = when (brokerKind) {
        BrokerKind.INTERACTIVE_BROKERS -> "Interactive Brokers"
        BrokerKind.EMULATOR -> "Broker Emulator"
        BrokerKind.EMULATOR_LIVE_IB_MARKET_DATA -> "Paper Trading (Live IB Data)"
    }
    return when (state) {
        GatewayConnectionState.Disconnected -> "Not Connected to $brokerName"
        GatewayConnectionState.Connecting -> "Connecting to $brokerName…"
        GatewayConnectionState.Connected -> "Connected to $brokerName"
        is GatewayConnectionState.Error -> "Not Connected to $brokerName"
    }
}

private fun brokerStatusColor(state: GatewayConnectionState): Color = when (state) {
    GatewayConnectionState.Disconnected -> TextSecondary
    GatewayConnectionState.Connecting -> Color(0xFFFFB300)
    GatewayConnectionState.Connected -> GainGreen
    is GatewayConnectionState.Error -> LossRed
}
