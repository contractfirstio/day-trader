package daytrader.domain

data class Position(
    val symbol: String,
    val companyName: String,
    val quantity: Int,
    val avgPrice: Double,
    val marketPrice: Double,
    val dailyChangePct: Double,
    val totalUnrealizedPnL: Double
) {
    val marketValue: Double get() = quantity * marketPrice
}
