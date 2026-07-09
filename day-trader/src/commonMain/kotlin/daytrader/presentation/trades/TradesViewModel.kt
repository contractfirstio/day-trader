package daytrader.presentation.trades

import daytrader.broker.SymbolMarkets
import daytrader.data.FillsRepository
import daytrader.data.HistoricalTradeSync
import daytrader.diagnostics.TimestampedConsoleLog
import daytrader.gateway.BrokerGateway
import daytrader.gateway.BrokerKind
import daytrader.gateway.BrokerFill
import daytrader.gateway.GatewayConnectionState
import daytrader.platform.TradingClock
import daytrader.platform.WallClock
import daytrader.presentation.Formatters
import daytrader.presentation.navigation.AppScreen
import daytrader.presentation.positions.SortDirection
import daytrader.presentation.ui.UiCoroutineScopes
import daytrader.presentation.ui.launchUiAction
import daytrader.presentation.ui.safeUiEmit
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.update

class TradesViewModel(
    private val repository: FillsRepository,
    private val executionGateway: BrokerGateway? = null,
    private val historicalTradeSync: HistoricalTradeSync? = null,
    private val brokerKind: BrokerKind = BrokerKind.EMULATOR,
    private val tradingClock: TradingClock = WallClock,
    private val syncTimeoutMs: Long = SYNC_TIMEOUT_MS,
    scope: CoroutineScope = UiCoroutineScopes.forScreen(AppScreen.TRADES, "TradesViewModel"),
) {
    private val scope = scope

    private var fills: List<BrokerFill> = emptyList()
    private var sortColumn = TradeSortColumn.TIME
    private var sortDirection = SortDirection.DESCENDING
    private var dateRange = defaultDateRange(tradingClock)
    private var activeDatePreset: TradeDatePreset? = TradeDatePreset.THIRTY_DAYS
    private var filterSymbol: String? = null
    private var connectionState: GatewayConnectionState = GatewayConnectionState.Disconnected
    private var isSyncing = false
    private var syncMessage: String? = null

    private val _uiState = MutableStateFlow(TradesUiState())
    val uiState: StateFlow<TradesUiState> = _uiState.asStateFlow()

    init {
        repository.fills
            .onEach { list ->
                fills = list
                isSyncing = false
                emitUiState()
            }
            .launchIn(scope)

        executionGateway?.connectionState
            ?.onEach { state ->
                connectionState = state
                if (state != GatewayConnectionState.Connected) {
                    isSyncing = false
                }
                emitUiState()
            }
            ?.launchIn(scope)
    }

    fun onHeaderClick(column: TradeSortColumn) {
        if (sortColumn == column) {
            sortDirection = if (sortDirection == SortDirection.ASCENDING) {
                SortDirection.DESCENDING
            } else {
                SortDirection.ASCENDING
            }
        } else {
            sortColumn = column
            sortDirection = when (column) {
                TradeSortColumn.TIME -> SortDirection.DESCENDING
                else -> SortDirection.ASCENDING
            }
        }
        emitUiState()
    }

    fun onFilterFromDateChanged(value: String) {
        activeDatePreset = null
        dateRange = dateRange.copy(from = parseFilterDate(value))
        emitUiState()
    }

    fun onFilterToDateChanged(value: String) {
        activeDatePreset = null
        dateRange = dateRange.copy(to = parseFilterDate(value))
        emitUiState()
    }

    fun onDatePresetSelected(preset: TradeDatePreset) {
        activeDatePreset = preset
        dateRange = dateRangeForPreset(preset, tradingClock)
        emitUiState()
    }

    fun onSymbolFilterSelected(symbol: String?) {
        filterSymbol = symbol?.takeIf { it.isNotBlank() }
        emitUiState()
    }

    fun onSyncClick() {
        if (!canSync()) {
            syncMessage = syncUnavailableMessage()
            emitUiState()
            return
        }
        if (isSyncing) return
        val countBefore = fills.size
        isSyncing = true
        syncMessage = null
        emitUiState()
        scope.launchUiAction(AppScreen.TRADES, "onSyncClick") {
            var flexAdded = 0
            var flexRefreshed = 0
            var flexError: String? = null
            historicalTradeSync?.let { sync ->
                sync.fetchTrades()
                    .onSuccess { trades ->
                        val result = repository.mergeFlexFills(trades)
                        flexAdded = result.added
                        flexRefreshed = trades.size
                        repository.flushPersistenceBlocking()
                    }
                    .onFailure { error ->
                        flexError = error.message ?: error.toString()
                        TimestampedConsoleLog.line("TradesSync", "Flex failed: $flexError")
                    }
            }

            val gateway = executionGateway
            if (gateway != null && connectionState == GatewayConnectionState.Connected) {
                gateway.refreshFills()
                val deadline = System.currentTimeMillis() + syncTimeoutMs
                while (System.currentTimeMillis() < deadline) {
                    delay(SYNC_POLL_MS)
                    if (fills.size != countBefore + flexAdded) break
                }
            }

            isSyncing = false
            syncMessage = syncResultMessage(
                countBefore = countBefore,
                countAfter = fills.size,
                flexAdded = flexAdded,
                flexRefreshed = flexRefreshed,
                flexError = flexError,
            )
            emitUiState()
        }
    }

    private fun syncResultMessage(
        countBefore: Int,
        countAfter: Int,
        flexAdded: Int,
        flexRefreshed: Int,
        flexError: String?,
    ): String {
        flexError?.let { return "Flex trade sync failed: $it" }
        return when {
            countAfter == 0 && historicalTradeSync == null && usesIbTradeHistory() ->
                "No trades stored yet. Open IB Settings and add Flex token + trades query ID (live account only), then sync."
            countAfter == 0 ->
                "No trades stored yet."
            flexRefreshed > 0 && countAfter == countBefore ->
                "Refreshed $flexRefreshed Flex trade(s) from IB — $countAfter stored in total."
            countAfter > countBefore ->
                buildString {
                    append("Added ${countAfter - countBefore} trade(s)")
                    if (flexAdded > 0) append(" ($flexAdded from Flex)")
                    append(" — $countAfter stored in total.")
                }
            flexRefreshed > 0 ->
                "Refreshed $flexRefreshed Flex trade(s) — $countAfter stored in total."
            else ->
                "Ledger up to date — $countAfter trade(s) stored."
        }
    }

    private fun canSync(): Boolean = when {
        brokerKind == BrokerKind.REPLAY -> false
        historicalTradeSync != null -> true
        executionGateway != null && connectionState == GatewayConnectionState.Connected -> true
        brokerKind == BrokerKind.EMULATOR -> executionGateway != null
        else -> false
    }

    private fun usesIbTradeHistory(): Boolean =
        brokerKind == BrokerKind.INTERACTIVE_BROKERS ||
            brokerKind == BrokerKind.EMULATOR_LIVE_IB_MARKET_DATA

    private fun syncUnavailableMessage(): String = when {
        brokerKind == BrokerKind.REPLAY ->
            "Trade sync is not available during session replay."
        usesIbTradeHistory() && historicalTradeSync == null ->
            "Open IB Settings (or menu Settings → Interactive Brokers) and add Flex token + query ID. Flex requires a live IB account."
        connectionState != GatewayConnectionState.Connected ->
            "Connect to your broker to sync today's executions."
        else -> "Trade sync is unavailable."
    }

    private fun filteredFills(): List<BrokerFill> =
        TradeLedgerFilter.filter(fills, dateRange, filterSymbol)

    private fun dateFilteredFills(): List<BrokerFill> =
        TradeLedgerFilter.filterByDate(fills, dateRange)

    private fun normalizeSymbolFilter(availableSymbols: List<String>) {
        val selected = filterSymbol ?: return
        if (availableSymbols.none { SymbolMarkets.symbolsMatch(selected, it) }) {
            filterSymbol = null
        }
    }

    private fun emitUiState() {
        safeUiEmit(AppScreen.TRADES, "emitUiState") {
            val availableSymbols = TradeLedgerFilter.distinctSymbols(dateFilteredFills())
            normalizeSymbolFilter(availableSymbols)
            val filtered = filteredFills()
            val summary = TradeLedgerFilter.summarize(filtered)
            val comparator = when (sortColumn) {
                TradeSortColumn.TIME -> compareBy<BrokerFill> { TradeUiMapper.parseFillDate(it.time) ?: java.time.LocalDate.MIN }
                    .thenBy { it.execId }
                TradeSortColumn.SYMBOL -> compareBy<BrokerFill> { it.symbol }
                    .thenBy { TradeUiMapper.parseFillTime(it.time) ?: LocalDateTime.MIN }
                TradeSortColumn.SIDE -> compareBy<BrokerFill> { it.side }
                    .thenBy { it.symbol }
                TradeSortColumn.QUANTITY -> compareBy<BrokerFill> { it.quantity }
                    .thenBy { it.symbol }
                TradeSortColumn.PRICE -> compareBy<BrokerFill> { it.price }
                    .thenBy { it.symbol }
                TradeSortColumn.COMMISSION -> compareBy<BrokerFill> { it.commission ?: Double.NEGATIVE_INFINITY }
                    .thenBy { it.symbol }
                TradeSortColumn.REALIZED_PNL -> compareBy<BrokerFill> { it.realizedPnL ?: Double.NEGATIVE_INFINITY }
                    .thenBy { it.symbol }
            }

            val sorted = if (sortDirection == SortDirection.DESCENDING) {
                filtered.sortedWith(comparator.reversed())
            } else {
                filtered.sortedWith(comparator)
            }

            _uiState.update {
                TradesUiState(
                    rows = sorted.map(TradeUiMapper::toRowUi),
                    totalFillCount = filtered.size,
                    totalStoredCount = fills.size,
                    filterFromDate = formatFilterDate(dateRange.from),
                    filterToDate = formatFilterDate(dateRange.to),
                    activeDatePreset = activeDatePreset,
                    filterSymbol = filterSymbol,
                    availableSymbols = availableSymbols,
                    filterSummary = toFilterSummaryUi(summary, filterSymbol),
                    sortColumn = sortColumn,
                    sortDirection = sortDirection,
                    canSync = canSync(),
                    isSyncing = isSyncing,
                    syncMessage = syncMessage
                )
            }
        }
    }

    companion object {
        const val RECENT_TRADES_DAYS = 30L
        private val FILTER_DATE_FORMAT = DateTimeFormatter.ISO_LOCAL_DATE
        private const val SYNC_TIMEOUT_MS = 8_000L
        private const val SYNC_POLL_MS = 200L

        internal fun defaultDateRange(clock: TradingClock): TradeDateRange =
            dateRangeForPreset(TradeDatePreset.THIRTY_DAYS, clock)

        internal fun dateRangeForPreset(preset: TradeDatePreset, clock: TradingClock): TradeDateRange {
            val today = today(clock)
            return when (preset) {
                TradeDatePreset.SEVEN_DAYS -> TradeDateRange(from = today.minusDays(6), to = today)
                TradeDatePreset.THIRTY_DAYS -> TradeDateRange(from = today.minusDays(29), to = today)
                TradeDatePreset.NINETY_DAYS -> TradeDateRange(from = today.minusDays(89), to = today)
                TradeDatePreset.ALL -> TradeDateRange()
            }
        }

        internal fun toFilterSummaryUi(
            summary: TradeLedgerSummary,
            filterSymbol: String? = null,
        ): TradeFilterSummaryUi {
            val currency = summary.currencies.singleOrNull().orEmpty().ifBlank { "USD" }
            val mixedCurrency = summary.currencies.size > 1
            val pnl = summary.realizedPnL
            val commission = summary.commission
            val symbolSuffix = filterSymbol?.let { " · $it" }.orEmpty()
            return TradeFilterSummaryUi(
                tradeCountLabel = "${summary.tradeCount} trade${if (summary.tradeCount == 1) "" else "s"}$symbolSuffix",
                formattedRealizedPnL = when {
                    pnl == null -> "—"
                    mixedCurrency -> "${Formatters.money(pnl, currency, showSign = true)} (mixed)"
                    else -> Formatters.money(pnl, currency, showSign = true)
                },
                formattedCommission = when {
                    commission == null -> "—"
                    mixedCurrency -> "${Formatters.money(commission, currency)} (mixed)"
                    else -> Formatters.money(commission, currency)
                },
                isPositiveRealizedPnL = pnl?.let { it >= 0 },
            )
        }

        private fun today(clock: TradingClock): LocalDate =
            Instant.ofEpochMilli(clock.nowEpochMillis())
                .atZone(ZoneId.systemDefault())
                .toLocalDate()

        private fun parseFilterDate(value: String): LocalDate? {
            val trimmed = value.trim()
            if (trimmed.isEmpty()) return null
            return try {
                LocalDate.parse(trimmed, FILTER_DATE_FORMAT)
            } catch (_: DateTimeParseException) {
                null
            }
        }

        private fun formatFilterDate(date: LocalDate?): String =
            date?.format(FILTER_DATE_FORMAT).orEmpty()
    }
}
