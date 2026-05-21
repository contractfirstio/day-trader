package daytrader.domain

import kotlinx.serialization.Serializable

@Serializable
data class StrategyRun(
    val id: String,
    val instanceId: String,
    val sessionDate: String,
    val pnl: Double,
    val trades: Int,
    val maxDollarsAtRun: Int,
    val status: RunStatus
)
