package daytrader.gateway

/**
 * A single broker execution (fill), correlated to an order via [permId] and [orderId].
 * [realizedPnL] and [commission] arrive asynchronously from commission reports keyed by [execId].
 * On the closing fill, [realizedPnL] is IB's net round-trip P&L (commissions already netted).
 */
data class BrokerFill(
    val execId: String,
    val orderId: Int,
    val permId: Long,
    val parentOrderId: Int,
    val symbol: String,
    val side: String,
    val quantity: Int,
    val price: Double,
    val time: String,
    val currency: String = "USD",
    val commission: Double? = null,
    val realizedPnL: Double? = null,
)
