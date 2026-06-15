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
    val formattedLastSessionPnL: String = "—",
    /** null when there is no closed session yet. */
    val isPositiveLastSessionPnL: Boolean? = null,
    val autoStartOnMarketOpen: Boolean = false,
    val hasOpenPosition: Boolean = false,
    val formattedPositionPnL: String? = null,
    val isPositivePositionPnL: Boolean? = null,
    val formattedMaxProfit: String? = null,
    val formattedStopOutcome: String? = null,
    /** When true, [formattedStopOutcome] is a guaranteed minimum win (trailing stop past entry). */
    val stopOutcomeIsMinWin: Boolean = false
)

data class StrategiesUiState(
    val filteredRows: List<StrategyDeploymentRowUi> = emptyList(),
    val filteredSummary: FilteredDeploymentsSummaryUi? = null,
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
    val addDialogPrefill: StrategyDeploymentAddPrefill? = null,
    val showImportDialog: Boolean = false,
    val symbolImport: DeploymentSymbolImportUiState? = null,
    val selectedDeploymentId: String? = null,
    val sessionHistory: SessionHistoryUiState? = null,
    val liveExecution: LiveExecutionUiState? = null,
    val liveBroker: LiveBrokerUiState? = null,
    val liveSessionTrades: LiveSessionTradesUiState? = null,
    val touchTurnLiveOrderChart: TouchTurnLiveOrderChartUiState? = null,
    /** Streaming IB marks on the Opening bar pipeline step while the 15m candle is forming. */
    val touchTurnFormingBarPriceChart: TouchTurnLiveOrderChartUiState? = null,
    val startBlockedAlert: StartBlockedByPositionAlert? = null,
    val globalAutoStartEnabled: Boolean = true,
    /** When false, the Trading tab shows an idle pipeline instead of a closed-session recap. */
    val tradingPanelShowsSessionRecap: Boolean = false,
    /** Session history row driving the Trading tab recap; null = latest closed run. */
    val tradingPanelRecapRunId: String? = null,
    /** When false, bid/ask/last are hidden on the Trading tab (idle Touch Turn panel). */
    val tradingPanelShowsLiveMarketQuotes: Boolean = false,
    /** Live Touch Turn pipeline graph for the selected deployment (engine-aligned). */
    val touchTurnPipelineGraph: TouchTurnPipelineGraph? = null,
    /** Broker-agnostic Touch Turn order lifecycle for the selected live/recap run. */
    val touchTurnOrderLifecycle: TouchTurnOrderLifecycleUi? = null,
    /** Pre-flight checklist for stopped Touch Turn deployments (Prepare before Start). */
    val touchTurnPrepare: TouchTurnPrepareUiState? = null,
    /** Closed session rows across all deployments (for global clear-history). */
    val globalClosedSessionHistoryCount: Int = 0,
    /** True when any deployment still has an in-progress session row. */
    val globalHasInProgressSessions: Boolean = false,
    /** Post-session IB quote recording for the selected deployment (Hybrid / IB only). */
    val sessionMarketDataCapture: SessionMarketDataCaptureUi? = null,
)

data class SessionMarketDataCaptureUi(
    val sessionId: String,
    val symbol: String,
)
