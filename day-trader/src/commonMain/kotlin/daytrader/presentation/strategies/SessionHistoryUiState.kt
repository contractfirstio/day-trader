package daytrader.presentation.strategies

import daytrader.presentation.positions.SortDirection

enum class SessionHistorySortColumn {
    START, STOP, LIQUIDITY, ORDERS, PNL
}

data class StrategySessionRowUi(
    val id: String,
    val formattedStartTime: String,
    val formattedStopTime: String,
    val tradeSideLabel: String?,
    val tradeSummary: String?,
    val hasTradeDetail: Boolean,
    val liquidityCandle: String,
    val ordersPlaced: String,
    val formattedPnL: String,
    val isPositivePnL: Boolean,
    val isPnLFlat: Boolean,
    val isInProgress: Boolean = false,
    val canDelete: Boolean = false,
    val isSelected: Boolean = false
)

data class SessionHistoryUiState(
    val rollup7d: String,
    val rollup14d: String,
    val rollup30d: String,
    val winRate: String,
    val rows: List<StrategySessionRowUi>,
    val sortColumn: SessionHistorySortColumn,
    val sortDirection: SortDirection,
    val includeTouchTurnFields: Boolean = false,
    val selectedRunId: String? = null,
    val selectedSessionTradeDetail: SessionTradeDetailUiState? = null
)
