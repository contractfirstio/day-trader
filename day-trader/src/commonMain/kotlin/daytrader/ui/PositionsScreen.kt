package daytrader.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import daytrader.gateway.GatewayConnectionState
import daytrader.presentation.positions.PositionsViewModel
import daytrader.ui.theme.DarkBackground
import daytrader.ui.theme.SurfaceDark
import daytrader.ui.theme.TextSecondary

@Composable
fun PositionsScreen(
    viewModel: PositionsViewModel,
    connectionState: GatewayConnectionState
) {
    val uiState by viewModel.uiState.collectAsState()

    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        Text(
            "Positions (${uiState.rows.size})",
            fontSize = 18.sp,
            fontWeight = FontWeight.SemiBold,
            color = Color.White
        )

        Spacer(modifier = Modifier.height(8.dp))

        Column(
            modifier = Modifier
                .fillMaxSize()
                .border(1.dp, SurfaceDark, RoundedCornerShape(8.dp))
                .background(SurfaceDark, RoundedCornerShape(8.dp))
        ) {
            BlotterHeader(
                activeSortColumn = uiState.sortColumn,
                sortDirection = uiState.sortDirection,
                onHeaderClick = viewModel::onHeaderClick
            )

            if (uiState.rows.isEmpty()) {
                Box(
                    modifier = Modifier.fillMaxSize().padding(24.dp),
                    contentAlignment = androidx.compose.ui.Alignment.Center
                ) {
                    Text(
                        text = when (connectionState) {
                            GatewayConnectionState.Connected ->
                                "No open positions reported by your broker."
                            GatewayConnectionState.Connecting ->
                                "Loading positions from broker…"
                            is GatewayConnectionState.Error ->
                                "Positions unavailable — fix broker connection and reconnect."
                            GatewayConnectionState.Disconnected ->
                                "Connect to your broker to load positions."
                        },
                        color = TextSecondary,
                        fontSize = 14.sp
                    )
                }
            } else {
                LazyColumn(modifier = Modifier.fillMaxSize()) {
                    items(
                        count = uiState.rows.size,
                        key = { index: Int ->
                            val row = uiState.rows[index]
                            "${row.symbol}|${row.companyName}"
                        }
                    ) { index: Int ->
                        BlotterRow(position = uiState.rows[index])
                        if (index < uiState.rows.size - 1) {
                            HorizontalDivider(color = DarkBackground, thickness = 1.dp)
                        }
                    }
                }
            }
        }
    }
}
