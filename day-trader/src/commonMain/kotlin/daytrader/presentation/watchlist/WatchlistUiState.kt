package daytrader.presentation.watchlist

import daytrader.domain.OhlcBar
import daytrader.domain.PlanSizingMode
import daytrader.domain.ProximityThresholdMode
import daytrader.domain.MacroTrendState
import daytrader.domain.StrategyType
import daytrader.domain.TradeSide
import daytrader.domain.WatchlistProximityStatus
import daytrader.presentation.strategies.TouchTurnOrderLevelKind
import daytrader.presentation.strategies.TouchTurnOrderLevelUi
import daytrader.presentation.strategies.TouchTurnQuoteStripUi

enum class WatchlistSortColumn {
    COMPANY,
    SYMBOL,
    MARKET,
    GROUPS,
    STRATEGIES,
    LAST,
    REVERSAL_SCORE,
    STATUS,
    PLANS
}

enum class WatchlistSortDirection {
    ASCENDING, DESCENDING
}

sealed class WatchlistGroupFilter {
    data object All : WatchlistGroupFilter()
    data object Ungrouped : WatchlistGroupFilter()
    data class Group(val labelId: String) : WatchlistGroupFilter()
}

data class WatchlistLabelUi(
    val id: String,
    val name: String
)

data class WatchlistStrategyUi(
    val deploymentId: String,
    val strategyType: StrategyType,
    val label: String
)

sealed class WatchlistStrategyFilter {
    data object All : WatchlistStrategyFilter()
    data object Unassigned : WatchlistStrategyFilter()
    data class Strategy(val strategyType: StrategyType) : WatchlistStrategyFilter()
}

data class WatchlistStrategyFilterChipUi(
    val filter: WatchlistStrategyFilter,
    val label: String,
    val count: Int,
    val selected: Boolean
)

data class WatchlistGroupFilterChipUi(
    val filter: WatchlistGroupFilter,
    val label: String,
    val count: Int,
    val selected: Boolean
)

enum class WatchlistConnectionChipTone {
    CONNECTED,
    CONNECTING,
    DISCONNECTED,
    ERROR
}

data class WatchlistConnectionChipUi(
    val label: String,
    val tone: WatchlistConnectionChipTone
)

data class WatchlistStatusStripUi(
    val connectionChips: List<WatchlistConnectionChipUi> = emptyList(),
    val priceModeLabel: String = "On-demand prices",
    val priceModeTooltip: String =
        "Last prices refresh when you run Check proximity (IB historical requests, not streaming).",
    val macroChips: List<WatchlistConnectionChipUi> = emptyList()
)

data class WatchlistMacroRegimeCardUi(
    val benchmarkLabel: String,
    val trend: MacroTrendState?,
    val trendLabel: String,
    val indexPriceLabel: String,
    val distanceFromSmaLabel: String,
    val actionHint: String,
    val scoredLabel: String,
    val calculatedAtLabel: String
)

data class WatchlistActivitySummaryUi(
    val proximityLabel: String?,
    val proximityHighlighted: Boolean = false,
    val reversalLabel: String? = null
)

enum class ReversalScoreProgressStepStatus {
    PENDING,
    ACTIVE,
    COMPLETE
}

data class ReversalScoreProgressStepUi(
    val label: String,
    val status: ReversalScoreProgressStepStatus
)

data class ReversalScoreProgressUi(
    val steps: List<ReversalScoreProgressStepUi>,
    val detailLabel: String?
)

data class WatchlistScanProgressUi(
    val completed: Int,
    val total: Int,
    val symbol: String
)

data class WatchlistRowUi(
    val entryId: String,
    val companyName: String,
    val symbol: String,
    val marketLabel: String,
    val formattedLast: String,
    val lastPriceSublabel: String? = null,
    val lastPriceAtLabel: String? = null,
    val proximityStatusLabel: String,
    val isNearEntry: Boolean,
    val notesPreview: String?,
    val planSummary: String? = null,
    val nearEntrySummary: String? = null,
    val reversalScoreLabel: String? = null,
    val reversalScore: Int? = null,
    val reversalScoreStale: Boolean = false,
    val reversalScoreCalculatedAtLabel: String? = null,
    val reversalScoreAlignmentBadgeLabel: String? = null,
    val reversalScoreHasInsight: Boolean = false,
    val reversalScoreLoading: Boolean = false,
    val groups: List<WatchlistLabelUi> = emptyList(),
    val strategies: List<WatchlistStrategyUi> = emptyList()
)

