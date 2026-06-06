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
    val createdAtEpochMs: Long
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
    val tags: List<String> = emptyList(),
    val tradePlans: List<WatchlistTradePlanRecord> = emptyList(),
    val lastScannedPrice: Double? = null,
    val lastScannedAtEpochMs: Long? = null
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
    val proximityThresholdValue: Double? = null
)
