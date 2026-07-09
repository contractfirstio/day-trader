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
import daytrader.presentation.trades.TradeFilterSummaryUi
import daytrader.presentation.trades.TradeRowUi
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
                    "Filter by trade date and symbol. Trades are saved locally; live executions persist automatically; Flex sync backfills history.",
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
            filterSymbol = uiState.filterSymbol,
            availableSymbols = uiState.availableSymbols,
            summary = uiState.filterSummary,
            onFromDateChanged = viewModel::onFilterFromDateChanged,
            onToDateChanged = viewModel::onFilterToDateChanged,
            onPresetSelected = viewModel::onDatePresetSelected,
            onSymbolFilterSelected = viewModel::onSymbolFilterSelected,
        )

        Spacer(modifier = Modifier.height(8.dp))

        Column(
            modifier = Modifier
                .fillMaxSize()
                .border(1.dp, SurfaceDark, RoundedCornerShape(8.dp))
                .background(SurfaceDark, RoundedCornerShape(8.dp))
        ) {
            TradesTableHeader(
                activeSortColumn = uiState.sortColumn,
                sortDirection = uiState.sortDirection,
                onHeaderClick = viewModel::onHeaderClick
            )

            if (uiState.rows.isEmpty()) {
                Box(
                    modifier = Modifier.fillMaxSize().padding(24.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = emptyTradesMessage(
                            connectionState = connectionState,
                            brokerKind = brokerKind,
                            filterFromDate = uiState.filterFromDate,
                            filterToDate = uiState.filterToDate,
                            filterSymbol = uiState.filterSymbol,
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
    filterSymbol: String?,
): String {
    val rangeLabel = when {
        filterFromDate.isNotBlank() && filterToDate.isNotBlank() -> "$filterFromDate to $filterToDate"
        filterFromDate.isNotBlank() -> "from $filterFromDate"
        filterToDate.isNotBlank() -> "through $filterToDate"
        else -> "all stored dates"
    }
    val symbolLabel = filterSymbol?.let { " for $it" }.orEmpty()
    return when (connectionState) {
        GatewayConnectionState.Connected ->
            "No trades$symbolLabel for $rangeLabel from ${brokerKind.displayName.lowercase()}."
        GatewayConnectionState.Connecting ->
            "Loading executions…"
        is GatewayConnectionState.Error ->
            "Trades unavailable — fix broker connection and reconnect."
        GatewayConnectionState.Disconnected ->
            if (filterFromDate.isBlank() && filterToDate.isBlank() && filterSymbol.isNullOrBlank()) {
                "No stored trades yet."
            } else {
                "No trades$symbolLabel for $rangeLabel."
            }
    }
}

@Composable
private fun TradesDateFilterBar(
    fromDate: String,
    toDate: String,
    activePreset: TradeDatePreset?,
    filterSymbol: String?,
    availableSymbols: List<String>,
    summary: TradeFilterSummaryUi?,
    onFromDateChanged: (String) -> Unit,
    onToDateChanged: (String) -> Unit,
    onPresetSelected: (TradeDatePreset) -> Unit,
    onSymbolFilterSelected: (String?) -> Unit,
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
        if (availableSymbols.size > 1) {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("Symbol", color = TextSecondary, fontSize = 12.sp)
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    TradesSymbolFilterChip(
                        label = "All",
                        selected = filterSymbol.isNullOrBlank(),
                        onClick = { onSymbolFilterSelected(null) },
                    )
                    availableSymbols.forEach { symbol ->
                        TradesSymbolFilterChip(
                            label = symbol,
                            selected = filterSymbol == symbol,
                            onClick = { onSymbolFilterSelected(symbol) },
                        )
                    }
                }
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
        }
    }
}

@Composable
private fun TradesSymbolFilterChip(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    FilterChip(
        selected = selected,
        onClick = onClick,
        label = { Text(label, fontSize = 12.sp) },
        modifier = Modifier.testTag("TradesSymbolFilter-$label"),
        colors = FilterChipDefaults.filterChipColors(
            selectedContainerColor = DarkBackground,
            selectedLabelColor = Color.White,
            containerColor = DarkBackground.copy(alpha = 0.5f),
            labelColor = TextSecondary
        )
    )
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
    onHeaderClick: (TradeSortColumn) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(TableHeaderBg, RoundedCornerShape(topStart = 8.dp, topEnd = 8.dp))
            .padding(horizontal = 12.dp, vertical = 12.dp)
            .testTag("TradesTableHeaderRow"),
        verticalAlignment = Alignment.CenterVertically
    ) {
        TradesHeaderCell("Date", TradeSortColumn.TIME, activeSortColumn, sortDirection, Modifier.weight(1.1f), onClick = onHeaderClick)
        TradesHeaderCell("Symbol", TradeSortColumn.SYMBOL, activeSortColumn, sortDirection, Modifier.weight(0.8f), onClick = onHeaderClick)
        TradesHeaderCell("Side", TradeSortColumn.SIDE, activeSortColumn, sortDirection, Modifier.weight(0.55f), onClick = onHeaderClick)
        TradesHeaderCell("Qty", TradeSortColumn.QUANTITY, activeSortColumn, sortDirection, Modifier.weight(0.45f), onClick = onHeaderClick)
        TradesHeaderCell("Price", TradeSortColumn.PRICE, activeSortColumn, sortDirection, Modifier.weight(0.75f), onClick = onHeaderClick)
        TradesHeaderCell("Commission", TradeSortColumn.COMMISSION, activeSortColumn, sortDirection, Modifier.weight(0.8f), onClick = onHeaderClick)
        TradesHeaderCell("Realized P&L", TradeSortColumn.REALIZED_PNL, activeSortColumn, sortDirection, Modifier.weight(0.9f), alignEnd = true, onClick = onHeaderClick)
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
        Text(row.formattedTime, Modifier.weight(1.1f), color = TextSecondary, fontSize = 13.sp, maxLines = 1)
        Text(row.symbol, Modifier.weight(0.8f), color = Color.White, fontWeight = FontWeight.Medium, fontSize = 13.sp, maxLines = 1)
        Text(
            row.sideLabel,
            Modifier.weight(0.55f),
            color = if (row.isBuySide) GainGreen else LossRed,
            fontSize = 13.sp,
            maxLines = 1
        )
        Text(row.quantityLabel, Modifier.weight(0.45f), color = Color.White, fontSize = 13.sp, maxLines = 1)
        Text(row.formattedPrice, Modifier.weight(0.75f), color = Color.White, fontSize = 13.sp, maxLines = 1)
        Text(row.formattedCommission ?: "—", Modifier.weight(0.8f), color = TextSecondary, fontSize = 13.sp, maxLines = 1)
        Text(
            text = row.formattedRealizedPnL ?: "—",
            modifier = Modifier.weight(0.9f),
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
