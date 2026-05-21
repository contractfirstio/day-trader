package daytrader.presentation.strategies

import daytrader.domain.StrategyInstance
import daytrader.domain.StrategyType

enum class InstanceFilter {
    ALL,
    RUNNING,
    STOPPED
}

enum class StrategyDetailTab {
    CONFIGURATION,
    ACTIVITY,
    PERFORMANCE
}

data class StrategyInstanceRowUi(
    val id: String,
    val name: String,
    val strategyTypeLabel: String,
    val status: daytrader.domain.InstanceStatus,
    val formattedTodayPnL: String,
    val isPositivePnL: Boolean,
    val paramsSummary: String,
    val tradesToday: Int
)

data class StrategiesUiState(
    val filteredRows: List<StrategyInstanceRowUi> = emptyList(),
    val filteredCount: Int = 0,
    val selectedInstance: StrategyInstance? = null,
    val searchQuery: String = "",
    val instanceFilter: InstanceFilter = InstanceFilter.ALL,
    val strategyTypeFilter: StrategyType? = null,
    val detailTab: StrategyDetailTab = StrategyDetailTab.CONFIGURATION,
    val showAddDialog: Boolean = false,
    val selectedInstanceId: String? = null
)
