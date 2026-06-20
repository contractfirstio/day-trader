package daytrader.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
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
        SessionHistorySortBar(
            activeSortColumn = sessionHistory.sortColumn,
            sortDirection = sessionHistory.sortDirection,
            onHeaderClick = onHeaderClick
        )
        if (sessionHistory.rows.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(20.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    "No sessions yet — start the deployment and stop to log P&L.",
                    color = TextSecondary,
                    fontSize = 12.sp
                )
            }
        } else {
            LazyColumn(
                modifier = modifier.fillMaxWidth(),
            ) {
                itemsIndexed(
                    items = sessionHistory.rows,
                    key = { _, row -> row.id },
                ) { index, row ->
                    SessionHistoryBlotterRow(
                        row = row,
                        onSelect = { onSelectRun(row.id) },
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

/** Compact sort controls — not a full table header. */
@Composable
private fun SessionHistorySortBar(
    activeSortColumn: SessionHistorySortColumn,
    sortDirection: SortDirection,
    onHeaderClick: (SessionHistorySortColumn) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(TableHeaderBg, RoundedCornerShape(topStart = 8.dp, topEnd = 8.dp))
            .padding(horizontal = 10.dp, vertical = 6.dp)
            .testTag("SessionHistoryBlotterHeader"),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text("Sessions", color = TextSecondary, fontSize = 10.sp, fontWeight = FontWeight.Bold)
        SessionHistorySortChip("Time", SessionHistorySortColumn.TIME, activeSortColumn, sortDirection, onHeaderClick)
        SessionHistorySortChip("P&L", SessionHistorySortColumn.PNL, activeSortColumn, sortDirection, onHeaderClick)
    }
}

@Composable
private fun SessionHistorySortChip(
    label: String,
    column: SessionHistorySortColumn,
    activeColumn: SessionHistorySortColumn,
    direction: SortDirection,
    onClick: (SessionHistorySortColumn) -> Unit
) {
    val isActive = activeColumn == column
    Row(
        modifier = Modifier
            .clickable { onClick(column) }
            .padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = label,
            color = if (isActive) Color.White else TextSecondary,
            fontSize = 10.sp,
            fontWeight = if (isActive) FontWeight.SemiBold else FontWeight.Normal
        )
        if (isActive) {
            Spacer(modifier = Modifier.width(3.dp))
            Icon(
                imageVector = if (direction == SortDirection.ASCENDING) Icons.Default.ArrowUpward else Icons.Default.ArrowDownward,
                tint = GainGreen,
                contentDescription = null,
                modifier = Modifier.size(10.dp)
            )
        }
    }
}

@Composable
private fun SessionHistoryBlotterRow(
    row: StrategySessionRowUi,
    onSelect: () -> Unit,
    onDeleteRun: (runId: String) -> Unit
) {
    var showDeleteConfirm by remember { mutableStateOf(false) }
    val rowBg = if (row.isSelected) TableHeaderBg.copy(alpha = 0.45f) else Color.Transparent

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(rowBg)
            .testTag("SessionHistoryBlotterRow-${row.id}")
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 6.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(
                modifier = Modifier
                    .weight(1f)
                    .clickable { onSelect() }
                    .padding(vertical = 4.dp, horizontal = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = when {
                        row.isSelected -> "●"
                        row.opensOnTradingTab -> "○"
                        else -> " "
                    },
                    color = if (row.isSelected) GainGreen else TextSecondary.copy(alpha = 0.6f),
                    fontSize = 10.sp,
                    modifier = Modifier
                        .width(12.dp)
                        .testTag("SessionHistoryRowMarker-${row.id}")
                )
                Spacer(modifier = Modifier.width(6.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = row.formattedSessionTime,
                            color = if (row.isInProgress) TextSecondary else Color.White,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Medium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f, fill = false)
                        )
                        Text(
                            text = row.formattedPnL,
                            modifier = Modifier
                                .padding(start = 8.dp)
                                .testTag("SessionHistoryBlotterRowPnL-${row.id}"),
                            color = sessionHistoryPnLColor(row),
                            fontWeight = FontWeight.SemiBold,
                            fontSize = 12.sp,
                            textAlign = TextAlign.End
                        )
                    }
                    if (row.positionLine != "—") {
                        Text(
                            text = row.positionLine,
                            modifier = Modifier
                                .padding(top = 1.dp)
                                .testTag("RunBlotterPosition-${row.id}"),
                            color = TextSecondary,
                            fontSize = 10.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    SessionLogReference(
                        deploymentId = row.deploymentId,
                        sessionId = row.id,
                        compact = true,
                        modifier = Modifier.padding(top = 2.dp)
                    )
                    if (row.opensOnTradingTab && !row.isSelected) {
                        Text(
                            "Open on Trading tab",
                            color = TextSecondary.copy(alpha = 0.75f),
                            fontSize = 9.sp,
                            modifier = Modifier.padding(top = 2.dp)
                        )
                    }
                }
            }
            if (row.canDelete) {
                IconButton(
                    onClick = { showDeleteConfirm = true },
                    modifier = Modifier
                        .size(32.dp)
                        .testTag("RunBlotterDelete-${row.id}")
                ) {
                    Icon(
                        imageVector = Icons.Default.Delete,
                        contentDescription = "Delete session",
                        tint = TextSecondary,
                        modifier = Modifier.size(18.dp)
                    )
                }
            }
        }
    }

    if (showDeleteConfirm) {
        val timeLabel = row.formattedSessionTime.takeIf { it != "—" } ?: "this session"
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

private fun sessionHistoryPnLColor(row: StrategySessionRowUi): Color = when {
    row.isInProgress -> TextSecondary
    row.isPnLFlat -> TextSecondary
    row.isPositivePnL -> GainGreen
    row.formattedPnL == "—" -> TextSecondary
    else -> LossRed
}
