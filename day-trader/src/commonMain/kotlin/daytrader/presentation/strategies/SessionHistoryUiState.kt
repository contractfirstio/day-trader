package daytrader.presentation.strategies

import daytrader.presentation.positions.SortDirection

enum class SessionHistorySortColumn {
    TIME, PNL
}

data class StrategySessionRowUi(
    val id: String,
    val formattedSessionTime: String,
    val positionLine: String,
    val hasTradeDetail: Boolean,
    val hasPipelineLog: Boolean = false,
    val formattedPnL: String,
    val isPositivePnL: Boolean,
    val isPnLFlat: Boolean,
    val isInProgress: Boolean = false,
    val canDelete: Boolean = false,
    val isSelected: Boolean = false,
    /** Touch Turn pipeline graph for the selected row only. */
    val pipelineGraph: TouchTurnPipelineGraph? = null,
    /** Touch Turn run facts for the selected row only. */
    val touchTurnRunDetail: TouchTurnRunRecordUi? = null
)

data class SessionHistoryUiState(
    val rollup30d: String,
    val winRate: String,
    val rows: List<StrategySessionRowUi>,
    val sortColumn: SessionHistorySortColumn,
    val sortDirection: SortDirection,
    val selectedRunId: String? = null,
    val selectedSessionTradeDetail: SessionTradeDetailUiState? = null,
    /** Set when a market filter is active (for empty-state copy). */
    val marketFilterLabel: String? = null
)
