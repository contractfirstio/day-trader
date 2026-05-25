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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import daytrader.presentation.positions.SortDirection
import daytrader.presentation.strategies.SessionHistoryUiState
import daytrader.presentation.strategies.SessionHistorySortColumn
import daytrader.presentation.strategies.StrategySessionRowUi
import daytrader.ui.theme.BrandRed
import daytrader.ui.theme.DarkBackground
import daytrader.ui.theme.GainGreen
import daytrader.ui.theme.LossRed
import daytrader.ui.theme.SurfaceDark
import daytrader.ui.theme.TableHeaderBg
import daytrader.ui.theme.TextSecondary

@Composable
fun SessionHistoryBlotterTable(
    sessionHistory: SessionHistoryUiState,
    onHeaderClick: (SessionHistorySortColumn) -> Unit,
    onSelectRun: (runId: String) -> Unit,
    onDeleteRun: (runId: String) -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(DarkBackground, RoundedCornerShape(8.dp))
    ) {
        SessionHistoryBlotterHeader(
            activeSortColumn = sessionHistory.sortColumn,
            sortDirection = sessionHistory.sortDirection,
            showTouchTurnColumns = sessionHistory.includeTouchTurnFields,
            onHeaderClick = onHeaderClick
        )
        if (sessionHistory.rows.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(24.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    "No sessions yet — start the deployment and stop to log P&L.",
                    color = TextSecondary,
                    fontSize = 13.sp
                )
            }
        } else {
            Column(modifier = Modifier.fillMaxWidth()) {
                sessionHistory.rows.forEachIndexed { index, row ->
                    SessionHistoryBlotterRow(
                        row = row,
                        showTouchTurnColumns = sessionHistory.includeTouchTurnFields,
                        onSelectRun = onSelectRun,
                        onDeleteRun = onDeleteRun
                    )
                    if (index < sessionHistory.rows.size - 1) {
                        HorizontalDivider(color = TableHeaderBg, thickness = 1.dp)
                    }
                }
            }
        }
    }
}

@Composable
fun SessionHistoryTradeDetail(
    sessionHistory: SessionHistoryUiState,
    modifier: Modifier = Modifier
) {
    val detail = sessionHistory.selectedSessionTradeDetail ?: return
    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(SurfaceDark, RoundedCornerShape(8.dp))
            .padding(12.dp)
            .testTag("SessionHistoryTradeDetail")
    ) {
        SessionTradeDetailPanel(detail, testTagPrefix = "SessionHistoryTrade")
    }
}

@Composable
fun SessionHistoryTouchTurnPipelineLog(
    sessionHistory: SessionHistoryUiState,
    modifier: Modifier = Modifier
) {
    val steps = sessionHistory.selectedTouchTurnPipeline ?: return
    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(SurfaceDark, RoundedCornerShape(8.dp))
            .padding(horizontal = 12.dp, vertical = 10.dp)
            .testTag("SessionHistoryTouchTurnPipelineLog")
    ) {
        Text(
            "Session pipeline log",
            fontSize = 12.sp,
            fontWeight = FontWeight.SemiBold,
            color = Color.White
        )
        Spacer(modifier = Modifier.height(8.dp))
        TouchTurnStatusBreadcrumbRow(
            steps = steps,
            modifier = Modifier.testTag("SessionHistoryTouchTurnPipelineBreadcrumb")
        )
    }
}

