package daytrader.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
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
    marketDataGateway: BrokerGateway? = null,
    modifier: Modifier = Modifier
) {
    val executionState by brokerGateway.connectionState.collectAsState()
    val marketDataState = marketDataGateway?.connectionState?.collectAsState()?.value

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
            if ((brokerKind == BrokerKind.EMULATOR_LIVE_IB_MARKET_DATA || brokerKind == BrokerKind.REPLAY) &&
                marketDataGateway != null
            ) {
                HybridConnectionStatus(
                    executionState = executionState,
                    marketDataState = marketDataState ?: GatewayConnectionState.Disconnected
                )
            } else {
                Text(
                    text = brokerStatusLabel(executionState, brokerKind),
                    style = MaterialTheme.typography.bodyMedium,
                    color = brokerStatusColor(executionState)
                )
            }
        }
        HybridReconnectButtons(
            brokerKind = brokerKind,
            executionState = executionState,
            marketDataState = marketDataState,
            onReconnectExecution = brokerGateway::reconnect,
            onReconnectMarketData = marketDataGateway?.let { gateway -> { gateway.reconnect() } }
        )
    }
}

@Composable
private fun HybridConnectionStatus(
    executionState: GatewayConnectionState,
    marketDataState: GatewayConnectionState
) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(
            text = "Paper execution · ${connectionShortLabel(executionState)}",
            style = MaterialTheme.typography.bodyMedium,
            color = brokerStatusColor(executionState)
        )
        Text(
            text = "IB market data · ${connectionShortLabel(marketDataState)}",
            style = MaterialTheme.typography.bodyMedium,
            color = brokerStatusColor(marketDataState)
        )
    }
}

@Composable
private fun HybridReconnectButtons(
    brokerKind: BrokerKind,
    executionState: GatewayConnectionState,
    marketDataState: GatewayConnectionState?,
    onReconnectExecution: () -> Unit,
    onReconnectMarketData: (() -> Unit)?
) {
    if (brokerKind != BrokerKind.EMULATOR_LIVE_IB_MARKET_DATA || onReconnectMarketData == null) {
        BrokerReconnectButton(state = executionState, onReconnect = onReconnectExecution)
        return
    }
    val marketState = marketDataState ?: GatewayConnectionState.Disconnected
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        if (needsReconnect(executionState)) {
            BrokerReconnectButton(
                state = executionState,
                onReconnect = onReconnectExecution,
                labelPrefix = "Paper"
            )
        }
        if (needsReconnect(marketState)) {
            BrokerReconnectButton(
                state = marketState,
                onReconnect = onReconnectMarketData,
                labelPrefix = "IB Data"
            )
        }
    }
}

@Composable
private fun BrokerReconnectButton(
    state: GatewayConnectionState,
    onReconnect: () -> Unit,
    labelPrefix: String? = null
) {
    if (!needsReconnect(state)) {
        return
    }
    val baseLabel = if (state is GatewayConnectionState.Disconnected) "Connect" else "Reconnect"
    val label = labelPrefix?.let { "$baseLabel $it" } ?: baseLabel
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

private fun needsReconnect(state: GatewayConnectionState): Boolean =
    state is GatewayConnectionState.Disconnected || state is GatewayConnectionState.Error

private fun connectionShortLabel(state: GatewayConnectionState): String = when (state) {
    GatewayConnectionState.Disconnected -> "Not connected"
    GatewayConnectionState.Connecting -> "Connecting…"
    GatewayConnectionState.Connected -> "Connected"
    is GatewayConnectionState.Error -> "Error"
}

private fun brokerStatusLabel(state: GatewayConnectionState, brokerKind: BrokerKind): String {
    val brokerName = when (brokerKind) {
        BrokerKind.INTERACTIVE_BROKERS -> "Interactive Brokers"
        BrokerKind.EMULATOR -> "Broker Emulator"
        BrokerKind.EMULATOR_LIVE_IB_MARKET_DATA -> "Paper Trading (Live IB Data)"
        BrokerKind.REPLAY -> "Session Replay (Captured Data)"
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
