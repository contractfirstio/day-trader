package daytrader.presentation.positions

enum class SortableColumn {
    SYMBOL, COMPANY, QUANTITY, AVG_PRICE, LAST_PRICE, MARKET_VALUE, DAILY_CHANGE, UNREALIZED_PNL
}

enum class SortDirection {
    ASCENDING, DESCENDING
}

data class PositionRowUi(
    val symbol: String,
    val companyName: String,
    val quantity: Int,
    val formattedAvgPrice: String,
    val formattedMarketPrice: String,
    val formattedMarketValue: String,
    val formattedDailyChange: String,
    val formattedPnL: String,
    val isPositiveDailyChange: Boolean,
    val isPositivePnL: Boolean
)

data class PositionsUiState(
    val rows: List<PositionRowUi> = emptyList(),
    val searchQuery: String = "",
    val sortColumn: SortableColumn = SortableColumn.SYMBOL,
    val sortDirection: SortDirection = SortDirection.ASCENDING
)
