package daytrader.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.ShowChart
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import daytrader.presentation.watchlist.WatchlistLabelUi
import daytrader.presentation.watchlist.WatchlistGroupFilter
import daytrader.domain.StrategyType
import daytrader.presentation.watchlist.WatchlistStrategyFilter
import daytrader.presentation.watchlist.WatchlistStrategyUi
import daytrader.presentation.watchlist.WatchlistRowUi
import daytrader.presentation.watchlist.WatchlistSortColumn
import daytrader.presentation.watchlist.WatchlistSortDirection
import daytrader.presentation.watchlist.WatchlistViewModel
import daytrader.ui.theme.*

@Composable
fun WatchlistScreen(viewModel: WatchlistViewModel) {
    val uiState by viewModel.uiState.collectAsState()

    if (uiState.showAddDialog) {
        AddWatchlistEntryDialog(
            onDismiss = viewModel::onDismissAddDialog,
            onResolveSymbol = viewModel::resolveInstrumentForSymbol,
            onAdd = viewModel::onAddEntry
        )
    }

    uiState.planDiaryEditor?.let { editor ->
        WatchlistPlanDiaryDialog(
            editor = editor,
            onDismiss = viewModel::onDismissPlanDiary,
            onStartAdd = viewModel::onStartAddDiaryEntry,
            onStartEdit = viewModel::onStartEditDiaryEntry,
            onCancelDraft = viewModel::onCancelDiaryDraft,
            onDraftBodyChange = viewModel::onDiaryDraftBodyChange,
            onDraftNotifyEnabledChange = viewModel::onDiaryDraftNotifyEnabledChange,
            onDraftNotifyDateChange = viewModel::onDiaryDraftNotifyDateChange,
            onSaveEntry = viewModel::onSaveDiaryEntry,
            onDeleteEntry = viewModel::onDeleteDiaryEntry,
            onDismissReminder = viewModel::onDismissDiaryReminder
        )
    }

    uiState.bracketOrderEditor?.let { order ->
        WatchlistBracketOrderDialog(
            order = order,
            connectionLabel = uiState.connectionLabel,
            onDismiss = viewModel::onDismissBracketOrder,
            onSubmit = viewModel::onSubmitBracketOrder
        )
    }

    uiState.reversalScoreInsight?.let { insight ->
        WatchlistReversalScoreInsightSheet(
            insight = insight,
            onDismiss = viewModel::onDismissReversalScoreInsight
        )
    }

    val entryEditor = uiState.tradePlansEditor
    if (entryEditor != null) {
        WatchlistEntryDetailPanel(
            editor = entryEditor,
            charts = uiState.entryCharts,
            onBack = viewModel::onDismissTradePlans,
            onSave = viewModel::onSaveTradePlans,
            onSideChange = viewModel::onUpdatePlanSide,
            onSizingModeChange = viewModel::onUpdatePlanSizingMode,
            onProximityEnabledChange = viewModel::onUpdatePlanProximityEnabled,
            onProximityModeChange = viewModel::onUpdatePlanProximityMode,
            onStopEntryChange = viewModel::onUpdatePlanStopEntry,
            onAdjustableTrailingStopChange = viewModel::onUpdatePlanAdjustableTrailingStop,
            onFieldChange = viewModel::onUpdatePlanField,
            onGroupInputChange = viewModel::onEditorGroupInputChange,
            onAddGroup = viewModel::onAddEditorGroup,
            onRemoveGroup = viewModel::onRemoveEditorGroup,
            onCreateStrategyDeployment = viewModel::onCreateStrategyDeployment,
            onRemoveStrategy = viewModel::onRemoveStrategy,
            onPlaceBracket = viewModel::onOpenBracketOrder,
            onReactivatePlan = viewModel::onReactivatePlan,
            onOpenDiary = viewModel::onOpenPlanDiary,
            onRelookupInstrument = viewModel::onRelookupEntryInstrument,
            modifier = Modifier.fillMaxSize().padding(16.dp)
        )
        return
    }

    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                val countLabel = if (
                    uiState.activeGroupFilter is WatchlistGroupFilter.All &&
                    uiState.activeStrategyFilter is WatchlistStrategyFilter.All
                ) {
                    "${uiState.totalEntryCount}"
                } else {
                    "${uiState.rows.size} of ${uiState.totalEntryCount}"
                }
                Text(
                    "${uiState.watchlistName} ($countLabel)",
                    fontSize = 18.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = Color.White
                )
                if (uiState.storageScopeLabel.isNotBlank()) {
                    Text(
                        uiState.storageScopeLabel,
                        fontSize = 11.sp,
                        color = TextSecondary,
                        modifier = Modifier.padding(top = 2.dp)
                    )
                }
                Spacer(modifier = Modifier.height(8.dp))
                WatchlistStatusStrip(strip = uiState.statusStrip)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = viewModel::onCheckEntryProximity,
                    enabled = !uiState.scanInProgress && !uiState.reversalScoreInProgress && uiState.totalEntryCount > 0,
                    colors = ButtonDefaults.buttonColors(containerColor = GainGreen),
                    modifier = Modifier.testTag("CheckEntryProximityButton")
                ) {
                    Icon(Icons.Default.Refresh, contentDescription = "Check entry proximity")
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(if (uiState.scanInProgress) "Scanning…" else "Check proximity")
                }
                Button(
                    onClick = viewModel::onCalculateReversalScores,
                    enabled = !uiState.reversalScoreInProgress && !uiState.scanInProgress && uiState.rows.isNotEmpty(),
                    colors = ButtonDefaults.buttonColors(containerColor = TradeBlueBorder),
                    modifier = Modifier.testTag("CalculateReversalScoreButton")
                ) {
                    Icon(Icons.Default.ShowChart, contentDescription = "Calculate reversal score")
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(if (uiState.reversalScoreInProgress) "Calculating…" else "Calculate Reversal Score")
                }
                Button(
                    onClick = viewModel::onShowAddDialog,
                    colors = ButtonDefaults.buttonColors(containerColor = BrandRed),
                    modifier = Modifier.testTag("AddWatchlistEntryButton")
                ) {
                    Icon(Icons.Default.Add, contentDescription = "Add symbol")
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("Add symbol")
                }
            }
        }

        if (uiState.macroRegimeCards.isNotEmpty()) {
            Spacer(modifier = Modifier.height(10.dp))
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                uiState.macroRegimeCards.chunked(2).forEach { rowCards ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        rowCards.forEach { card ->
                            WatchlistMacroRegimeCard(
                                card = card,
                                modifier = Modifier.weight(1f)
                            )
                        }
                        if (rowCards.size == 1) {
                            Spacer(modifier = Modifier.weight(1f))
                        }
                    }
                }
            }
        }

        uiState.activitySummary?.let { summary ->
            Spacer(modifier = Modifier.height(8.dp))
            WatchlistActivitySummaryBar(summary = summary)
        }

        if (uiState.scanInProgress) {
            uiState.scanProgress?.let { progress ->
                Spacer(modifier = Modifier.height(8.dp))
                WatchlistScanProgressPanel(progress = progress)
            }
        }

        if (uiState.reversalScoreInProgress) {
            uiState.reversalScoreProgress?.let { progress ->
                Spacer(modifier = Modifier.height(8.dp))
                WatchlistReversalScoreProgressPanel(progress = progress)
            }
        }

        if (uiState.totalEntryCount > 0) {
            Spacer(modifier = Modifier.height(8.dp))
            WatchlistGroupFilterBar(
                chips = uiState.groupFilterChips,
                onFilterSelected = viewModel::onGroupFilterSelected
            )
            if (uiState.strategyFilterChips.size > 1) {
                Spacer(modifier = Modifier.height(6.dp))
                WatchlistStrategyFilterBar(
                    chips = uiState.strategyFilterChips,
                    onFilterSelected = viewModel::onStrategyFilterSelected
                )
            }
            Spacer(modifier = Modifier.height(8.dp))
        }

        Column(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .border(1.dp, SurfaceDark, RoundedCornerShape(8.dp))
                .background(SurfaceDark, RoundedCornerShape(8.dp))
        ) {
            WatchlistHeader(
                activeSortColumn = uiState.sortColumn,
                sortDirection = uiState.sortDirection,
                onHeaderClick = viewModel::onHeaderClick
            )

            if (uiState.totalEntryCount == 0) {
                Box(
                    modifier = Modifier.fillMaxSize().padding(24.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "No symbols yet. Add instruments you want to track for longer-term ideas.",
                        color = TextSecondary,
                        fontSize = 14.sp,
                        textAlign = TextAlign.Center
                    )
                }
            } else if (uiState.rows.isEmpty()) {
                Box(
                    modifier = Modifier.fillMaxSize().padding(24.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "No symbols match the current filters.",
                        color = TextSecondary,
                        fontSize = 14.sp,
                        textAlign = TextAlign.Center
                    )
                }
            } else {
                LazyColumn(modifier = Modifier.fillMaxSize()) {
                    items(
                        count = uiState.rows.size,
                        key = { index -> uiState.rows[index].entryId }
                    ) { index ->
                        WatchlistRow(
                            row = uiState.rows[index],
                            onOpenPlans = { viewModel.onOpenTradePlans(uiState.rows[index].entryId) },
                            onOpenReversalScoreInsight = {
                                viewModel.onOpenReversalScoreInsight(uiState.rows[index].entryId)
                            },
                            onRemove = { viewModel.onRemoveEntry(uiState.rows[index].entryId) },
                            onGroupClick = { labelId ->
                                viewModel.onGroupFilterSelected(WatchlistGroupFilter.Group(labelId))
                            },
                            onStrategyClick = { strategyType ->
                                viewModel.onStrategyFilterSelected(WatchlistStrategyFilter.Strategy(strategyType))
                            }
                        )
                        if (index < uiState.rows.size - 1) {
                            HorizontalDivider(color = DarkBackground, thickness = 1.dp)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun WatchlistGroupFilterBar(
    chips: List<daytrader.presentation.watchlist.WatchlistGroupFilterChipUi>,
    onFilterSelected: (WatchlistGroupFilter) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .testTag("WatchlistGroupFilterBar"),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        chips.forEach { chip ->
            WatchlistFilterChip(
                label = chip.label,
                selected = chip.selected,
                onClick = { onFilterSelected(chip.filter) }
            )
        }
    }
}

@Composable
private fun WatchlistFilterChip(label: String, selected: Boolean, onClick: () -> Unit) {
    val bg = if (selected) BrandRed.copy(alpha = 0.25f) else DarkBackground
    val borderColor = if (selected) SelectionBorder else TableHeaderBg
    Text(
        text = label,
        modifier = Modifier
            .clickable(onClick = onClick)
            .background(bg, RoundedCornerShape(10.dp))
            .border(1.dp, borderColor, RoundedCornerShape(10.dp))
            .padding(horizontal = 8.dp, vertical = 3.dp),
        color = if (selected) Color.White else TextSecondary,
        fontSize = 10.sp,
        fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
        maxLines = 1
    )
}

@Composable
private fun WatchlistHeader(
    activeSortColumn: WatchlistSortColumn,
    sortDirection: WatchlistSortDirection,
    onHeaderClick: (WatchlistSortColumn) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(TableHeaderBg, RoundedCornerShape(topStart = 8.dp, topEnd = 8.dp))
            .padding(horizontal = 16.dp, vertical = 12.dp)
            .testTag("WatchlistTableHeaderRow"),
        verticalAlignment = Alignment.CenterVertically
    ) {
        WatchlistHeaderCell("Company", WatchlistSortColumn.COMPANY, activeSortColumn, sortDirection, Modifier.weight(1.8f), onHeaderClick)
        WatchlistHeaderCell("Symbol", WatchlistSortColumn.SYMBOL, activeSortColumn, sortDirection, Modifier.weight(0.8f), onHeaderClick)
        WatchlistHeaderCell("Market", WatchlistSortColumn.MARKET, activeSortColumn, sortDirection, Modifier.weight(0.7f), onHeaderClick)
        WatchlistHeaderCell("Groups", WatchlistSortColumn.GROUPS, activeSortColumn, sortDirection, Modifier.weight(0.9f), onHeaderClick)
        WatchlistHeaderCell("Strategies", WatchlistSortColumn.STRATEGIES, activeSortColumn, sortDirection, Modifier.weight(1.1f), onHeaderClick)
        WatchlistHeaderCell("Last Price", WatchlistSortColumn.LAST, activeSortColumn, sortDirection, Modifier.weight(1.0f).padding(end = 12.dp), onHeaderClick, alignEnd = true)
        WatchlistHeaderCell("Rev Score", WatchlistSortColumn.REVERSAL_SCORE, activeSortColumn, sortDirection, Modifier.weight(0.95f).padding(end = 16.dp), onHeaderClick, alignEnd = true)
        WatchlistHeaderCell("Status", WatchlistSortColumn.STATUS, activeSortColumn, sortDirection, Modifier.weight(0.9f).padding(start = 4.dp), onHeaderClick)
        WatchlistHeaderCell("Plans", WatchlistSortColumn.PLANS, activeSortColumn, sortDirection, Modifier.weight(1.2f), onHeaderClick)
        Spacer(modifier = Modifier.width(40.dp))
    }
}

@Composable
private fun RowScope.WatchlistHeaderCell(
    label: String,
    columnType: WatchlistSortColumn,
    activeColumn: WatchlistSortColumn,
    direction: WatchlistSortDirection,
    modifier: Modifier = Modifier,
    onClick: (WatchlistSortColumn) -> Unit,
    alignEnd: Boolean = false,
    sortable: Boolean = true
) {
    val isActive = activeColumn == columnType
    Row(
        modifier = modifier
            .then(if (sortable) Modifier.clickable { onClick(columnType) } else Modifier)
            .padding(vertical = 2.dp),
        horizontalArrangement = if (alignEnd) Arrangement.End else Arrangement.Start,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = label,
            color = if (isActive && sortable) Color.White else TextSecondary,
            fontWeight = FontWeight.Bold,
            fontSize = 12.sp,
            textAlign = if (alignEnd) TextAlign.End else TextAlign.Start
        )
        if (isActive && sortable) {
            Spacer(modifier = Modifier.width(4.dp))
            Icon(
                imageVector = if (direction == WatchlistSortDirection.ASCENDING) Icons.Default.ArrowUpward else Icons.Default.ArrowDownward,
                contentDescription = "Sorted direction",
                tint = GainGreen,
                modifier = Modifier.size(12.dp)
            )
        }
    }
}

@Composable
private fun WatchlistRow(
    row: WatchlistRowUi,
    onOpenPlans: () -> Unit,
    onOpenReversalScoreInsight: () -> Unit,
    onRemove: () -> Unit,
    onGroupClick: (String) -> Unit,
    onStrategyClick: (StrategyType) -> Unit
) {
    val rowColor = if (row.isNearEntry) TradeBlueSurface.copy(alpha = 0.85f) else Color.Transparent
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(rowColor)
            .clickable(onClick = onOpenPlans)
            .padding(horizontal = 16.dp, vertical = 14.dp)
            .testTag("WatchlistDataRow"),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(row.companyName, modifier = Modifier.weight(1.8f), color = Color.White, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(row.symbol, modifier = Modifier.weight(0.8f), color = TextSecondary, fontWeight = FontWeight.Bold, fontSize = 14.sp)
        Text(row.marketLabel, modifier = Modifier.weight(0.7f), color = TextSecondary, fontSize = 13.sp)
        WatchlistRowGroups(
            groups = row.groups,
            modifier = Modifier.weight(0.9f),
            onGroupClick = onGroupClick
        )
        WatchlistRowStrategies(
            strategies = row.strategies,
            modifier = Modifier.weight(1.1f),
            onStrategyClick = onStrategyClick
        )
        Column(
            modifier = Modifier.weight(1.0f).padding(end = 12.dp),
            horizontalAlignment = Alignment.End
        ) {
            Text(
                row.formattedLast,
                color = Color.White,
                fontWeight = FontWeight.Medium,
                fontSize = 14.sp,
                textAlign = TextAlign.End
            )
            row.lastPriceSublabel?.let { sublabel ->
                Text(
                    sublabel,
                    color = TextSecondary,
                    fontSize = 10.sp,
                    textAlign = TextAlign.End,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 2.dp)
                )
            }
        }
        Box(
            modifier = Modifier
                .weight(0.95f)
                .padding(end = 16.dp)
                .then(
                    if (row.reversalScoreHasInsight) {
                        Modifier.clickable(onClick = onOpenReversalScoreInsight)
                    } else {
                        Modifier
                    }
                ),
            contentAlignment = Alignment.CenterEnd
        ) {
            when {
                row.reversalScoreLoading -> {
                    CircularProgressIndicator(
                        modifier = Modifier.size(16.dp),
                        strokeWidth = 2.dp,
                        color = TradeBlueBorder
                    )
                }
                row.reversalScoreLabel != null && row.reversalScore != null -> {
                    Column(horizontalAlignment = Alignment.End) {
                        ReversalScorePill(
                            score = row.reversalScore,
                            stale = row.reversalScoreStale
                        )
                        row.reversalScoreAlignmentBadgeLabel?.let { badgeLabel ->
                            AlignmentBadgeChip(
                                label = badgeLabel,
                                modifier = Modifier.padding(top = 4.dp)
                            )
                        }
                        row.reversalScoreCalculatedAtLabel?.let { scoredAt ->
                            Text(
                                scoredAt,
                                color = TextSecondary,
                                fontSize = 10.sp,
                                textAlign = TextAlign.End,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.padding(top = 2.dp)
                            )
                        }
                    }
                }
                else -> {
                    Text("—", color = TextSecondary, fontSize = 14.sp, textAlign = TextAlign.End)
                }
            }
        }
        Text(
            row.proximityStatusLabel,
            modifier = Modifier.weight(0.9f).padding(start = 4.dp),
            color = if (row.isNearEntry) TradeBlueBorder else TextSecondary,
            fontSize = 12.sp,
            fontWeight = if (row.isNearEntry) FontWeight.SemiBold else FontWeight.Normal
        )
        Text(
            row.nearEntrySummary ?: row.planSummary.orEmpty(),
            modifier = Modifier.weight(1.2f),
            color = if (row.isNearEntry) TradeBlueBorder else TextSecondary,
            fontSize = 12.sp,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis
        )
        IconButton(onClick = onRemove, modifier = Modifier.size(36.dp)) {
            Icon(Icons.Default.Delete, contentDescription = "Remove from watchlist", tint = TextSecondary)
        }
    }
}

@Composable
private fun WatchlistRowGroups(
    groups: List<WatchlistLabelUi>,
    modifier: Modifier = Modifier,
    onGroupClick: (String) -> Unit
) {
    if (groups.isEmpty()) {
        Text(
            "—",
            modifier = modifier,
            color = TextSecondary.copy(alpha = 0.5f),
            fontSize = 12.sp
        )
        return
    }
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        val visible = groups.take(2)
        visible.forEach { label ->
            GroupTagChip(label = label.name, onClick = { onGroupClick(label.id) })
        }
        if (groups.size > 2) {
            Text(
                "+${groups.size - 2}",
                color = TextSecondary,
                fontSize = 10.sp
            )
        }
    }
}

