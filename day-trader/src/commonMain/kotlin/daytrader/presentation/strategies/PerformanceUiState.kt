package daytrader.presentation.strategies

import daytrader.presentation.positions.SortDirection

enum class RunSortColumn {
    DATE, PNL, TRADES, AT_RISK
}

data class StrategyRunRowUi(
    val id: String,
    val formattedDate: String,
    val formattedPnL: String,
    val isPositivePnL: Boolean,
    val trades: Int,
    val formattedAtRisk: String
)

data class PerformanceUiState(
    val rollup7d: String,
    val rollup30d: String,
    val winRate: String,
    val rows: List<StrategyRunRowUi>,
    val sortColumn: RunSortColumn,
    val sortDirection: SortDirection
)
