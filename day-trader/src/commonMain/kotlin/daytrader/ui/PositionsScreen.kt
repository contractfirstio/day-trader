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
import daytrader.broker.IbConnectionState
import daytrader.presentation.positions.PositionsViewModel
import daytrader.ui.theme.DarkBackground
import daytrader.ui.theme.SurfaceDark
import daytrader.ui.theme.TextSecondary

@Composable
fun PositionsScreen(
    viewModel: PositionsViewModel,
    connectionState: IbConnectionState
) {
    val uiState by viewModel.uiState.collectAsState()

    Column(modifier = Modifier.fillMaxSize().padding(24.dp)) {
        Column {
            Text("Current Positions", fontSize = 28.sp, fontWeight = FontWeight.Bold, color = Color.White)
            Text("Fully Sortable Blotter", fontSize = 13.sp, color = TextSecondary)
        }

        Spacer(modifier = Modifier.height(24.dp))

        Text("Open Positions (${uiState.rows.size})", fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = Color.White)

        Spacer(modifier = Modifier.height(16.dp))

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
                            is IbConnectionState.Connected ->
                                "No open positions reported by IB Gateway."
                            IbConnectionState.Connecting ->
                                "Loading positions from IB Gateway…"
                            is IbConnectionState.Error ->
                                "Positions unavailable — fix IB connection and reconnect."
                            IbConnectionState.Disconnected ->
                                "Connect to IB Gateway to load positions."
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
