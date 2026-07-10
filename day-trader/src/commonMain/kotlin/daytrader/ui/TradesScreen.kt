package daytrader.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
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
import daytrader.gateway.BrokerKind
import daytrader.gateway.GatewayConnectionState
import daytrader.presentation.positions.SortDirection
import daytrader.presentation.trades.TradeDatePreset
import daytrader.presentation.trades.TradeFilterColumn
import daytrader.presentation.trades.TradeFilterSummaryUi
import daytrader.presentation.trades.TradeRowUi
import daytrader.presentation.trades.TradeSetFilterUi
import daytrader.presentation.trades.TradeSortColumn
import daytrader.presentation.trades.TradesViewModel
import daytrader.ui.theme.BrandRed
import daytrader.ui.theme.DarkBackground
import daytrader.ui.theme.GainGreen
import daytrader.ui.theme.LossRed
import daytrader.ui.theme.SurfaceDark
import daytrader.ui.theme.TableHeaderBg
import daytrader.ui.theme.TextSecondary

@Composable
fun TradesScreen(
    viewModel: TradesViewModel,
    connectionState: GatewayConnectionState,
    brokerKind: BrokerKind,
    onOpenIbSettings: (() -> Unit)? = null,
) {
    val uiState by viewModel.uiState.collectAsState()

    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    if (uiState.totalStoredCount == uiState.totalFillCount) {
                        "Trades (${uiState.totalFillCount})"
                    } else {
                        "Trades (${uiState.totalFillCount} of ${uiState.totalStoredCount} stored)"
                    },
                    fontSize = 18.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = Color.White
                )
                Text(
                    tradesSourceLabel(brokerKind),
                    fontSize = 12.sp,
                    color = TextSecondary
                )
                Text(
                    "Filter by trade date. Use column filters on Symbol, Market, and Side for spreadsheet-style selection.",
                    fontSize = 11.sp,
                    color = TextSecondary,
                    lineHeight = 14.sp,
                    modifier = Modifier.padding(top = 4.dp)
                )
            }
            if (brokerKind != BrokerKind.REPLAY) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (onOpenIbSettings != null && brokerKind.usesLiveIbMarketData) {
                        Button(
                            onClick = onOpenIbSettings,
                            colors = ButtonDefaults.buttonColors(
                                containerColor = DarkBackground,
                                contentColor = TextSecondary
                            ),
                            modifier = Modifier.testTag("TradesIbSettingsButton")
                        ) {
                            Text("IB Settings…", fontSize = 12.sp)
                        }
                    }
                    Button(
                        onClick = viewModel::onSyncClick,
                        enabled = uiState.canSync && !uiState.isSyncing,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = SurfaceDark,
                            contentColor = Color.White,
                            disabledContainerColor = SurfaceDark.copy(alpha = 0.6f),
                            disabledContentColor = TextSecondary
                        ),
                        modifier = Modifier.testTag("TradesSyncButton")
                    ) {
                    if (uiState.isSyncing) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(16.dp),
                            strokeWidth = 2.dp,
                            color = GainGreen
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                    } else {
                        Icon(
                            imageVector = Icons.Default.Sync,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                    }
                    Text(syncButtonLabel(brokerKind), fontSize = 12.sp)
                }
                }
            }
        }
        uiState.syncMessage?.let { message ->
            Text(
                message,
                fontSize = 12.sp,
                color = if (message.startsWith("No trades")) {
                    MaterialTheme.colorScheme.error
                } else {
                    GainGreen
                },
                modifier = Modifier.padding(top = 4.dp)
            )
        }

        Spacer(modifier = Modifier.height(8.dp))

        TradesDateFilterBar(
            fromDate = uiState.filterFromDate,
            toDate = uiState.filterToDate,
            activePreset = uiState.activeDatePreset,
            summary = uiState.filterSummary,
            onFromDateChanged = viewModel::onFilterFromDateChanged,
            onToDateChanged = viewModel::onFilterToDateChanged,
            onPresetSelected = viewModel::onDatePresetSelected,
        )

        Spacer(modifier = Modifier.height(8.dp))

        var openFilterColumn by remember { mutableStateOf<TradeFilterColumn?>(null) }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .border(1.dp, SurfaceDark, RoundedCornerShape(8.dp))
                .background(SurfaceDark, RoundedCornerShape(8.dp))
        ) {
            TradesTableHeader(
                activeSortColumn = uiState.sortColumn,
                sortDirection = uiState.sortDirection,
                dateFilter = uiState.dateFilter,
                symbolFilter = uiState.symbolFilter,
                marketFilter = uiState.marketFilter,
                sideFilter = uiState.sideFilter,
                openFilterColumn = openFilterColumn,
                onHeaderClick = viewModel::onHeaderClick,
                onFilterClick = { column ->
                    openFilterColumn = if (openFilterColumn == column) null else column
                },
                onFilterDismiss = { openFilterColumn = null },
                onFilterSelectAll = viewModel::onColumnFilterSelectAll,
                onFilterOptionToggled = viewModel::onColumnFilterToggled,
            )

            if (uiState.rows.isEmpty()) {
                Box(
                    modifier = Modifier.fillMaxSize().padding(24.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text =                         emptyTradesMessage(
                            connectionState = connectionState,
                            brokerKind = brokerKind,
                            filterFromDate = uiState.filterFromDate,
                            filterToDate = uiState.filterToDate,
                            hasColumnFilters = uiState.symbolFilter?.isActive == true ||
                                uiState.marketFilter?.isActive == true ||
                                uiState.sideFilter?.isActive == true,
                        ),
                        color = TextSecondary,
                        fontSize = 14.sp,
                        textAlign = TextAlign.Center
                    )
                }
            } else {
                LazyColumn(modifier = Modifier.fillMaxSize()) {
                    items(
                        count = uiState.rows.size,
                        key = { index -> uiState.rows[index].execId }
                    ) { index ->
                        TradesTableRow(row = uiState.rows[index])
                        if (index < uiState.rows.size - 1) {
                            HorizontalDivider(color = DarkBackground, thickness = 1.dp)
                        }
                    }
                }
            }
        }
    }
}

