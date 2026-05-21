package daytrader.presentation.positions

enum class SortableColumn {
    COMPANY, SYMBOL, UNREALIZED_PNL
}

enum class SortDirection {
    ASCENDING, DESCENDING
}

data class PositionRowUi(
    val companyName: String,
    val symbol: String,
    val formattedPnL: String,
    val isPositivePnL: Boolean
)

data class PositionsUiState(
    val rows: List<PositionRowUi> = emptyList(),
    val sortColumn: SortableColumn = SortableColumn.COMPANY,
    val sortDirection: SortDirection = SortDirection.ASCENDING
)
