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
    /** Per-fill commission from the broker commission report (shown separately; do not subtract again). */
    val commission: Double? = null,
    /**
     * Per-fill realized P&L from the broker commission report. On entry fills this is 0.
     * On the closing fill IB reports the net round-trip P&L (price move with commissions
     * already netted into cost basis — see IBKR Realized P&L glossary).
     */
    val realizedPnL: Double? = null,
)

/** Sum of per-fill [SessionTrade.realizedPnL] — net session P&L when the round-trip is closed. */
fun List<SessionTrade>.sessionRealizedPnL(): Double =
    sumOf { it.realizedPnL ?: 0.0 }

fun List<SessionTrade>.sessionCommissionTotal(): Double =
    sumOf { it.commission ?: 0.0 }

/**
 * Price-move P&L before commissions, derived when every fill has commission data.
 * IB stores net realized on the closing fill; gross = net + commissions.
 */
fun List<SessionTrade>.sessionGrossPricePnL(): Double =
    sessionRealizedPnL() + sessionCommissionTotal()

/** @see [sessionRealizedPnL] — IB net realized is already after commissions. */
fun List<SessionTrade>.sessionNetPnL(): Double = sessionRealizedPnL()

fun List<SessionTrade>.hasCompleteCommissionData(): Boolean =
    isNotEmpty() && all { it.commission != null }

/** Session P&L for display and rollups — [sessionRealizedPnL] (IB net when closed). */
fun List<SessionTrade>.sessionDisplayPnL(): Double = sessionRealizedPnL()

/**
 * Capital put to work on the entry leg: sum of entry-fill qty × price.
 * Uses the first parent (entry) order; partial entry fills are included.
 * Exit / OPEN_DEADLINE closes with a different order id are excluded even when
 * [SessionTrade.parentOrderId] is 0.
 */
fun List<SessionTrade>.sessionEntryNotional(): Double {
    val entryOrderId = firstOrNull { it.parentOrderId == 0 }?.orderId ?: return 0.0
    return filter { it.parentOrderId == 0 && it.orderId == entryOrderId }
        .sumOf { it.quantity * it.price }
}

fun List<SessionTrade>.dedupeByExecId(): List<SessionTrade> {
    val seen = LinkedHashSet<String>()
    return filter { trade -> seen.add(trade.execId) }
}

/** User-facing trade count: one completed or partial round-trip, not raw fill rows. */
fun List<SessionTrade>.roundTripCount(): Int {
    if (isEmpty()) return 0
    val hasEntry = any { it.parentOrderId == 0 }
    val hasExit = hasClosingFill()
    return when {
        hasEntry && hasExit -> 1
        hasEntry || hasExit -> 1
        else -> 0
    }
}

/**
 * True when session trades include a bracket exit leg or an OPEN_DEADLINE market-close fill
 * (standalone MKT orders also use [SessionTrade.parentOrderId] == 0).
 */
fun List<SessionTrade>.hasClosingFill(): Boolean {
    if (any { it.parentOrderId != 0 }) return true
    if (any { (it.realizedPnL ?: 0.0) != 0.0 }) return true
    if (size < 2) return false
    val entryOrderId = firstOrNull { it.parentOrderId == 0 }?.orderId
    return any { it.parentOrderId == 0 && entryOrderId != null && it.orderId != entryOrderId }
}
