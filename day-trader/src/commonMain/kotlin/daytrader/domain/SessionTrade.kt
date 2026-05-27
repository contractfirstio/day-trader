package daytrader.domain

/**
 * A broker fill captured for a strategy run cycle, used to verify session P&L after stop.
 */
data class SessionTrade(
    val execId: String,
    val orderId: Int,
    val permId: Long,
    val parentOrderId: Int,
    val side: String,
    val quantity: Int,
    val price: Double,
    val time: String,
    val currency: String = "USD",
    val commission: Double? = null,
    val realizedPnL: Double? = null
)

fun List<SessionTrade>.sessionRealizedPnL(): Double =
    sumOf { it.realizedPnL ?: 0.0 }

fun List<SessionTrade>.dedupeByExecId(): List<SessionTrade> {
    val seen = LinkedHashSet<String>()
    return filter { trade -> seen.add(trade.execId) }
}

/** User-facing trade count: one completed or partial round-trip, not raw fill rows. */
fun List<SessionTrade>.roundTripCount(): Int {
    if (isEmpty()) return 0
    val hasEntry = any { it.parentOrderId == 0 }
    val hasExit = any { it.parentOrderId != 0 }
    return when {
        hasEntry && hasExit -> 1
        hasEntry || hasExit -> 1
        else -> 0
    }
}
