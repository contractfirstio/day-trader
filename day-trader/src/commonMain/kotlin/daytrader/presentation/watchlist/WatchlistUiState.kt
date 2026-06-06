package daytrader.presentation.watchlist

import daytrader.domain.PlanSizingMode
import daytrader.domain.ProximityThresholdMode
import daytrader.domain.TradeSide
import daytrader.domain.WatchlistProximityStatus

enum class WatchlistSortColumn {
    COMPANY, SYMBOL, LAST, NOTES
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

data class WatchlistGroupFilterChipUi(
    val filter: WatchlistGroupFilter,
    val label: String,
    val count: Int,
    val selected: Boolean
)

data class WatchlistRowUi(
    val entryId: String,
    val companyName: String,
    val symbol: String,
    val marketLabel: String,
    val formattedLast: String,
    val lastPriceAtLabel: String?,
    val proximityStatusLabel: String,
    val isNearEntry: Boolean,
    val notesPreview: String?,
    val planSummary: String? = null,
    val nearEntrySummary: String? = null,
    val groups: List<WatchlistLabelUi> = emptyList()
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
    val plans: List<WatchlistPlanEditorUi>
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
    val quantityText: String,
    val outcome: WatchlistPlanOutcomeUi? = null,
    val validationErrors: List<String> = emptyList(),
    val canSubmit: Boolean = false,
    val submitInProgress: Boolean = false,
    val submitResultMessage: String? = null
)

enum class WatchlistBracketOrderField {
    ENTRY, STOP, TARGET, QUANTITY
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

data class WatchlistUiState(
    val watchlistName: String = "Watchlist",
    val totalEntryCount: Int = 0,
    val rows: List<WatchlistRowUi> = emptyList(),
    val groupFilterChips: List<WatchlistGroupFilterChipUi> = emptyList(),
    val activeGroupFilter: WatchlistGroupFilter = WatchlistGroupFilter.All,
    val sortColumn: WatchlistSortColumn = WatchlistSortColumn.SYMBOL,
    val sortDirection: WatchlistSortDirection = WatchlistSortDirection.ASCENDING,
    val showAddDialog: Boolean = false,
    val tradePlansEditor: WatchlistTradePlansEditorUi? = null,
    val planDiaryEditor: WatchlistPlanDiaryEditorUi? = null,
    val pendingDiaryNotification: WatchlistDiaryNotificationUi? = null,
    val bracketOrderEditor: WatchlistBracketOrderUi? = null,
    val connectionLabel: String = "Disconnected",
    val scanInProgress: Boolean = false,
    val scanProgressLabel: String? = null,
    val scanSummary: String? = null,
    val nearHits: List<WatchlistNearHitUi> = emptyList()
)
