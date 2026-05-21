package daytrader.domain

data class StrategyRun(
    val id: String,
    val date: String,
    val pnl: Double,
    val trades: Int,
    val maxAtRisk: Int,
    val status: RunStatus
)
