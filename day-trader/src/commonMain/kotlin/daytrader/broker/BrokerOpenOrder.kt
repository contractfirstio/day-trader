package daytrader.broker

data class BrokerOpenOrder(
    val orderId: Int,
    val symbol: String,
    val action: String,
    val quantity: Int,
    val filled: Int,
    val remaining: Int,
    val orderType: String,
    val limitPrice: Double?,
    val stopPrice: Double?,
    val status: String,
    val currency: String
)
