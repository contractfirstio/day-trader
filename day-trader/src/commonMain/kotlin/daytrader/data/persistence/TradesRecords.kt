package daytrader.data.persistence

import kotlinx.serialization.Serializable

@Serializable
data class BrokerFillRecord(
    val execId: String,
    val orderId: Int,
    val permId: Long,
    val parentOrderId: Int = 0,
    val symbol: String,
    val side: String,
    val quantity: Int,
    val price: Double,
    val time: String,
    val currency: String = "USD",
    val commission: Double? = null,
    val realizedPnL: Double? = null,
)

@Serializable
data class TradesDocument(
    val fills: List<BrokerFillRecord> = emptyList(),
)
