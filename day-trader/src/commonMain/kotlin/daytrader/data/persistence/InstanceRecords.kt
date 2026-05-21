package daytrader.data.persistence

import daytrader.domain.StrategyType
import kotlinx.serialization.Serializable

@Serializable
data class InstancesDocument(
    val instances: List<InstanceRecord> = emptyList()
)

@Serializable
data class InstanceRecord(
    val id: String,
    val strategy: StrategyType,
    val status: String,
    val configuration: ConfigurationRecord,
    val live: LiveRecord,
    val performance: List<PerformanceDayRecord> = emptyList()
)

@Serializable
data class ConfigurationRecord(
    val symbol: String,
    val maxAtRisk: Int
)

@Serializable
data class LiveRecord(
    val state: String,
    val side: String,
    val quantity: Int = 0,
    val entry: Double? = null,
    val stop: Double? = null,
    val target: Double? = null,
    val market: Double? = null,
    val orderStatus: String = "—",
    val updatedAt: String = "—"
)

@Serializable
data class PerformanceDayRecord(
    val id: String,
    val date: String,
    val pnl: Double,
    val trades: Int,
    val maxAtRisk: Int,
    val status: String
)

@Serializable
data class StrategiesScreenDocument(
    val selectedInstanceId: String? = null,
    val search: String = "",
    val statusFilter: String = "all",
    val strategyFilter: StrategyType? = null,
    val detailTab: String = "configuration"
)
