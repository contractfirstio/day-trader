package daytrader.presentation.strategies

fun StrategyDetailTab.displayLabel(): String = when (this) {
    StrategyDetailTab.CONFIGURATION -> "Config"
    StrategyDetailTab.LIVE -> "Trading"
    StrategyDetailTab.PERFORMANCE -> "Session history"
}

fun InstanceFilter.statusFilterLabel(): String = when (this) {
    InstanceFilter.ALL -> "All"
    InstanceFilter.RUNNING -> "Active"
    InstanceFilter.STOPPED -> "Stopped"
}

fun InstanceFilter.activeFilterHint(): String? = when (this) {
    InstanceFilter.ALL -> null
    InstanceFilter.RUNNING -> "active only"
    InstanceFilter.STOPPED -> "stopped only"
}
