package daytrader.data.persistence

import daytrader.data.StrategiesAppState
import daytrader.domain.StrategyType
import daytrader.presentation.strategies.InstanceFilter
import daytrader.presentation.strategies.StrategyDetailTab

object StrategiesAppStatePersistence {
    fun fromDocument(document: StrategiesScreenDocument): StrategiesAppState =
        StrategiesAppState(
            selectedInstanceId = document.selectedInstanceId,
            searchQuery = document.search,
            instanceFilter = parseStatusFilter(document.statusFilter),
            strategyTypeFilter = document.strategyFilter,
            detailTab = parseDetailTab(document.detailTab)
        )

    fun toDocument(state: StrategiesAppState): StrategiesScreenDocument =
        StrategiesScreenDocument(
            selectedInstanceId = state.selectedInstanceId,
            search = state.searchQuery,
            statusFilter = statusFilterLabel(state.instanceFilter),
            strategyFilter = state.strategyTypeFilter,
            detailTab = detailTabLabel(state.detailTab)
        )

    private fun parseStatusFilter(value: String): InstanceFilter =
        when (value.lowercase()) {
            "running" -> InstanceFilter.RUNNING
            "stopped" -> InstanceFilter.STOPPED
            else -> runCatching { InstanceFilter.valueOf(value.uppercase()) }
                .getOrDefault(InstanceFilter.ALL)
        }

    private fun statusFilterLabel(filter: InstanceFilter): String = when (filter) {
        InstanceFilter.ALL -> "all"
        InstanceFilter.RUNNING -> "running"
        InstanceFilter.STOPPED -> "stopped"
    }

    private fun parseDetailTab(value: String): StrategyDetailTab =
        when (value.lowercase()) {
            "activity", "live" -> StrategyDetailTab.LIVE
            "performance" -> StrategyDetailTab.PERFORMANCE
            "configuration", "config" -> StrategyDetailTab.CONFIGURATION
            else -> runCatching { StrategyDetailTab.valueOf(value.uppercase()) }
                .getOrDefault(StrategyDetailTab.CONFIGURATION)
        }

    private fun detailTabLabel(tab: StrategyDetailTab): String = when (tab) {
        StrategyDetailTab.CONFIGURATION -> "configuration"
        StrategyDetailTab.LIVE -> "live"
        StrategyDetailTab.PERFORMANCE -> "performance"
    }
}
