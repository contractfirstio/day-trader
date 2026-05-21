package daytrader.data.persistence

import daytrader.domain.ActiveExecution
import daytrader.domain.InstanceStatus
import daytrader.domain.RunStatus
import daytrader.domain.StrategyType
import kotlinx.serialization.Serializable

@Serializable
internal data class LegacyInstancesDocument(
    val instances: List<LegacyInstanceRecord> = emptyList()
)

@Serializable
internal data class LegacyInstanceRecord(
    val id: String,
    val strategyType: StrategyType,
    val status: InstanceStatus,
    val symbol: String,
    val maxDollars: Int,
    val runs: List<LegacyPerformanceDayRecord> = emptyList(),
    val activeExecution: ActiveExecution = ActiveExecution(),
    val todayPnL: Double = 0.0,
    val tradesToday: Int = 0
)

@Serializable
internal data class LegacyPerformanceDayRecord(
    val id: String,
    val instanceId: String = "",
    val sessionDate: String,
    val pnl: Double,
    val trades: Int,
    val maxDollarsAtRun: Int,
    val status: RunStatus
)

@Serializable
internal data class LegacyStrategiesScreenDocument(
    val selectedInstanceId: String? = null,
    val searchQuery: String = "",
    val instanceFilter: String = "ALL",
    val strategyTypeFilter: StrategyType? = null,
    val detailTab: String = "CONFIGURATION"
)
