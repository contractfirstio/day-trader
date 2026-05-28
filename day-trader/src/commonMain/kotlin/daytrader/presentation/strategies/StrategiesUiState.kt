package daytrader.presentation.strategies

import daytrader.domain.StrategyDeployment
import daytrader.domain.StrategyType

enum class DeploymentFilter {
    ALL,
    RUNNING,
    STOPPED
}

enum class StrategyDetailTab {
    CONFIGURATION,
    LIVE,
    SESSION_HISTORY
}

data class StrategyDeploymentRowUi(
    val id: String,
    val name: String,
    /** Company name from IB when known; otherwise the trading symbol. */
    val instrumentName: String,
    val strategyTypeLabel: String,
    val status: daytrader.domain.DeploymentStatus,
    val cardAccent: DeploymentCardAccent,
    val statusChipLabel: String,
    val formattedTotalPnL: String,
    val isPositiveTotalPnL: Boolean,
    val paramsSummary: String,
    val tradesToday: Int,
    val liveTradeSummary: String?,
    val formattedRollup7d: String,
    val isPositiveRollup7d: Boolean,
    val formattedRollup14d: String,
    val isPositiveRollup14d: Boolean,
    val formattedRollup30d: String,
    val isPositiveRollup30d: Boolean,
    val formattedWinRate: String,
    /** null when no closed runs; true when win rate is at least 50%. */
    val winRateIsPositive: Boolean? = null,
    val autoStartOnMarketOpen: Boolean = false
)

data class StrategiesUiState(
    val filteredRows: List<StrategyDeploymentRowUi> = emptyList(),
    val filteredCount: Int = 0,
    val totalCount: Int = 0,
    val hasActiveFilters: Boolean = false,
    val selectedMarketZoneId: String? = null,
    val selectedMarketLabel: String? = null,
    val selectedDeployment: StrategyDeployment? = null,
    val selectedCardPresentation: DeploymentCardPresentation? = null,
    val searchQuery: String = "",
    val deploymentFilter: DeploymentFilter = DeploymentFilter.ALL,
    val strategyTypeFilter: StrategyType? = null,
    val detailTab: StrategyDetailTab = StrategyDetailTab.CONFIGURATION,
    val showAddDialog: Boolean = false,
    val selectedDeploymentId: String? = null,
    val sessionHistory: SessionHistoryUiState? = null,
    val liveExecution: LiveExecutionUiState? = null,
    val liveBroker: LiveBrokerUiState? = null,
    val liveSessionTrades: LiveSessionTradesUiState? = null,
    val touchTurnLiveOrderChart: TouchTurnLiveOrderChartUiState? = null,
    val startBlockedAlert: StartBlockedByPositionAlert? = null,
    val globalAutoStartEnabled: Boolean = true,
    /** When false, the Trading tab shows an idle pipeline instead of the last closed session. */
    val tradingPanelShowsLastSessionRecap: Boolean = false,
    /** When false, bid/ask/last are hidden on the Trading tab (idle Touch Turn panel). */
    val tradingPanelShowsLiveMarketQuotes: Boolean = false,
)
