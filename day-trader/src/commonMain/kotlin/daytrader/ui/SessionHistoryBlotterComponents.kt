package daytrader.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
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
import daytrader.presentation.strategies.SessionTradeDetailUiState
import daytrader.presentation.strategies.StrategySessionRowUi
import daytrader.domain.FirstCandleCloseStatus
import daytrader.domain.TouchTurnLogic
import daytrader.presentation.strategies.TouchTurnPipelineNodeId
import daytrader.ui.TouchTurnOpeningBarChart
import daytrader.presentation.strategies.TouchTurnRunRecordUi
import daytrader.presentation.strategies.detailTitle
import daytrader.presentation.strategies.isSelectable
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
            Column(modifier = Modifier.fillMaxWidth()) {
                sessionHistory.rows.forEachIndexed { index, row ->
                    SessionHistoryAccordionRow(
                        row = row,
                        selectedTradeDetail = sessionHistory.selectedSessionTradeDetail
                            .takeIf { row.isSelected },
                        onToggle = { onSelectRun(row.id) },
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
private fun SessionHistoryAccordionRow(
    row: StrategySessionRowUi,
    selectedTradeDetail: SessionTradeDetailUiState?,
    onToggle: () -> Unit,
    onDeleteRun: (runId: String) -> Unit
) {
    var showDeleteConfirm by remember { mutableStateOf(false) }
    val isExpanded = row.isSelected
    val isExpandable = row.hasTradeDetail || row.hasPipelineLog
    val rowBg = if (isExpanded) TableHeaderBg.copy(alpha = 0.45f) else Color.Transparent

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(rowBg)
            .testTag("SessionHistoryBlotterRow-${row.id}")
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 6.dp, end = 4.dp, top = 4.dp, bottom = if (isExpanded) 2.dp else 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(
                modifier = Modifier
                    .weight(1f)
                    .clickable(enabled = isExpandable) { onToggle() }
                    .padding(vertical = 4.dp, horizontal = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                SessionHistoryDisclosureChevron(
                    expanded = isExpanded,
                    enabled = isExpandable,
                    modifier = Modifier.testTag("SessionHistoryChevron-${row.id}")
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

        AnimatedVisibility(
            visible = isExpanded,
            enter = expandVertically(),
            exit = shrinkVertically()
        ) {
            SessionHistoryExpandedSections(
                row = row,
                tradeDetail = selectedTradeDetail,
                modifier = Modifier.padding(start = 28.dp, end = 10.dp, bottom = 8.dp)
            )
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

@Composable
private fun SessionHistoryDisclosureChevron(
    expanded: Boolean,
    enabled: Boolean,
    modifier: Modifier = Modifier
) {
    Text(
        text = when {
            !enabled -> " "
            expanded -> "▾"
            else -> "▸"
        },
        color = if (enabled) TextSecondary else Color.Transparent,
        fontSize = 11.sp,
        modifier = modifier.width(12.dp)
    )
}

@Composable
private fun SessionHistoryExpandedSections(
    row: StrategySessionRowUi,
    tradeDetail: SessionTradeDetailUiState?,
    modifier: Modifier = Modifier
) {
    var pipelineExpanded by rememberSaveable(row.id, "pipeline") { mutableStateOf(false) }
    var runExpanded by rememberSaveable(row.id, "run") { mutableStateOf(false) }
    var tradeExpanded by rememberSaveable(row.id, "trade") { mutableStateOf(false) }

    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(2.dp)
    ) {
        row.pipelineGraph?.let { graph ->
            var selectedNode by rememberSaveable(row.id, "pipelineNode") {
                mutableStateOf(graph.defaultSelectedNode())
            }
            LaunchedEffect(graph) {
                if (selectedNode == null || graph.node(selectedNode!!)?.isSelectable() != true) {
                    selectedNode = graph.defaultSelectedNode()
                }
            }
            SessionHistoryCollapsibleSection(
                title = "Pipeline",
                expanded = pipelineExpanded,
                onToggle = { pipelineExpanded = !pipelineExpanded },
                testTag = "SessionHistorySectionPipeline-${row.id}"
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 4.dp, bottom = 2.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    TouchTurnPipelineGraphView(
                        graph = graph,
                        compact = false,
                        showTitle = false,
                        selectedNodeId = selectedNode,
                        onNodeSelected = { selectedNode = it },
                        modifier = Modifier.testTag("SessionHistoryBlotterRowPipeline-${row.id}")
                    )
                    TouchTurnPipelineDetailPanel(
                        selectedNodeId = selectedNode,
                        graph = graph
                    ) { nodeId ->
                        when (nodeId) {
                            TouchTurnPipelineNodeId.Readiness ->
                                row.touchTurnSessionStart?.let { startUi ->
                                    TouchTurnSessionStartDetail(ui = startUi)
                                } ?: Text(
                                    "Session start details were not recorded for this run.",
                                    fontSize = 11.sp,
                                    color = TextSecondary
                                )
                            TouchTurnPipelineNodeId.Orders,
                            TouchTurnPipelineNodeId.Position ->
                                tradeDetail?.let { detail ->
                                    SessionTradeDetailPanel(
                                        detail = detail,
                                        testTagPrefix = "SessionHistoryTrade"
                                    )
                                } ?: Text(
                                    "No trade recorded for this session.",
                                    fontSize = 11.sp,
                                    color = TextSecondary
                                )
                            TouchTurnPipelineNodeId.Data -> {
                                val bar = row.touchTurnOpeningBar
                                val currency = row.touchTurnOpeningBarCurrency ?: "USD"
                                if (bar != null) {
                                    TouchTurnOpeningBarChart(
                                        candle = bar,
                                        candleColor = TouchTurnLogic.firstCandleColor(bar),
                                        currencyCode = currency,
                                        closeStatus = FirstCandleCloseStatus.CLOSED,
                                        rangeThreshold = row.touchTurnRangeThreshold
                                    )
                                } else {
                                    Text(
                                        "Opening bar data not recorded for this session.",
                                        fontSize = 11.sp,
                                        color = TextSecondary
                                    )
                                }
                            }
                            TouchTurnPipelineNodeId.Rules ->
                                row.touchTurnAnalysisSession?.let { analysisSession ->
                                    TouchTurnPipelineSectionRules(
                                        session = analysisSession,
                                        graph = graph,
                                        sessionEnded = true,
                                        requireLivePriceChecks = row.touchTurnRequireLivePriceChecks
                                    )
                                } ?: Text(
                                    "Entry rules were not recorded for this session.",
                                    fontSize = 11.sp,
                                    color = TextSecondary
                                )
                            else ->
                                Text(
                                    graph.node(nodeId)?.label ?: nodeId.detailTitle(),
                                    fontSize = 11.sp,
                                    color = TextSecondary
                                )
                        }
                    }
                }
            }
        }
        row.touchTurnRunDetail?.let { detail ->
            SessionHistoryCollapsibleSection(
                title = "Run details",
                expanded = runExpanded,
                onToggle = { runExpanded = !runExpanded },
                testTag = "SessionHistorySectionRun-${row.id}"
            ) {
                TouchTurnRunRecordDetail(
                    detail = detail,
                    modifier = Modifier
                        .padding(top = 4.dp, bottom = 2.dp)
                        .testTag("SessionHistoryBlotterRowRunRecord-${row.id}")
                )
            }
        }
        tradeDetail?.let { detail ->
            val fillCount = detail.fills.size
            val title = if (fillCount > 0) "Trade & fills ($fillCount)" else "Trade"
            SessionHistoryCollapsibleSection(
                title = title,
                expanded = tradeExpanded,
                onToggle = { tradeExpanded = !tradeExpanded },
                testTag = "SessionHistorySectionTrade-${row.id}"
            ) {
                SessionTradeDetailCompact(
                    detail = detail,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 4.dp, bottom = 2.dp)
                        .testTag("SessionHistoryTradeDetail"),
                    testTagPrefix = "SessionHistoryTrade"
                )
            }
        }
    }
}

@Composable
private fun SessionHistoryCollapsibleSection(
    title: String,
    expanded: Boolean,
    onToggle: () -> Unit,
    testTag: String,
    content: @Composable () -> Unit
) {
    Column(modifier = Modifier.fillMaxWidth().testTag(testTag)) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = onToggle)
                .padding(vertical = 3.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = if (expanded) "▾" else "▸",
                color = TextSecondary.copy(alpha = 0.8f),
                fontSize = 10.sp,
                modifier = Modifier.width(12.dp)
            )
            Text(
                text = title,
                color = TextSecondary,
                fontSize = 10.sp,
                fontWeight = FontWeight.Medium
            )
        }
        AnimatedVisibility(
            visible = expanded,
            enter = expandVertically(),
            exit = shrinkVertically()
        ) {
            content()
        }
    }
}

@Composable
private fun TouchTurnRunRecordDetail(
    detail: TouchTurnRunRecordUi,
    modifier: Modifier = Modifier
) {
    Column(modifier = modifier.fillMaxWidth()) {
        Text(
            text = detail.teaser,
            color = TextSecondary.copy(alpha = 0.85f),
            fontSize = 10.sp,
            lineHeight = 12.sp,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis
        )
        if (detail.body.isNotBlank()) {
            Text(
                text = detail.body,
                color = Color(0xFF9EABB6),
                fontSize = 9.sp,
                lineHeight = 11.sp,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 2.dp)
            )
        }
    }
}

private fun sessionHistoryPnLColor(row: StrategySessionRowUi): Color = when {
    row.isInProgress -> TextSecondary
    row.isPnLFlat -> TextSecondary
    row.isPositivePnL -> GainGreen
    row.formattedPnL == "—" -> TextSecondary
    else -> LossRed
}