data class WatchlistPlanEditorUi(
    val planId: String,
    val label: String,
    val side: TradeSide,
    val entryPriceText: String,
    val stopPriceText: String,
    val targetPriceText: String,
    val investmentAmountText: String,
    val sizingMode: PlanSizingMode,
    val proximityAlertEnabled: Boolean,
    val proximityThresholdMode: ProximityThresholdMode,
    val proximityThresholdValueText: String,
    val stopEntry: Boolean = false,
    val adjustableTrailingStop: Boolean = true,
    val outcome: WatchlistPlanOutcomeUi? = null,
    val isNearEntry: Boolean = false,
    val orderPlacedLabel: String? = null,
    val diaryEntryCount: Int = 0,
    val pendingDiaryReminderCount: Int = 0
)

data class WatchlistPlanOutcomeUi(
    val quantityLabel: String? = null,
    val notionalLabel: String? = null,
    val lossAtStopLabel: String? = null,
    val profitAtTargetLabel: String? = null,
    val rMultipleLabel: String? = null,
    val returnAtTargetLabel: String? = null,
    val returnAtStopLabel: String? = null,
    val errors: List<String> = emptyList()
)

data class WatchlistTradePlansEditorUi(
    val entryId: String,
    val symbol: String,
    val companyName: String,
    val formattedLast: String,
    val scannedPrice: Double? = null,
    val currencyCode: String,
    val assignedLabels: List<WatchlistLabelUi> = emptyList(),
    val availableLabels: List<WatchlistLabelUi> = emptyList(),
    val assignedLabelIds: List<String> = emptyList(),
    val pendingLabels: List<WatchlistLabelUi> = emptyList(),
    val newGroupInput: String = "",
    val assignedStrategies: List<WatchlistStrategyUi> = emptyList(),
    val availableStrategies: List<WatchlistStrategyUi> = emptyList(),
    val assignedStrategyDeploymentIds: List<String> = emptyList(),
    val plans: List<WatchlistPlanEditorUi>,
    val savedListingLabel: String? = null,
    val minOrderSize: Int = 1,
    val orderSizeIncrement: Int = 1,
    val canRelookupInstrument: Boolean = false,
    val instrumentRelookupInProgress: Boolean = false,
    val instrumentRelookupMessage: String? = null
)

data class WatchlistNearHitUi(
    val entryId: String,
    val symbol: String,
    val planLabel: String,
    val summary: String
)

enum class WatchlistPlanField {
    ENTRY, STOP, TARGET, INVESTMENT, PROXIMITY_THRESHOLD
}

data class WatchlistBracketOrderUi(
    val entryId: String,
    val planId: String,
    val symbol: String,
    val companyName: String,
    val planLabel: String,
    val currencyCode: String,
    val side: TradeSide,
    val entryPriceText: String,
    val stopPriceText: String,
    val targetPriceText: String,
    val investmentAmountText: String,
    val sizingMode: PlanSizingMode = PlanSizingMode.NOTIONAL,
    /** Derived from investment, prices, and board-lot rules — not user-edited. */
    val quantityText: String,
    val stopEntry: Boolean = false,
    val adjustableTrailingStop: Boolean = true,
    val minOrderSize: Int = 1,
    val orderSizeIncrement: Int = 1,
    val bracketOrderSummary: String = "",
    val outcome: WatchlistPlanOutcomeUi? = null,
    val validationErrors: List<String> = emptyList(),
    val canSubmit: Boolean = false,
    val submitInProgress: Boolean = false,
    val submitResultMessage: String? = null
)

enum class WatchlistBracketOrderField {
    ENTRY, STOP, TARGET
}

