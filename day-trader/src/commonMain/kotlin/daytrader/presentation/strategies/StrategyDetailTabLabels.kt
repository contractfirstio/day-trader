package daytrader.presentation.strategies

fun StrategyDetailTab.displayLabel(): String = when (this) {
    StrategyDetailTab.CONFIGURATION -> "Config"
    StrategyDetailTab.LIVE -> "Trading"
    StrategyDetailTab.SESSION_HISTORY -> "Session history"
}

fun DeploymentFilter.statusFilterLabel(): String = when (this) {
    DeploymentFilter.ALL -> "All"
    DeploymentFilter.RUNNING -> "Active"
    DeploymentFilter.STOPPED -> "Stopped"
}

fun DeploymentFilter.activeFilterHint(): String? = when (this) {
    DeploymentFilter.ALL -> null
    DeploymentFilter.RUNNING -> "active only"
    DeploymentFilter.STOPPED -> "stopped only"
}
