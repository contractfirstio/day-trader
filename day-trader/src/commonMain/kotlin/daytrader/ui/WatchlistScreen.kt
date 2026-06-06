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

    uiState.tradePlansEditor?.let { editor ->
        WatchlistTradePlansDialog(
            editor = editor,
            onDismiss = viewModel::onDismissTradePlans,
            onSave = viewModel::onSaveTradePlans,
            onSideChange = viewModel::onUpdatePlanSide,
            onSizingModeChange = viewModel::onUpdatePlanSizingMode,
            onProximityEnabledChange = viewModel::onUpdatePlanProximityEnabled,
            onProximityModeChange = viewModel::onUpdatePlanProximityMode,
            onFieldChange = viewModel::onUpdatePlanField,
            onGroupInputChange = viewModel::onEditorGroupInputChange,
            onAddGroup = viewModel::onAddEditorGroup,
            onRemoveGroup = viewModel::onRemoveEditorGroup,
            onPlaceBracket = viewModel::onOpenBracketOrder,
            onReactivatePlan = viewModel::onReactivatePlan,
            onOpenDiary = viewModel::onOpenPlanDiary
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
            onSideChange = viewModel::onUpdateBracketOrderSide,
            onFieldChange = viewModel::onUpdateBracketOrderField,
            onSubmit = viewModel::onSubmitBracketOrder
        )
    }

    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                val countLabel = if (uiState.activeGroupFilter is WatchlistGroupFilter.All) {
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
                Text(
                    uiState.connectionLabel,
                    fontSize = 12.sp,
                    color = TextSecondary
                )
                Text(
                    "Prices refresh only when you run a scan (historical requests, no streaming lines).",
                    fontSize = 11.sp,
                    color = TextSecondary,
                    lineHeight = 14.sp
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = viewModel::onCheckEntryProximity,
                    enabled = !uiState.scanInProgress && uiState.totalEntryCount > 0,
                    colors = ButtonDefaults.buttonColors(containerColor = GainGreen),
                    modifier = Modifier.testTag("CheckEntryProximityButton")
                ) {
                    Icon(Icons.Default.Refresh, contentDescription = "Check entry proximity")
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(if (uiState.scanInProgress) "Scanning…" else "Check proximity")
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

        uiState.scanProgressLabel?.let { progress ->
            Spacer(modifier = Modifier.height(8.dp))
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            Text(progress, color = TextSecondary, fontSize = 12.sp, modifier = Modifier.padding(top = 4.dp))
        }

        uiState.scanSummary?.let { summary ->
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                summary,
                color = if (uiState.nearHits.isNotEmpty()) TradeBlueBorder else TextSecondary,
                fontSize = 13.sp,
                fontWeight = if (uiState.nearHits.isNotEmpty()) FontWeight.SemiBold else FontWeight.Normal
            )
        }

        Spacer(modifier = Modifier.height(8.dp))

        if (uiState.totalEntryCount > 0) {
            WatchlistGroupFilterBar(
                chips = uiState.groupFilterChips,
                onFilterSelected = viewModel::onGroupFilterSelected
            )
            Spacer(modifier = Modifier.height(8.dp))
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
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
                        text = "No symbols in this group.",
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
                            onRemove = { viewModel.onRemoveEntry(uiState.rows[index].entryId) },
                            onGroupClick = { labelId ->
                                viewModel.onGroupFilterSelected(WatchlistGroupFilter.Group(labelId))
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
    val borderColor = if (selected) BrandRed else TableHeaderBg
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
        WatchlistHeaderCell("Market", WatchlistSortColumn.SYMBOL, activeSortColumn, sortDirection, Modifier.weight(0.7f), onHeaderClick, sortable = false)
        WatchlistHeaderCell("Groups", WatchlistSortColumn.NOTES, activeSortColumn, sortDirection, Modifier.weight(1.0f), onHeaderClick, sortable = false)
        WatchlistHeaderCell("Last Price", WatchlistSortColumn.LAST, activeSortColumn, sortDirection, Modifier.weight(0.8f).padding(end = 12.dp), onHeaderClick, alignEnd = true)
        WatchlistHeaderCell("Last Price At", WatchlistSortColumn.LAST, activeSortColumn, sortDirection, Modifier.weight(1.3f).padding(start = 12.dp), onHeaderClick, sortable = false)
        WatchlistHeaderCell("Status", WatchlistSortColumn.NOTES, activeSortColumn, sortDirection, Modifier.weight(0.9f), onHeaderClick, sortable = false)
        WatchlistHeaderCell("Plans", WatchlistSortColumn.NOTES, activeSortColumn, sortDirection, Modifier.weight(1.1f), onHeaderClick, sortable = false)
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
    onRemove: () -> Unit,
    onGroupClick: (String) -> Unit
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
            modifier = Modifier.weight(1.0f),
            onGroupClick = onGroupClick
        )
        Text(
            row.formattedLast,
            modifier = Modifier.weight(0.8f).padding(end = 12.dp),
            color = Color.White,
            fontWeight = FontWeight.Medium,
            fontSize = 14.sp,
            textAlign = TextAlign.End
        )
        Text(
            row.lastPriceAtLabel.orEmpty(),
            modifier = Modifier.weight(1.3f).padding(start = 12.dp),
            color = TextSecondary,
            fontSize = 12.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        Text(
            row.proximityStatusLabel,
            modifier = Modifier.weight(0.9f),
            color = if (row.isNearEntry) TradeBlueBorder else TextSecondary,
            fontSize = 12.sp,
            fontWeight = if (row.isNearEntry) FontWeight.SemiBold else FontWeight.Normal
        )
        Text(
            row.nearEntrySummary ?: row.planSummary.orEmpty(),
            modifier = Modifier.weight(1.1f),
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
