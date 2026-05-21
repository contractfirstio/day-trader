package daytrader.domain

import kotlinx.serialization.Serializable

@Serializable
data class StrategyInstance(
    val id: String,
    val strategyType: StrategyType,
    val status: InstanceStatus,
    val symbol: String,
    val maxDollars: Int,
    val runs: List<StrategyRun> = emptyList(),
    val todayPnL: Double,
    val tradesToday: Int,
    val lastSignal: String,
    val lastOrder: String,
    val openPosition: String,
    val lastUpdate: String
)
