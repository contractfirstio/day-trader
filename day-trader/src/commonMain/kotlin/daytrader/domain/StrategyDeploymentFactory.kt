package daytrader.domain

fun newStrategyDeploymentId(): String = "inst-${kotlin.random.Random.nextLong().toULong().toString(16)}"

fun instanceDisplayName(strategyType: StrategyType, symbol: String): String = when (strategyType) {
    StrategyType.TOUCH_AND_TURN_SCALPER -> "Touch and Turn — $symbol"
    StrategyType.QUICK_FLIP_SCALPER -> "Quick Flip — $symbol"
}

fun defaultStrategyDeployment(
    strategyType: StrategyType,
    symbol: String,
    maxDollars: Int,
    status: DeploymentStatus = DeploymentStatus.STOPPED
): StrategyDeployment {
    val symbolUpper = symbol.trim().uppercase()
    return StrategyDeployment(
        id = newStrategyDeploymentId(),
        strategyType = strategyType,
        status = status,
        symbol = symbolUpper,
        maxDollars = maxDollars,
        sessionHistory = emptyList(),
        live = ActiveExecution.flat()
    )
}

fun duplicateStrategyDeployment(source: StrategyDeployment): StrategyDeployment = source.copy(
    id = newStrategyDeploymentId(),
    status = DeploymentStatus.STOPPED,
    sessionHistory = emptyList(),
    live = ActiveExecution.flat(),
    touchTurnSession = null,
    lastAutoStartSessionDate = null
)