@Composable
private fun GroupTagChip(label: String, onClick: () -> Unit) {
    Text(
        text = label,
        modifier = Modifier
            .clickable(onClick = onClick)
            .background(DarkBackground, RoundedCornerShape(8.dp))
            .border(1.dp, TableHeaderBg, RoundedCornerShape(8.dp))
            .padding(horizontal = 6.dp, vertical = 2.dp),
        color = TextSecondary,
        fontSize = 10.sp,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis
    )
}

@Composable
private fun WatchlistStrategyFilterBar(
    chips: List<daytrader.presentation.watchlist.WatchlistStrategyFilterChipUi>,
    onFilterSelected: (WatchlistStrategyFilter) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .testTag("WatchlistStrategyFilterBar"),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        chips.forEach { chip ->
            WatchlistFilterChip(
                label = chip.label,
                selected = chip.selected,
                onClick = { onFilterSelected(chip.filter) }
            )
        }
    }
}

@Composable
private fun WatchlistRowStrategies(
    strategies: List<WatchlistStrategyUi>,
    modifier: Modifier = Modifier,
    onStrategyClick: (StrategyType) -> Unit
) {
    val uniqueStrategies = strategies.distinctBy { it.strategyType }
    if (uniqueStrategies.isEmpty()) {
        Text(
            "—",
            modifier = modifier,
            color = TextSecondary.copy(alpha = 0.5f),
            fontSize = 12.sp
        )
        return
    }
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        uniqueStrategies.take(2).forEach { strategy ->
            StrategyTagChip(label = strategy.label, onClick = { onStrategyClick(strategy.strategyType) })
        }
        if (uniqueStrategies.size > 2) {
            Text("+${uniqueStrategies.size - 2}", color = TextSecondary, fontSize = 10.sp)
        }
    }
}

@Composable
private fun StrategyTagChip(label: String, onClick: () -> Unit) {
    Text(
        text = label,
        modifier = Modifier
            .clickable(onClick = onClick)
            .background(GainGreen.copy(alpha = 0.12f), RoundedCornerShape(8.dp))
            .border(1.dp, GainGreen.copy(alpha = 0.35f), RoundedCornerShape(8.dp))
            .padding(horizontal = 6.dp, vertical = 2.dp),
        color = GainGreen,
        fontSize = 10.sp,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis
    )
}
