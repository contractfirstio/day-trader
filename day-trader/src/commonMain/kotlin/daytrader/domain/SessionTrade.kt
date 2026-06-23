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
    /** Per-fill commission from the broker commission report. */
    val commission: Double? = null,
    /** Per-fill price-based realized P&L from the broker commission report. */
    val realizedPnL: Double? = null,
)

fun List<SessionTrade>.sessionRealizedPnL(): Double =
    sumOf { it.realizedPnL ?: 0.0 }

fun List<SessionTrade>.sessionCommissionTotal(): Double =
    sumOf { it.commission ?: 0.0 }

/** Gross realized P&L minus per-fill commissions (derived; not stored by IB). */
fun List<SessionTrade>.sessionNetPnL(): Double =
    sessionRealizedPnL() - sessionCommissionTotal()

fun List<SessionTrade>.hasCompleteCommissionData(): Boolean =
    isNotEmpty() && all { it.commission != null }

/** Net when every fill has commission; otherwise gross realized P&L. */
fun List<SessionTrade>.sessionDisplayPnL(): Double =
    if (hasCompleteCommissionData()) sessionNetPnL() else sessionRealizedPnL()

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
