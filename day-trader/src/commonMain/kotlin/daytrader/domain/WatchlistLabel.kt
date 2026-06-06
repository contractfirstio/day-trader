package daytrader.domain

data class WatchlistLabel(
    val id: String,
    val name: String,
    val createdAtEpochMs: Long
)

fun newWatchlistLabelId(): String = "wll-${kotlin.random.Random.nextLong().toULong().toString(16)}"

fun newWatchlistLabel(name: String, nowEpochMs: Long = System.currentTimeMillis()): WatchlistLabel? {
    val normalized = WatchlistLabels.normalizeName(name) ?: return null
    return WatchlistLabel(
        id = newWatchlistLabelId(),
        name = normalized,
        createdAtEpochMs = nowEpochMs
    )
}