private fun tradesSourceLabel(brokerKind: BrokerKind): String = when (brokerKind) {
    BrokerKind.INTERACTIVE_BROKERS -> "Settled trades from IB Flex; today's executions stream live from TWS"
    BrokerKind.EMULATOR_LIVE_IB_MARKET_DATA -> "Settled trades from IB Flex; paper execution uses the emulator"
    BrokerKind.EMULATOR -> "Simulated trades from the broker emulator"
    BrokerKind.REPLAY -> "Recorded trades during session replay"
}

private fun syncButtonLabel(brokerKind: BrokerKind): String = when (brokerKind) {
    BrokerKind.INTERACTIVE_BROKERS, BrokerKind.EMULATOR_LIVE_IB_MARKET_DATA -> "Sync from IB"
    else -> "Refresh trades"
}

private fun emptyTradesMessage(
    connectionState: GatewayConnectionState,
    brokerKind: BrokerKind,
    filterFromDate: String,
    filterToDate: String,
    hasColumnFilters: Boolean,
): String {
    val rangeLabel = when {
        filterFromDate.isNotBlank() && filterToDate.isNotBlank() -> "$filterFromDate to $filterToDate"
        filterFromDate.isNotBlank() -> "from $filterFromDate"
        filterToDate.isNotBlank() -> "through $filterToDate"
        else -> "all stored dates"
    }
    val filterLabel = if (hasColumnFilters) " with the current column filters" else ""
    return when (connectionState) {
        GatewayConnectionState.Connected ->
            "No trades$filterLabel for $rangeLabel from ${brokerKind.displayName.lowercase()}."
        GatewayConnectionState.Connecting ->
            "Loading executions…"
        is GatewayConnectionState.Error ->
            "Trades unavailable — fix broker connection and reconnect."
        GatewayConnectionState.Disconnected ->
            if (filterFromDate.isBlank() && filterToDate.isBlank() && !hasColumnFilters) {
                "No stored trades yet."
            } else {
                "No trades$filterLabel for $rangeLabel."
            }
    }
}

