package daytrader.domain

import daytrader.data.StrategyCatalog

fun newStrategyInstanceId(): String = "inst-${kotlin.random.Random.nextLong().toULong().toString(16)}"

fun defaultInstanceName(strategyType: StrategyType, symbol: String): String = when (strategyType) {
    StrategyType.TOUCH_AND_TURN_SCALPER -> "Touch and Turn — $symbol"
    StrategyType.QUICK_FLIP_SCALPER -> "Quick Flip — $symbol"
}

fun defaultStrategyInstance(
    strategyType: StrategyType,
    name: String,
    symbol: String,
    timeframe: String,
    riskDollars: Int,
    status: InstanceStatus = InstanceStatus.STOPPED
): StrategyInstance {
    val defaults = StrategyCatalog.defaultsFor(strategyType)
    return StrategyInstance(
        id = newStrategyInstanceId(),
        name = name,
        strategyType = strategyType,
        status = status,
        symbol = symbol,
        timeframe = timeframe,
        riskDollars = riskDollars,
        positionSize = defaults.positionSize,
        stopLossTicks = defaults.stopLossTicks,
        sessionWindow = defaults.sessionWindow,
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
    name = "${source.name} (copy)",
    status = InstanceStatus.STOPPED,
    todayPnL = 0.0,
    tradesToday = 0,
    lastSignal = "—",
    lastOrder = "—",
    openPosition = "Flat",
    lastUpdate = "—"
)
