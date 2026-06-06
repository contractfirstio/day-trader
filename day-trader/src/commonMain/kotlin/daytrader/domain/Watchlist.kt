package daytrader.domain

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
    val tradePlans: List<WatchlistTradePlan> = defaultWatchlistTradePlans(),
    val lastScannedPrice: Double? = null,
    val lastScannedAtEpochMs: Long? = null
)

data class Watchlist(
    val id: String,
    val name: String,
    val entries: List<WatchlistEntry>,
    val labels: List<WatchlistLabel> = emptyList(),
    val createdAtEpochMs: Long
)

fun newWatchlistId(): String = "wl-${kotlin.random.Random.nextLong().toULong().toString(16)}"

fun newWatchlistEntryId(): String = "wle-${kotlin.random.Random.nextLong().toULong().toString(16)}"

const val DEFAULT_WATCHLIST_ID = "default-watchlist"

fun defaultWatchlist(nowEpochMs: Long = System.currentTimeMillis()): Watchlist =
    Watchlist(
        id = DEFAULT_WATCHLIST_ID,
        name = "Watchlist",
        entries = emptyList(),
        createdAtEpochMs = nowEpochMs
    )

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
