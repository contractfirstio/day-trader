package daytrader.domain

fun newStrategyInstanceId(): String = "inst-${kotlin.random.Random.nextLong().toULong().toString(16)}"

fun instanceDisplayName(strategyType: StrategyType, symbol: String): String = when (strategyType) {
    StrategyType.TOUCH_AND_TURN_SCALPER -> "Touch and Turn — $symbol"
    StrategyType.QUICK_FLIP_SCALPER -> "Quick Flip — $symbol"
}

fun defaultStrategyInstance(
    strategyType: StrategyType,
    symbol: String,
    maxDollars: Int,
    status: InstanceStatus = InstanceStatus.STOPPED
): StrategyInstance {
    val symbolUpper = symbol.trim().uppercase()
    return StrategyInstance(
        id = newStrategyInstanceId(),
        strategyType = strategyType,
        status = status,
        symbol = symbolUpper,
        maxDollars = maxDollars,
        runs = emptyList(),
        todayPnL = 0.0,
        tradesToday = 0,
        lastSignal = "—",
        lastOrder = "—",
        openPosition = "Flat",
        lastUpdate = "—"
    )
}

fun duplicateStrategyInstance(source: StrategyInstance): StrategyInstance = source.copy(
    id = newStrategyInstanceId(),
    status = InstanceStatus.STOPPED,
    runs = emptyList(),
    todayPnL = 0.0,
    tradesToday = 0,
    lastSignal = "—",
    lastOrder = "—",
    openPosition = "Flat",
    lastUpdate = "—"
)
