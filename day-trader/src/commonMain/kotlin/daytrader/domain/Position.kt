package daytrader.domain

data class Position(
    val symbol: String,
    val companyName: String,
    val quantity: Int,
    val avgPrice: Double,
    val marketPrice: Double,
    val dailyChangePct: Double,
    val totalUnrealizedPnL: Double,
    /** ISO currency for prices and unrealized P&L (e.g. GBP for LSE). */
    val currency: String = "USD"
) {
    val marketValue: Double get() = quantity * marketPrice
}
