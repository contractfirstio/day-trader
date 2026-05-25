package daytrader.data.persistence

import daytrader.data.StrategiesAppState
import daytrader.presentation.strategies.InstanceFilter
import daytrader.presentation.strategies.StrategyDetailTab

object StrategiesAppStatePersistence {
    fun fromDocument(document: StrategiesScreenDocument): StrategiesAppState =
        StrategiesAppState(
            selectedInstanceId = document.selectedInstanceId,
            detailTab = parseDetailTab(document.detailTab),
            globalAutoStartEnabled = document.globalAutoStartEnabled
        )

    fun toDocument(state: StrategiesAppState): StrategiesScreenDocument =
        StrategiesScreenDocument(
            selectedInstanceId = state.selectedInstanceId,
            detailTab = detailTabLabel(state.detailTab),
            globalAutoStartEnabled = state.globalAutoStartEnabled
        )

    private fun parseDetailTab(value: String): StrategyDetailTab =
        when (value.lowercase()) {
            "activity", "live", "trading" -> StrategyDetailTab.LIVE
            "performance", "session_history", "session history" -> StrategyDetailTab.PERFORMANCE
            "configuration", "config" -> StrategyDetailTab.CONFIGURATION
            else -> runCatching { StrategyDetailTab.valueOf(value.uppercase()) }
                .getOrDefault(StrategyDetailTab.CONFIGURATION)
        }

    private fun detailTabLabel(tab: StrategyDetailTab): String = when (tab) {
        StrategyDetailTab.CONFIGURATION -> "config"
        StrategyDetailTab.LIVE -> "trading"
        StrategyDetailTab.PERFORMANCE -> "session_history"
    }
}
