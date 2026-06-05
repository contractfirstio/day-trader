package daytrader.presentation.strategies

import daytrader.presentation.positions.SortDirection

enum class SessionHistorySortColumn {
    TIME, PNL
}

data class StrategySessionRowUi(
    val id: String,
    val deploymentId: String,
    val sessionLogFolder: String,
    val formattedSessionTime: String,
    val positionLine: String,
    val formattedPnL: String,
    val isPositivePnL: Boolean,
    val isPnLFlat: Boolean,
    val isInProgress: Boolean = false,
    val canDelete: Boolean = false,
    val isSelected: Boolean = false,
    /** True when this row can be opened on the Trading tab (closed Touch Turn with pipeline data). */
    val opensOnTradingTab: Boolean = false,
)

data class SessionHistoryUiState(
    val rollup30d: String,
    val winRate: String,
    val rows: List<StrategySessionRowUi>,
    val sortColumn: SessionHistorySortColumn,
    val sortDirection: SortDirection,
    val selectedRunId: String? = null,
    /** Set when a market filter is active (for empty-state copy). */
    val marketFilterLabel: String? = null
)
