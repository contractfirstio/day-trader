package daytrader.presentation.strategies

import daytrader.domain.StrategyInstance
import daytrader.domain.StrategyType

enum class InstanceFilter {
    ALL,
    RUNNING,
    STOPPED
}

enum class StrategyDetailTab {
    CONFIGURATION,
    LIVE,
    PERFORMANCE
}

data class StrategyInstanceRowUi(
    val id: String,
    val name: String,
    val strategyTypeLabel: String,
    val status: daytrader.domain.InstanceStatus,
    val cardAccent: InstanceCardAccent,
    val statusChipLabel: String,
    val formattedTotalPnL: String,
    val isPositiveTotalPnL: Boolean,
    val paramsSummary: String,
    val tradesToday: Int,
    val liveTradeSummary: String?,
    val formattedRollup7d: String,
    val isPositiveRollup7d: Boolean,
    val formattedRollup30d: String,
    val isPositiveRollup30d: Boolean,
    val formattedWinRate: String,
    /** null when no closed runs; true when win rate is at least 50%. */
    val winRateIsPositive: Boolean? = null,
    val autoStartOnMarketOpen: Boolean = false
)

data class StrategiesUiState(
    val filteredRows: List<StrategyInstanceRowUi> = emptyList(),
    val filteredCount: Int = 0,
    val totalCount: Int = 0,
    val hasActiveFilters: Boolean = false,
    val selectedMarketZoneId: String? = null,
    val selectedMarketLabel: String? = null,
    val selectedInstance: StrategyInstance? = null,
    val selectedCardPresentation: InstanceCardPresentation? = null,
    val searchQuery: String = "",
    val instanceFilter: InstanceFilter = InstanceFilter.ALL,
    val strategyTypeFilter: StrategyType? = null,
    val detailTab: StrategyDetailTab = StrategyDetailTab.CONFIGURATION,
    val showAddDialog: Boolean = false,
    val selectedInstanceId: String? = null,
    val performance: PerformanceUiState? = null,
    val liveExecution: LiveExecutionUiState? = null,
    val liveBroker: LiveBrokerUiState? = null,
    val liveSessionTrades: LiveSessionTradesUiState? = null,
    val startBlockedAlert: StartBlockedByPositionAlert? = null,
    val globalAutoStartEnabled: Boolean = true
)
