package daytrader.data.persistence

import daytrader.domain.StrategyInstance
import daytrader.domain.StrategyType
import kotlinx.serialization.Serializable

@Serializable
data class StrategyInstancesDocument(
    val version: Int = CURRENT_VERSION,
    val instances: List<StrategyInstance> = emptyList()
) {
    companion object {
        const val CURRENT_VERSION = 1
    }
}

@Serializable
data class StrategiesAppStateDocument(
    val version: Int = CURRENT_VERSION,
    val selectedInstanceId: String? = null,
    val searchQuery: String = "",
    val instanceFilter: String = "ALL",
    val strategyTypeFilter: StrategyType? = null,
    val detailTab: String = "CONFIGURATION"
) {
    companion object {
        const val CURRENT_VERSION = 1
    }
}
