package daytrader.data.persistence

import daytrader.domain.ActiveExecution
import daytrader.domain.DeploymentStatus
import daytrader.domain.SessionStatus
import daytrader.domain.StrategyType
import kotlinx.serialization.Serializable

@Serializable
internal data class LegacyDeploymentsDocument(
    val instances: List<LegacyDeploymentRecord> = emptyList()
)

@Serializable
internal data class LegacyDeploymentRecord(
    val id: String,
    val strategyType: StrategyType,
    val status: DeploymentStatus,
    val symbol: String,
    val maxDollars: Int,
    val runs: List<LegacySessionHistoryRecord> = emptyList(),
    val activeExecution: ActiveExecution = ActiveExecution(),
    val todayPnL: Double = 0.0,
    val tradesToday: Int = 0
)

@Serializable
internal data class LegacySessionHistoryRecord(
    val id: String,
    val instanceId: String = "",
    val sessionDate: String,
    val pnl: Double,
    val trades: Int,
    val maxDollarsAtRun: Int,
    val status: SessionStatus
)

@Serializable
internal data class LegacyStrategiesScreenDocument(
    val selectedInstanceId: String? = null,
    val selectedDeploymentId: String? = null,
    val searchQuery: String = "",
    val deploymentFilter: String = "ALL",
    val strategyTypeFilter: StrategyType? = null,
    val detailTab: String = "CONFIGURATION"
)
