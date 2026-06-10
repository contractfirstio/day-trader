package daytrader.domain

import daytrader.gateway.BrokerKind

data class WatchlistEntry(
    val id: String,
    val symbol: String,
    val companyName: String?,
    val marketZoneId: String,
    val currencyCode: String,
    val instrument: InstrumentIdentity?,
    val addedAtEpochMs: Long,
    val notes: String? = null,
    val labelIds: List<String> = emptyList(),
    /** Strategy deployment ids this watchlist symbol is linked to (many-to-many). */
    val strategyDeploymentIds: List<String> = emptyList(),
    val tradePlans: List<WatchlistTradePlan> = defaultWatchlistTradePlans(),
    val lastScannedPrice: Double? = null,
    val lastScannedAtEpochMs: Long? = null,
    val reversalScore: Int? = null,
    val reversalScoreAtEpochMs: Long? = null,
    val reversalScoreAlignmentBadge: ReversalScoreAlignmentBadge? = null,
    val reversalScoreInsightText: String? = null,
    val reversalScoreRecommendationText: String? = null
)

/** Cached home-market macro regime from the latest watchlist reversal-score batch. */
data class WatchlistHomeMarketRegime(
    val marketZoneId: String,
    val benchmarkSymbol: String,
    val benchmarkLabel: String,
    val macroTrend: MacroTrendState? = null,
    val lastPrice: Double? = null,
    val sma200: Double? = null
)

data class Watchlist(
    val id: String,
    val name: String,
    val entries: List<WatchlistEntry>,
    val labels: List<WatchlistLabel> = emptyList(),
    val createdAtEpochMs: Long,
    val lastReversalScoreHomeMarketRegimes: List<WatchlistHomeMarketRegime> = emptyList()
)

fun newWatchlistId(): String = "wl-${kotlin.random.Random.nextLong().toULong().toString(16)}"

fun newWatchlistEntryId(): String = "wle-${kotlin.random.Random.nextLong().toULong().toString(16)}"

const val DEFAULT_WATCHLIST_ID = "default-watchlist"

fun defaultWatchlist(nowEpochMs: Long = System.currentTimeMillis()): Watchlist =
    defaultWatchlistForBrokerKind(BrokerKind.EMULATOR, nowEpochMs)

fun defaultWatchlistForBrokerKind(
    kind: BrokerKind,
    nowEpochMs: Long = System.currentTimeMillis()
): Watchlist =
    Watchlist(
        id = DEFAULT_WATCHLIST_ID,
        name = watchlistNameForBrokerKind(kind),
        entries = emptyList(),
        createdAtEpochMs = nowEpochMs
    )

fun watchlistNameForBrokerKind(kind: BrokerKind): String = when (kind) {
    BrokerKind.EMULATOR -> "Watchlist (Emulator)"
    BrokerKind.EMULATOR_LIVE_IB_MARKET_DATA -> "Watchlist (Paper · Live IB)"
    BrokerKind.INTERACTIVE_BROKERS -> "Watchlist (Interactive Brokers)"
    BrokerKind.REPLAY -> "Watchlist (Replay)"
}

fun newWatchlistEntry(
    symbol: String,
    marketZoneId: String,
    currencyCode: String,
    companyName: String?,
    instrument: InstrumentIdentity?,
    notes: String? = null,
    nowEpochMs: Long = System.currentTimeMillis()
): WatchlistEntry {
    val symbolUpper = symbol.trim().uppercase()
    return WatchlistEntry(
        id = newWatchlistEntryId(),
        symbol = symbolUpper,
        companyName = companyName?.trim()?.takeIf { it.isNotBlank() },
        marketZoneId = marketZoneId,
        currencyCode = currencyCode,
        instrument = instrument,
        addedAtEpochMs = nowEpochMs,
        notes = notes?.trim()?.takeIf { it.isNotBlank() }
    )
}
