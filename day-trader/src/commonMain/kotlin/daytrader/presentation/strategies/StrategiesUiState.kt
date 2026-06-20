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
    val formattedNoTradeRate: String = "—",
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

/** Left rail: deployment list, filters, and summary — stable during quote-only refreshes. */
data class StrategiesListUiState(
    val filteredRows: List<StrategyDeploymentRowUi> = emptyList(),
    val filteredSummary: FilteredDeploymentsSummaryUi? = null,
    val filteredCount: Int = 0,
    val totalCount: Int = 0,
    val allDeployments: List<StrategyDeployment> = emptyList(),
    val hasActiveFilters: Boolean = false,
    val selectedMarketZoneId: String? = null,
    val selectedMarketLabel: String? = null,
    val searchQuery: String = "",
    val deploymentFilter: DeploymentFilter = DeploymentFilter.ALL,
    val strategyTypeFilter: StrategyType? = null,
    val globalAutoStartEnabled: Boolean = true,
    val globalClosedSessionHistoryCount: Int = 0,
    val globalHasInProgressSessions: Boolean = false,
)

/** Selected deployment detail (non-streaming fields). */
data class StrategiesDetailUiState(
    val selectedDeploymentId: String? = null,
    val selectedDeployment: StrategyDeployment? = null,
    val selectedCardPresentation: DeploymentCardPresentation? = null,
    val detailTab: StrategyDetailTab = StrategyDetailTab.CONFIGURATION,
    val sessionHistory: SessionHistoryUiState? = null,
    val liveExecution: LiveExecutionUiState? = null,
    val touchTurnPrepare: TouchTurnPrepareUiState? = null,
    val tradingPanelShowsSessionRecap: Boolean = false,
    val tradingPanelRecapRunId: String? = null,
    val globalAutoStartEnabled: Boolean = true,
    /** All deployments (unfiltered) for bulk actions such as copy-rules market targeting. */
    val allDeployments: List<StrategyDeployment> = emptyList(),
)

/** Live quotes, charts, and pipeline — updates on quote ticks and pipeline timer. */
data class StrategiesLiveUiState(
    val liveBroker: LiveBrokerUiState? = null,
    val liveSessionTrades: LiveSessionTradesUiState? = null,
    val touchTurnLiveOrderChart: TouchTurnLiveOrderChartUiState? = null,
    val touchTurnFormingBarPriceChart: TouchTurnLiveOrderChartUiState? = null,
    val touchTurnPipelineGraph: TouchTurnPipelineGraph? = null,
    val touchTurnOrderLifecycle: TouchTurnOrderLifecycleUi? = null,
    val tradingPanelShowsLiveMarketQuotes: Boolean = false,
    val sessionMarketDataCapture: SessionMarketDataCaptureUi? = null,
)

/** Dialogs and modal alerts — infrequent updates. */
data class StrategiesChromeUiState(
    val showAddDialog: Boolean = false,
    val addDialogPrefill: StrategyDeploymentAddPrefill? = null,
    val showImportDialog: Boolean = false,
    val symbolImport: DeploymentSymbolImportUiState? = null,
    val startBlockedAlert: StartBlockedByPositionAlert? = null,
)

data class StrategiesUiState(
    val filteredRows: List<StrategyDeploymentRowUi> = emptyList(),
    val filteredSummary: FilteredDeploymentsSummaryUi? = null,
    val filteredCount: Int = 0,
    val totalCount: Int = 0,
    /** All deployments (unfiltered) for bulk actions such as copy-rules market targeting. */
    val allDeployments: List<StrategyDeployment> = emptyList(),
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

fun StrategiesListUiState.mergeUiState(
    detail: StrategiesDetailUiState,
    live: StrategiesLiveUiState,
    chrome: StrategiesChromeUiState,
): StrategiesUiState = StrategiesUiState(
    filteredRows = filteredRows,
    filteredSummary = filteredSummary,
    filteredCount = filteredCount,
    totalCount = totalCount,
    allDeployments = allDeployments,
    hasActiveFilters = hasActiveFilters,
    selectedMarketZoneId = selectedMarketZoneId,
    selectedMarketLabel = selectedMarketLabel,
    selectedDeployment = detail.selectedDeployment,
    selectedCardPresentation = detail.selectedCardPresentation,
    searchQuery = searchQuery,
    deploymentFilter = deploymentFilter,
    strategyTypeFilter = strategyTypeFilter,
    detailTab = detail.detailTab,
    showAddDialog = chrome.showAddDialog,
    addDialogPrefill = chrome.addDialogPrefill,
    showImportDialog = chrome.showImportDialog,
    symbolImport = chrome.symbolImport,
    selectedDeploymentId = detail.selectedDeploymentId,
    sessionHistory = detail.sessionHistory,
    liveExecution = detail.liveExecution,
    liveBroker = live.liveBroker,
    liveSessionTrades = live.liveSessionTrades,
    touchTurnLiveOrderChart = live.touchTurnLiveOrderChart,
    touchTurnFormingBarPriceChart = live.touchTurnFormingBarPriceChart,
    startBlockedAlert = chrome.startBlockedAlert,
    globalAutoStartEnabled = globalAutoStartEnabled,
    tradingPanelShowsSessionRecap = detail.tradingPanelShowsSessionRecap,
    tradingPanelRecapRunId = detail.tradingPanelRecapRunId,
    tradingPanelShowsLiveMarketQuotes = live.tradingPanelShowsLiveMarketQuotes,
    touchTurnPipelineGraph = live.touchTurnPipelineGraph,
    touchTurnOrderLifecycle = live.touchTurnOrderLifecycle,
    touchTurnPrepare = detail.touchTurnPrepare,
    globalClosedSessionHistoryCount = globalClosedSessionHistoryCount,
    globalHasInProgressSessions = globalHasInProgressSessions,
    sessionMarketDataCapture = live.sessionMarketDataCapture,
)
