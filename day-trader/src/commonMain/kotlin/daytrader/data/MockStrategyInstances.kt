package daytrader.data

import daytrader.domain.InstanceStatus
import daytrader.domain.StrategyType
import daytrader.domain.defaultStrategyInstance

fun mockStrategyInstances() = listOf(
    defaultStrategyInstance(
        strategyType = StrategyType.TOUCH_AND_TURN_SCALPER,
        name = "T&T — SPY 1m",
        symbol = "SPY",
        timeframe = "1m",
        riskDollars = 500,
        status = InstanceStatus.RUNNING
    ).copy(
        todayPnL = 142.50,
        tradesToday = 7,
        lastSignal = "Long @ 521.40 — touch of prior high",
        lastOrder = "BUY 100 SPY @ 521.42",
        openPosition = "Long 100 SPY",
        lastUpdate = "12:04:03"
    ),
    defaultStrategyInstance(
        strategyType = StrategyType.TOUCH_AND_TURN_SCALPER,
        name = "T&T — QQQ 5m",
        symbol = "QQQ",
        timeframe = "5m",
        riskDollars = 350,
        status = InstanceStatus.STOPPED
    ).copy(
        todayPnL = -28.00,
        tradesToday = 3,
        lastSignal = "Flat — no touch level",
        lastOrder = "—",
        openPosition = "Flat",
        lastUpdate = "11:52:18"
    ),
    defaultStrategyInstance(
        strategyType = StrategyType.QUICK_FLIP_SCALPER,
        name = "QF — NVDA 1m",
        symbol = "NVDA",
        timeframe = "1m",
        riskDollars = 250,
        status = InstanceStatus.STOPPED
    ).copy(
        todayPnL = 64.00,
        tradesToday = 12,
        lastSignal = "Short @ 485.10 — momentum flip",
        lastOrder = "SELL 50 NVDA @ 485.08",
        openPosition = "Flat",
        lastUpdate = "12:01:44"
    )
)
