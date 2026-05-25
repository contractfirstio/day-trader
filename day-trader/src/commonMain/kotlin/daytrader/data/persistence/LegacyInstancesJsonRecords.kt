package daytrader.data.persistence

import daytrader.domain.StrategyType
import kotlinx.serialization.Serializable

/** `instances.json` schema before deployment/session terminology (instances + performance keys). */
@Serializable
internal data class LegacyInstancesJsonDocument(
    val instances: List<LegacyInstancesJsonRecord> = emptyList()
)

@Serializable
internal data class LegacyInstancesJsonRecord(
    val id: String,
    val strategy: StrategyType,
    val status: String,
    val configuration: ConfigurationRecord,
    val live: LiveRecord,
    val performance: List<SessionHistoryRecord> = emptyList(),
    val touchTurnSession: TouchTurnSessionRecord? = null
)
