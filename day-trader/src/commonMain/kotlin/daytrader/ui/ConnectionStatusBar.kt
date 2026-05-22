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
    modifier: Modifier = Modifier
) {
    val state by ibGateway.state.collectAsState()

    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = statusLabel(state),
            style = MaterialTheme.typography.bodyMedium,
            color = statusColor(state)
        )

        if (state is IbConnectionState.Error || state is IbConnectionState.Disconnected) {
            Button(
                onClick = { ibGateway.reconnect() },
                enabled = state !is IbConnectionState.Connecting,
                colors = ButtonDefaults.buttonColors(
                    containerColor = SurfaceDark,
                    contentColor = Color.White
                )
            ) {
                Text(if (state is IbConnectionState.Disconnected) "Connect" else "Reconnect")
            }
        }
    }
}

private fun statusLabel(state: IbConnectionState): String = when (state) {
    IbConnectionState.Disconnected -> "IB Gateway: Disconnected"
    IbConnectionState.Connecting -> "IB Gateway: Connecting…"
    is IbConnectionState.Connected -> "IB Gateway: Connected (next order id ${state.nextOrderId})"
    is IbConnectionState.Error -> "IB Gateway: ${state.message}"
}

private fun statusColor(state: IbConnectionState): Color = when (state) {
    IbConnectionState.Disconnected -> TextSecondary
    IbConnectionState.Connecting -> Color(0xFFFFB300)
    is IbConnectionState.Connected -> GainGreen
    is IbConnectionState.Error -> LossRed
}
