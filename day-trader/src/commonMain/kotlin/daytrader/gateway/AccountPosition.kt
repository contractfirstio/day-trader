package daytrader.gateway

data class AccountPosition(
    val account: String,
    val symbol: String,
    val companyName: String,
    val quantity: Int,
    val avgPrice: Double,
    val marketPrice: Double,
    val priorClose: Double?,
    val totalUnrealizedPnL: Double,
    val currency: String
) {
    val dailyChangePct: Double
        get() {
            val close = priorClose
            if (close == null || close <= 0.0) return 0.0
            return ((marketPrice - close) / close) * 100.0
        }
}