@Composable
private fun TradesDateFilterBar(
    fromDate: String,
    toDate: String,
    activePreset: TradeDatePreset?,
    summary: TradeFilterSummaryUi?,
    onFromDateChanged: (String) -> Unit,
    onToDateChanged: (String) -> Unit,
    onPresetSelected: (TradeDatePreset) -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .border(1.dp, SurfaceDark, RoundedCornerShape(8.dp))
            .background(SurfaceDark, RoundedCornerShape(8.dp))
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            TradesDateField(
                label = "From",
                value = fromDate,
                onValueChange = onFromDateChanged,
                modifier = Modifier.weight(1f)
            )
            TradesDateField(
                label = "To",
                value = toDate,
                onValueChange = onToDateChanged,
                modifier = Modifier.weight(1f)
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TradeDatePreset.entries.forEach { preset ->
                FilterChip(
                    selected = activePreset == preset,
                    onClick = { onPresetSelected(preset) },
                    label = { Text(preset.label, fontSize = 12.sp) },
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = DarkBackground,
                        selectedLabelColor = Color.White,
                        containerColor = DarkBackground.copy(alpha = 0.5f),
                        labelColor = TextSecondary
                    )
                )
            }
        }
        summary?.let {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(it.tradeCountLabel, color = TextSecondary, fontSize = 12.sp)
                Text(
                    "Realized P&L ${it.formattedRealizedPnL}",
                    color = when (it.isPositiveRealizedPnL) {
                        true -> GainGreen
                        false -> LossRed
                        null -> TextSecondary
                    },
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    "Commission ${it.formattedCommission}",
                    color = TextSecondary,
                    fontSize = 12.sp
                )
            }
            it.pnlCurrencyNote?.let { note ->
                Text(
                    note,
                    color = TextSecondary,
                    fontSize = 11.sp,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
}

@Composable
private fun TradesDateField(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label) },
        placeholder = { Text("yyyy-MM-dd") },
        singleLine = true,
        modifier = modifier.testTag("TradesFilter$label"),
        colors = OutlinedTextFieldDefaults.colors(
            focusedTextColor = Color.White,
            unfocusedTextColor = Color.White,
            focusedBorderColor = BrandRed,
            unfocusedBorderColor = TextSecondary,
            focusedLabelColor = TextSecondary,
            unfocusedLabelColor = TextSecondary,
            cursorColor = BrandRed
        )
    )
}

private val TradeDatePreset.label: String
    get() = when (this) {
        TradeDatePreset.SEVEN_DAYS -> "7d"
        TradeDatePreset.THIRTY_DAYS -> "30d"
        TradeDatePreset.NINETY_DAYS -> "90d"
        TradeDatePreset.ALL -> "All"
    }

@Composable
private fun TradesTableHeader(
    activeSortColumn: TradeSortColumn,
    sortDirection: SortDirection,
    dateFilter: TradeSetFilterUi?,
    symbolFilter: TradeSetFilterUi?,
    marketFilter: TradeSetFilterUi?,
    sideFilter: TradeSetFilterUi?,
    openFilterColumn: TradeFilterColumn?,
    onHeaderClick: (TradeSortColumn) -> Unit,
    onFilterClick: (TradeFilterColumn) -> Unit,
    onFilterDismiss: () -> Unit,
    onFilterSelectAll: (TradeFilterColumn, Boolean) -> Unit,
    onFilterOptionToggled: (TradeFilterColumn, String) -> Unit,
) {
    Box(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(TableHeaderBg, RoundedCornerShape(topStart = 8.dp, topEnd = 8.dp))
                .padding(horizontal = 12.dp, vertical = 12.dp)
                .testTag("TradesTableHeaderRow"),
            verticalAlignment = Alignment.CenterVertically
        ) {
            TradesFilterableHeaderCell(
                label = "Date",
                filter = dateFilter,
                expanded = openFilterColumn == TradeFilterColumn.DATE,
                onSortClick = { onHeaderClick(TradeSortColumn.TIME) },
                onFilterClick = { onFilterClick(TradeFilterColumn.DATE) },
                onFilterDismiss = onFilterDismiss,
                onFilterSelectAll = { selected -> onFilterSelectAll(TradeFilterColumn.DATE, selected) },
                onFilterOptionToggled = { value -> onFilterOptionToggled(TradeFilterColumn.DATE, value) },
                modifier = Modifier.weight(1.0f),
                sortActive = activeSortColumn == TradeSortColumn.TIME,
                sortAscending = sortDirection == SortDirection.ASCENDING,
            )
            TradesFilterableHeaderCell(
                label = "Symbol",
                filter = symbolFilter,
                expanded = openFilterColumn == TradeFilterColumn.SYMBOL,
                onSortClick = { onHeaderClick(TradeSortColumn.SYMBOL) },
                onFilterClick = { onFilterClick(TradeFilterColumn.SYMBOL) },
                onFilterDismiss = onFilterDismiss,
                onFilterSelectAll = { selected -> onFilterSelectAll(TradeFilterColumn.SYMBOL, selected) },
                onFilterOptionToggled = { value -> onFilterOptionToggled(TradeFilterColumn.SYMBOL, value) },
                modifier = Modifier.weight(0.75f),
                sortActive = activeSortColumn == TradeSortColumn.SYMBOL,
                sortAscending = sortDirection == SortDirection.ASCENDING,
            )
            TradesFilterableHeaderCell(
                label = "Market",
                filter = marketFilter,
                expanded = openFilterColumn == TradeFilterColumn.MARKET,
                onSortClick = { onHeaderClick(TradeSortColumn.MARKET) },
                onFilterClick = { onFilterClick(TradeFilterColumn.MARKET) },
                onFilterDismiss = onFilterDismiss,
                onFilterSelectAll = { selected -> onFilterSelectAll(TradeFilterColumn.MARKET, selected) },
                onFilterOptionToggled = { value -> onFilterOptionToggled(TradeFilterColumn.MARKET, value) },
                modifier = Modifier.weight(0.55f),
                sortActive = activeSortColumn == TradeSortColumn.MARKET,
                sortAscending = sortDirection == SortDirection.ASCENDING,
            )
            TradesFilterableHeaderCell(
                label = "Side",
                filter = sideFilter,
                expanded = openFilterColumn == TradeFilterColumn.SIDE,
                onSortClick = { onHeaderClick(TradeSortColumn.SIDE) },
                onFilterClick = { onFilterClick(TradeFilterColumn.SIDE) },
                onFilterDismiss = onFilterDismiss,
                onFilterSelectAll = { selected -> onFilterSelectAll(TradeFilterColumn.SIDE, selected) },
                onFilterOptionToggled = { value -> onFilterOptionToggled(TradeFilterColumn.SIDE, value) },
                modifier = Modifier.weight(0.55f),
                sortActive = activeSortColumn == TradeSortColumn.SIDE,
                sortAscending = sortDirection == SortDirection.ASCENDING,
            )
            TradesHeaderCell(
                label = "Qty",
                column = TradeSortColumn.QUANTITY,
                activeColumn = activeSortColumn,
                direction = sortDirection,
                modifier = Modifier.weight(0.45f),
                onClick = onHeaderClick
            )
            TradesHeaderCell(
                label = "Price",
                column = TradeSortColumn.PRICE,
                activeColumn = activeSortColumn,
                direction = sortDirection,
                modifier = Modifier.weight(0.7f),
                onClick = onHeaderClick
            )
            TradesHeaderCell(
                label = "Commission",
                column = TradeSortColumn.COMMISSION,
                activeColumn = activeSortColumn,
                direction = sortDirection,
                modifier = Modifier.weight(0.75f),
                onClick = onHeaderClick
            )
            TradesHeaderCell(
                label = "Realized P&L",
                column = TradeSortColumn.REALIZED_PNL,
                activeColumn = activeSortColumn,
                direction = sortDirection,
                modifier = Modifier.weight(0.85f),
                alignEnd = true,
                onClick = onHeaderClick
            )
        }
    }
}