data class WatchlistPlanDiaryEntryUi(
    val id: String,
    val body: String,
    val formattedCreatedAt: String,
    val notifyOnDateLabel: String?,
    val reminderActive: Boolean
)

data class WatchlistPlanDiaryEditorUi(
    val entryId: String,
    val planId: String,
    val symbol: String,
    val companyName: String,
    val planLabel: String,
    val entries: List<WatchlistPlanDiaryEntryUi>,
    val focusedEntryId: String? = null,
    val composingEntry: Boolean = false,
    val editingEntryId: String? = null,
    val draftBody: String = "",
    val draftNotifyOnDate: String = "",
    val draftNotifyEnabled: Boolean = false
)

data class WatchlistDiaryNotificationUi(
    val entryId: String,
    val planId: String,
    val diaryEntryId: String,
    val symbol: String,
    val companyName: String,
    val planLabel: String,
    val bodyPreview: String,
    val notifyOnDateLabel: String
)

data class WatchlistReversalScoreInsightUi(
    val entryId: String,
    val symbol: String,
    val companyName: String,
    val compositeScore: Int,
    val contextBadgeLabel: String?,
    val insightText: String,
    val recommendationText: String
)

data class WatchlistEntryChartsUi(
    val symbol: String,
    val currencyCode: String,
    val dailyBars: List<OhlcBar> = emptyList(),
    val dailyLoading: Boolean = false,
    val dailyError: String? = null,
    val livePriceHistory: List<Double> = emptyList(),
    val liveCurrentPrice: Double? = null,
    val liveAvailable: Boolean = false,
    val liveStatusLabel: String? = null,
    val liveQuoteStrip: TouchTurnQuoteStripUi? = null,
    val orderLevels: List<TouchTurnOrderLevelUi> = emptyList(),
    val executedLevels: Set<TouchTurnOrderLevelKind> = emptySet(),
    val listingExch: String? = null
)

data class WatchlistUiState(
    val watchlistName: String = "Watchlist",
    val totalEntryCount: Int = 0,
    val rows: List<WatchlistRowUi> = emptyList(),
    val groupFilterChips: List<WatchlistGroupFilterChipUi> = emptyList(),
    val activeGroupFilter: WatchlistGroupFilter = WatchlistGroupFilter.All,
    val strategyFilterChips: List<WatchlistStrategyFilterChipUi> = emptyList(),
    val activeStrategyFilter: WatchlistStrategyFilter = WatchlistStrategyFilter.All,
    val sortColumn: WatchlistSortColumn = WatchlistSortColumn.SYMBOL,
    val sortDirection: WatchlistSortDirection = WatchlistSortDirection.ASCENDING,
    val showAddDialog: Boolean = false,
    val tradePlansEditor: WatchlistTradePlansEditorUi? = null,
    val entryCharts: WatchlistEntryChartsUi? = null,
    val planDiaryEditor: WatchlistPlanDiaryEditorUi? = null,
    val pendingDiaryNotification: WatchlistDiaryNotificationUi? = null,
    val bracketOrderEditor: WatchlistBracketOrderUi? = null,
    val connectionLabel: String = "Disconnected",
    val statusStrip: WatchlistStatusStripUi = WatchlistStatusStripUi(),
    val macroRegimeCards: List<WatchlistMacroRegimeCardUi> = emptyList(),
    val activitySummary: WatchlistActivitySummaryUi? = null,
    val scanInProgress: Boolean = false,
    val scanProgress: WatchlistScanProgressUi? = null,
    val scanSummary: String? = null,
    val reversalScoreInProgress: Boolean = false,
    val reversalScoreProgress: ReversalScoreProgressUi? = null,
    val reversalScoreProgressLabel: String? = null,
    val reversalScoreSummary: String? = null,
    val reversalScoreLoadingEntryId: String? = null,
    val reversalScoreInsight: WatchlistReversalScoreInsightUi? = null,
    val nearHits: List<WatchlistNearHitUi> = emptyList(),
    /** Broker mode this watchlist file belongs to (separate persistence per mode). */
    val storageScopeLabel: String = ""
)
