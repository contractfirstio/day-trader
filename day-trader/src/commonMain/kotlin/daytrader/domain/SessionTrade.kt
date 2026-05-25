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
