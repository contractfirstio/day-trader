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
    val status: daytrader.domain.DeploymentStatus,
    val cardAccent: DeploymentCardAccent,
    val statusChipLabel: String,
    val formattedTotalPnL: String,
    val isPositiveTotalPnL: Boolean,
    val formattedWinRate: String,
    /** null when no closed runs; true when win rate is at least 50%. */
    val winRateIsPositive: Boolean? = null,
    val formattedNoTradeRate: String = "—",
    val formattedLastSessionPnL: String = "—",
    /** null when there is no closed session yet. */
    val isPositiveLastSessionPnL: Boolean? = null,
    val autoStartOnMarketOpen: Boolean = false,
    val hasOpenPosition: Boolean = false,
    /** Raw unrealized P&L for open positions; formatted in Compose on the live band. */
    val positionPnL: Double? = null,
    val isPositivePositionPnL: Boolean? = null,
    val maxProfit: Double? = null,
    val stopOutcome: Double? = null,
    /** Currency for [positionPnL], [maxProfit], and [stopOutcome] display. */
    val currencyCode: String,
    /** When true, [stopOutcome] is a guaranteed minimum win (trailing stop past entry). */
    val stopOutcomeIsMinWin: Boolean = false
)

/** Lightweight index for copy-rules targeting — avoids retaining full [StrategyDeployment] graphs in UI state. */
data class StrategyDeploymentCopyTarget(
    val id: String,
    val marketZoneId: String,
)

/** Left rail: deployment list, filters, and summary — stable during quote-only refreshes. */
data class StrategiesListUiState(
    val filteredRows: List<StrategyDeploymentRowUi> = emptyList(),
    val filteredSummary: FilteredDeploymentsSummaryUi? = null,
    val filteredCount: Int = 0,
    val totalCount: Int = 0,
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
    /** All deployments (id + market only) for copy-rules market targeting. */
    val deploymentCopyTargets: List<StrategyDeploymentCopyTarget> = emptyList(),
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

data class SessionMarketDataCaptureUi(
    val sessionId: String,
    val symbol: String,
)
