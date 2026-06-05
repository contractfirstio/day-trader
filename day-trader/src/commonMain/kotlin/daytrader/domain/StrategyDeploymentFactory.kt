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
    marketZoneId: String = RthMarketSessions.US.zoneId,
    currencyCode: String = "USD",
    marketSource: MarketSource = MarketSource.LEGACY_INFERRED,
    companyName: String? = null,
    instrument: InstrumentIdentity? = null,
    status: DeploymentStatus = DeploymentStatus.STOPPED
): StrategyDeployment {
    val symbolUpper = symbol.trim().uppercase()
    return StrategyDeployment(
        id = newStrategyDeploymentId(),
        strategyType = strategyType,
        status = status,
        symbol = symbolUpper,
        marketZoneId = marketZoneId,
        currencyCode = currencyCode,
        marketSource = marketSource,
        companyName = companyName?.trim()?.takeIf { it.isNotBlank() },
        instrument = instrument,
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
    touchTurnPrepare = null,
    lastAutoStartSessionDate = null
)
