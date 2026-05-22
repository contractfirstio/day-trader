package daytrader.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
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
import daytrader.ui.theme.BrandRed
import daytrader.ui.theme.DarkBackground
import daytrader.ui.theme.GainGreen
import daytrader.ui.theme.LossRed
import daytrader.ui.theme.SurfaceDark
import daytrader.ui.theme.TableHeaderBg
import daytrader.ui.theme.TextSecondary

@Composable
fun RunBlotterTable(
    performance: PerformanceUiState,
    onHeaderClick: (RunSortColumn) -> Unit,
    onDeleteRun: (runId: String) -> Unit,
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
            showTouchTurnColumns = performance.includeTouchTurnFields,
            onHeaderClick = onHeaderClick
        )
        if (performance.rows.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(24.dp),
                contentAlignment = Alignment.Center
            ) {
                Text("No runs yet — start and stop the instance to record a session.", color = TextSecondary, fontSize = 13.sp)
            }
        } else {
            Column(modifier = Modifier.fillMaxWidth()) {
                performance.rows.forEachIndexed { index, row ->
                    RunBlotterRow(
                        row = row,
                        showTouchTurnColumns = performance.includeTouchTurnFields,
                        onDeleteRun = onDeleteRun
                    )
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
    showTouchTurnColumns: Boolean,
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
        RunHeaderCell("Start time", RunSortColumn.START, activeSortColumn, sortDirection, Modifier.weight(0.9f), onClick = onHeaderClick)
        RunHeaderCell("Stop time", RunSortColumn.STOP, activeSortColumn, sortDirection, Modifier.weight(0.9f), onClick = onHeaderClick)
        if (showTouchTurnColumns) {
            RunHeaderCell("Liquidity candle", RunSortColumn.LIQUIDITY, activeSortColumn, sortDirection, Modifier.weight(1f), onClick = onHeaderClick)
            RunHeaderCell("Orders placed", RunSortColumn.ORDERS, activeSortColumn, sortDirection, Modifier.weight(1f), onClick = onHeaderClick)
        }
        RunHeaderCell("P&L", RunSortColumn.PNL, activeSortColumn, sortDirection, Modifier.weight(0.9f), alignEnd = true, onClick = onHeaderClick)
        Spacer(modifier = Modifier.width(40.dp))
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
            fontSize = 10.sp,
            textAlign = if (alignEnd) TextAlign.End else TextAlign.Start,
            lineHeight = 12.sp
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
private fun RunBlotterRow(
    row: StrategyRunRowUi,
    showTouchTurnColumns: Boolean,
    onDeleteRun: (runId: String) -> Unit
) {
    var showDeleteConfirm by remember { mutableStateOf(false) }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 10.dp)
            .testTag("RunBlotterRow-${row.id}"),
        verticalAlignment = Alignment.CenterVertically
    ) {
        RunCell(row.formattedStartTime, Modifier.weight(0.9f), row.isInProgress)
        RunCell(row.formattedStopTime, Modifier.weight(0.9f), row.isInProgress)
        if (showTouchTurnColumns) {
            RunCell(row.liquidityCandle, Modifier.weight(1f), row.isInProgress)
            RunCell(row.ordersPlaced, Modifier.weight(1f), row.isInProgress)
        }
        Text(
            text = row.formattedPnL,
            modifier = Modifier
                .weight(0.9f)
                .testTag("RunBlotterRowPnL-${row.id}"),
            color = when {
                row.isInProgress -> TextSecondary
                row.isPnLNothing -> TextSecondary
                row.isPositivePnL -> GainGreen
                row.formattedPnL == "—" -> TextSecondary
                else -> LossRed
            },
            fontWeight = FontWeight.SemiBold,
            fontSize = 13.sp,
            textAlign = TextAlign.End
        )
        if (row.canDelete) {
            IconButton(
                onClick = { showDeleteConfirm = true },
                modifier = Modifier
                    .size(40.dp)
                    .testTag("RunBlotterDelete-${row.id}")
            ) {
                Icon(
                    imageVector = Icons.Default.Delete,
                    contentDescription = "Delete session",
                    tint = TextSecondary
                )
            }
        } else {
            Spacer(modifier = Modifier.width(40.dp))
        }
    }

    if (showDeleteConfirm) {
        val timeLabel = when {
            row.formattedStartTime != "—" && row.formattedStopTime != "—" ->
                "${row.formattedStartTime}–${row.formattedStopTime}"
            row.formattedStartTime != "—" -> row.formattedStartTime
            else -> "this session"
        }
        AlertDialog(
            onDismissRequest = { showDeleteConfirm = false },
            containerColor = SurfaceDark,
            title = {
                Text("Delete performance record?", color = Color.White, fontWeight = FontWeight.Bold)
            },
            text = {
                Text(
                    "Remove the session ($timeLabel) from this instance's performance history? This cannot be undone.",
                    color = TextSecondary,
                    fontSize = 14.sp
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        showDeleteConfirm = false
                        onDeleteRun(row.id)
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = BrandRed)
                ) {
                    Text("Delete")
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteConfirm = false }) {
                    Text("Cancel", color = TextSecondary)
                }
            }
        )
    }
}

@Composable
private fun RowScope.RunCell(text: String, modifier: Modifier, muted: Boolean) {
    Text(
        text = text,
        modifier = modifier,
        color = if (muted) TextSecondary else Color.White,
        fontSize = 13.sp
    )
}