@Composable
private fun SessionHistoryBlotterHeader(
    activeSortColumn: SessionHistorySortColumn,
    sortDirection: SortDirection,
    showTouchTurnColumns: Boolean,
    onHeaderClick: (SessionHistorySortColumn) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(TableHeaderBg, RoundedCornerShape(topStart = 8.dp, topEnd = 8.dp))
            .padding(horizontal = 12.dp, vertical = 10.dp)
            .testTag("SessionHistoryBlotterHeader"),
        verticalAlignment = Alignment.CenterVertically
    ) {
        SessionHistoryHeaderCell("Session start", SessionHistorySortColumn.START, activeSortColumn, sortDirection, Modifier.weight(0.85f), onClick = onHeaderClick)
        SessionHistoryHeaderCell("Session end", SessionHistorySortColumn.STOP, activeSortColumn, sortDirection, Modifier.weight(0.85f), onClick = onHeaderClick)
        Text(
            "Position",
            modifier = Modifier.weight(1.1f),
            color = TextSecondary,
            fontWeight = FontWeight.Bold,
            fontSize = 10.sp
        )
        if (showTouchTurnColumns) {
            SessionHistoryHeaderCell("Liquidity candle", SessionHistorySortColumn.LIQUIDITY, activeSortColumn, sortDirection, Modifier.weight(0.9f), onClick = onHeaderClick)
            SessionHistoryHeaderCell("Orders placed", SessionHistorySortColumn.ORDERS, activeSortColumn, sortDirection, Modifier.weight(0.9f), onClick = onHeaderClick)
        }
        SessionHistoryHeaderCell("Realized P&L", SessionHistorySortColumn.PNL, activeSortColumn, sortDirection, Modifier.weight(0.75f), alignEnd = true, onClick = onHeaderClick)
        Spacer(modifier = Modifier.width(40.dp))
    }
}

@Composable
private fun RowScope.SessionHistoryHeaderCell(
    label: String,
    column: SessionHistorySortColumn,
    activeColumn: SessionHistorySortColumn,
    direction: SortDirection,
    modifier: Modifier = Modifier,
    alignEnd: Boolean = false,
    onClick: (SessionHistorySortColumn) -> Unit
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
                tint = GainGreen,
                contentDescription = null,
                modifier = Modifier.size(11.dp)
            )
        }
    }
}

@Composable
private fun SessionHistoryBlotterRow(
    row: StrategySessionRowUi,
    showTouchTurnColumns: Boolean,
    onSelectRun: (runId: String) -> Unit,
    onDeleteRun: (runId: String) -> Unit
) {
    var showDeleteConfirm by remember { mutableStateOf(false) }
    val rowBg = if (row.isSelected) TableHeaderBg.copy(alpha = 0.5f) else Color.Transparent

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(rowBg)
            .clickable(enabled = row.hasTradeDetail || row.hasPipelineLog) { onSelectRun(row.id) }
            .padding(horizontal = 12.dp, vertical = 10.dp)
            .testTag("SessionHistoryBlotterRow-${row.id}"),
        verticalAlignment = Alignment.CenterVertically
    ) {
        RunCell(row.formattedStartTime, Modifier.weight(0.85f), row.isInProgress)
        RunCell(row.formattedStopTime, Modifier.weight(0.85f), row.isInProgress)
        SessionHistoryTradeCell(row, Modifier.weight(1.1f))
        if (showTouchTurnColumns) {
            RunCell(row.liquidityCandle, Modifier.weight(0.9f), row.isInProgress)
            RunCell(row.ordersPlaced, Modifier.weight(0.9f), row.isInProgress)
        }
        Text(
            text = row.formattedPnL,
            modifier = Modifier
                .weight(0.75f)
                .testTag("SessionHistoryBlotterRowPnL-${row.id}"),
            color = when {
                row.isInProgress -> TextSecondary
                row.isPnLFlat -> TextSecondary
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
                Text("Delete session?", color = Color.White, fontWeight = FontWeight.Bold)
            },
            text = {
                Text(
                    "Remove this session ($timeLabel) from the deployment's session history? This cannot be undone.",
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
private fun RowScope.SessionHistoryTradeCell(row: StrategySessionRowUi, modifier: Modifier) {
    Column(modifier = modifier) {
        row.tradeSideLabel?.let { side ->
            Text(
                text = side,
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold,
                color = when (side) {
                    "Long" -> GainGreen
                    "Short" -> LossRed
                    else -> Color.White
                },
                modifier = Modifier.testTag("RunBlotterTradeSide-${row.id}")
            )
        } ?: Text("—", fontSize = 12.sp, color = TextSecondary)
        row.tradeSummary?.let { summary ->
            Text(
                text = summary,
                fontSize = 10.sp,
                color = if (row.isInProgress) TextSecondary else Color(0xFFB0BEC5),
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.testTag("RunBlotterTradeSummary-${row.id}")
            )
        }
        if (row.hasTradeDetail && !row.isSelected) {
            Text(
                "Fills & P&L",
                fontSize = 9.sp,
                color = TextSecondary.copy(alpha = 0.7f)
            )
        }
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
