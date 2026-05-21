package daytrader.presentation.strategies

import daytrader.domain.RunStatus
import daytrader.presentation.positions.SortDirection

enum class RunSortColumn {
    DATE, PNL, TRADES, VS_MAX
}

data class StrategyRunRowUi(
    val id: String,
    val formattedDate: String,
    val formattedPnL: String,
    val isPositivePnL: Boolean,
    val trades: Int,
    val formattedVsMax: String,
    val status: RunStatus,
    val isLive: Boolean
)

data class PerformanceUiState(
    val currentRunDateLabel: String,
    val currentRunPnL: String,
    val isCurrentRunPositive: Boolean,
    val currentRunTrades: Int,
    val isLive: Boolean,
    val rollup7d: String,
    val rollup30d: String,
    val winRate: String,
    val rows: List<StrategyRunRowUi>,
    val sortColumn: RunSortColumn,
    val sortDirection: SortDirection
)
