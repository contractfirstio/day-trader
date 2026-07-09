package daytrader.presentation.trades

import daytrader.presentation.positions.SortDirection

enum class TradeDatePreset {
    SEVEN_DAYS,
    THIRTY_DAYS,
    NINETY_DAYS,
    ALL,
}

data class TradeFilterSummaryUi(
    val tradeCountLabel: String,
    val formattedRealizedPnL: String,
    val formattedCommission: String,
    val isPositiveRealizedPnL: Boolean?,
)

enum class TradeSortColumn {
    TIME,
    SYMBOL,
    SIDE,
    QUANTITY,
    PRICE,
    COMMISSION,
    REALIZED_PNL
}

data class TradeRowUi(
    val execId: String,
    val formattedTime: String,
    val symbol: String,
    val sideLabel: String,
    val isBuySide: Boolean,
    val quantityLabel: String,
    val formattedPrice: String,
    val formattedCommission: String?,
    val formattedRealizedPnL: String?,
    val isPositiveRealizedPnL: Boolean?
)

data class TradesUiState(
    val rows: List<TradeRowUi> = emptyList(),
    val totalFillCount: Int = 0,
    val totalStoredCount: Int = 0,
    val filterFromDate: String = "",
    val filterToDate: String = "",
    val activeDatePreset: TradeDatePreset? = TradeDatePreset.THIRTY_DAYS,
    val filterSymbol: String? = null,
    val availableSymbols: List<String> = emptyList(),
    val filterSummary: TradeFilterSummaryUi? = null,
    val sortColumn: TradeSortColumn = TradeSortColumn.TIME,
    val sortDirection: SortDirection = SortDirection.DESCENDING,
    val canSync: Boolean = false,
    val isSyncing: Boolean = false,
    val syncMessage: String? = null
)
