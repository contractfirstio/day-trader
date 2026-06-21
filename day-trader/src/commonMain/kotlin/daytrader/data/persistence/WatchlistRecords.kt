package daytrader.data.persistence

import kotlinx.serialization.Serializable

@Serializable
data class WatchlistsDocument(
    val watchlists: List<WatchlistRecord> = emptyList()
)

@Serializable
data class WatchlistRecord(
    val id: String,
    val name: String,
    val entries: List<WatchlistEntryRecord> = emptyList(),
    val labels: List<WatchlistLabelRecord> = emptyList(),
    val createdAtEpochMs: Long,
    val lastReversalScoreHomeMarketRegimes: List<WatchlistHomeMarketRegimeRecord> = emptyList(),
    /** Legacy single-SPY cache — migrated into [lastReversalScoreHomeMarketRegimes] on load. */
    val lastReversalScoreMacroTrend: String? = null,
    val lastReversalScoreSpyLastPrice: Double? = null,
    val lastReversalScoreSpySma200: Double? = null
)

@Serializable
data class WatchlistHomeMarketRegimeRecord(
    val marketZoneId: String,
    val benchmarkSymbol: String,
    val benchmarkLabel: String,
    val macroTrend: String? = null,
    val lastPrice: Double? = null,
    val sma200: Double? = null
)

@Serializable
data class WatchlistLabelRecord(
    val id: String,
    val name: String,
    val createdAtEpochMs: Long
)

@Serializable
data class WatchlistEntryRecord(
    val id: String,
    val symbol: String,
    val companyName: String? = null,
    val marketZoneId: String,
    val currencyCode: String,
    val instrument: InstrumentIdentityRecord? = null,
    val addedAtEpochMs: Long,
    val notes: String? = null,
    val labelIds: List<String> = emptyList(),
    val strategyDeploymentIds: List<String> = emptyList(),
    val tags: List<String> = emptyList(),
    val tradePlans: List<WatchlistTradePlanRecord> = emptyList(),
    val lastScannedPrice: Double? = null,
    val lastScannedAtEpochMs: Long? = null,
    val reversalScore: Int? = null,
    val reversalScoreAtEpochMs: Long? = null,
    val reversalScoreAlignmentBadge: String? = null,
    val reversalScoreInsightText: String? = null,
    val reversalScoreRecommendationText: String? = null
)

@Serializable
data class WatchlistPlanDiaryEntryRecord(
    val id: String,
    val body: String,
    val createdAtEpochMs: Long,
    val notifyOnDate: String? = null,
    val notificationDismissed: Boolean = false
)

@Serializable
data class WatchlistTradePlanRecord(
    val id: String,
    val label: String,
    val kind: String = "bracket",
    val side: String = "long",
    val entryPrice: Double? = null,
    val stopPrice: Double? = null,
    val targetPrice: Double? = null,
    val investmentAmount: Double? = null,
    val sizingMode: String = "notional",
    val proximityAlertEnabled: Boolean = false,
    val proximityThresholdMode: String = "percent",
    val proximityThresholdValue: Double? = null,
    val stopEntry: Boolean = false,
    val adjustableTrailingStop: Boolean = true,
    val orderPlacedAtEpochMs: Long? = null,
    val placedOrderIds: List<Int> = emptyList(),
    val executedBracketLegs: List<String> = emptyList(),
    val diaryEntries: List<WatchlistPlanDiaryEntryRecord> = emptyList()
)
