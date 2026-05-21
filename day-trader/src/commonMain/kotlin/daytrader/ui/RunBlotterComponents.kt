package daytrader.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import daytrader.presentation.positions.SortDirection
import daytrader.presentation.strategies.PerformanceUiState
import daytrader.presentation.strategies.RunSortColumn
import daytrader.presentation.strategies.StrategyRunRowUi
import daytrader.ui.theme.DarkBackground
import daytrader.ui.theme.GainGreen
import daytrader.ui.theme.LossRed
import daytrader.ui.theme.TableHeaderBg
import daytrader.ui.theme.TextSecondary

@Composable
fun RunBlotterTable(
    performance: PerformanceUiState,
    onHeaderClick: (RunSortColumn) -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(DarkBackground, RoundedCornerShape(8.dp))
    ) {
        RunBlotterHeader(
            activeSortColumn = performance.sortColumn,
            sortDirection = performance.sortDirection,
            onHeaderClick = onHeaderClick
        )
        if (performance.rows.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(24.dp),
                contentAlignment = Alignment.Center
            ) {
                Text("No completed runs yet.", color = TextSecondary, fontSize = 13.sp)
            }
        } else {
            LazyColumn(modifier = Modifier.fillMaxWidth()) {
                items(
                    count = performance.rows.size,
                    key = { index -> performance.rows[index].id }
                ) { index ->
                    RunBlotterRow(row = performance.rows[index])
                    if (index < performance.rows.size - 1) {
                        HorizontalDivider(color = TableHeaderBg, thickness = 1.dp)
                    }
                }
            }
        }
    }
}

@Composable
private fun RunBlotterHeader(
    activeSortColumn: RunSortColumn,
    sortDirection: SortDirection,
    onHeaderClick: (RunSortColumn) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(TableHeaderBg, RoundedCornerShape(topStart = 8.dp, topEnd = 8.dp))
            .padding(horizontal = 12.dp, vertical = 10.dp)
            .testTag("RunBlotterHeader"),
        verticalAlignment = Alignment.CenterVertically
    ) {
        RunHeaderCell("Date", RunSortColumn.DATE, activeSortColumn, sortDirection, Modifier.weight(1.1f), onClick = onHeaderClick)
        RunHeaderCell("P&L", RunSortColumn.PNL, activeSortColumn, sortDirection, Modifier.weight(1.2f), alignEnd = true, onClick = onHeaderClick)
        RunHeaderCell("Trades", RunSortColumn.TRADES, activeSortColumn, sortDirection, Modifier.weight(0.8f), alignEnd = true, onClick = onHeaderClick)
        RunHeaderCell("At risk", RunSortColumn.AT_RISK, activeSortColumn, sortDirection, Modifier.weight(0.9f), alignEnd = true, onClick = onHeaderClick)
    }
}

@Composable
private fun RowScope.RunHeaderCell(
    label: String,
    column: RunSortColumn,
    activeColumn: RunSortColumn,
    direction: SortDirection,
    modifier: Modifier = Modifier,
    alignEnd: Boolean = false,
    onClick: (RunSortColumn) -> Unit
) {
    val isActive = activeColumn == column
    Row(
        modifier = modifier
            .clickable { onClick(column) }
            .padding(vertical = 2.dp),
        horizontalArrangement = if (alignEnd) Arrangement.End else Arrangement.Start,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = label,
            color = if (isActive) Color.White else TextSecondary,
            fontWeight = FontWeight.Bold,
            fontSize = 11.sp,
            textAlign = if (alignEnd) TextAlign.End else TextAlign.Start
        )
        if (isActive) {
            Spacer(modifier = Modifier.width(4.dp))
            Icon(
                imageVector = if (direction == SortDirection.ASCENDING) Icons.Default.ArrowUpward else Icons.Default.ArrowDownward,
                contentDescription = null,
                tint = GainGreen,
                modifier = Modifier.size(11.dp)
            )
        }
    }
}

@Composable
private fun RunBlotterRow(row: StrategyRunRowUi) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 12.dp)
            .testTag("RunBlotterRow-${row.id}"),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            row.formattedDate,
            modifier = Modifier.weight(1.1f),
            color = Color.White,
            fontSize = 13.sp
        )
        Text(
            text = row.formattedPnL,
            modifier = Modifier.weight(1.2f),
            color = if (row.isPositivePnL) GainGreen else LossRed,
            fontWeight = FontWeight.SemiBold,
            fontSize = 13.sp,
            textAlign = TextAlign.End
        )
        Text(
            text = row.trades.toString(),
            modifier = Modifier.weight(0.8f),
            color = Color.White,
            fontSize = 13.sp,
            textAlign = TextAlign.End
        )
        Text(
            text = row.formattedAtRisk,
            modifier = Modifier.weight(0.9f),
            color = TextSecondary,
            fontSize = 13.sp,
            textAlign = TextAlign.End
        )
    }
}
