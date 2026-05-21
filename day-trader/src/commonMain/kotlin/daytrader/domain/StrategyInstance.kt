package daytrader.domain

data class StrategyInstance(
    val id: String,
    val name: String,
    val strategyType: StrategyType,
    val status: InstanceStatus,
    val symbol: String,
    val timeframe: String,
    val riskDollars: Int,
    val positionSize: Int,
    val stopLossTicks: Int,
    val sessionWindow: String,
    val todayPnL: Double,
    val tradesToday: Int,
    val lastSignal: String,
    val lastOrder: String,
    val openPosition: String,
    val lastUpdate: String
)