@Composable
private fun RowScope.TradesHeaderCell(
    label: String,
    column: TradeSortColumn,
    activeColumn: TradeSortColumn,
    direction: SortDirection,
    modifier: Modifier = Modifier,
    alignEnd: Boolean = false,
    onClick: (TradeSortColumn) -> Unit
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
            fontSize = 12.sp,
            textAlign = if (alignEnd) TextAlign.End else TextAlign.Start
        )
        if (isActive) {
            Spacer(modifier = Modifier.width(4.dp))
            Icon(
                imageVector = if (direction == SortDirection.ASCENDING) Icons.Default.ArrowUpward else Icons.Default.ArrowDownward,
                contentDescription = "Sorted direction",
                tint = GainGreen,
                modifier = Modifier.size(12.dp)
            )
        }
    }
}

@Composable
private fun TradesTableRow(row: TradeRowUi) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 12.dp)
            .testTag("TradesTableRow-${row.execId}"),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(row.formattedTime, Modifier.weight(1.0f), color = TextSecondary, fontSize = 13.sp, maxLines = 1)
        Text(row.symbol, Modifier.weight(0.75f), color = Color.White, fontWeight = FontWeight.Medium, fontSize = 13.sp, maxLines = 1)
        Text(row.marketLabel, Modifier.weight(0.55f), color = TextSecondary, fontSize = 13.sp, maxLines = 1)
        Text(
            row.sideLabel,
            Modifier.weight(0.55f),
            color = if (row.isBuySide) GainGreen else LossRed,
            fontSize = 13.sp,
            maxLines = 1
        )
        Text(row.quantityLabel, Modifier.weight(0.45f), color = Color.White, fontSize = 13.sp, maxLines = 1)
        Text(row.formattedPrice, Modifier.weight(0.7f), color = Color.White, fontSize = 13.sp, maxLines = 1)
        Text(row.formattedCommission ?: "—", Modifier.weight(0.75f), color = TextSecondary, fontSize = 13.sp, maxLines = 1)
        Text(
            text = row.formattedRealizedPnL ?: "—",
            modifier = Modifier.weight(0.85f),
            color = when (row.isPositiveRealizedPnL) {
                true -> GainGreen
                false -> LossRed
                null -> TextSecondary
            },
            fontSize = 13.sp,
            maxLines = 1,
            textAlign = TextAlign.End
        )
    }
}
