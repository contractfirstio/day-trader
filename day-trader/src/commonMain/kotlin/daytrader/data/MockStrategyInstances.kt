package daytrader.data

import daytrader.domain.InstanceStatus
import daytrader.domain.StrategyType
import daytrader.domain.defaultStrategyInstance

fun mockStrategyInstances() = listOf(
    defaultStrategyInstance(
        strategyType = StrategyType.TOUCH_AND_TURN_SCALPER,
        symbol = "SPY",
        status = InstanceStatus.RUNNING
    ).copy(
        name = "T&T — SPY 1m",
        todayPnL = 142.50,
        tradesToday = 7,
        lastSignal = "Long @ 521.40 — touch of prior high",
        lastOrder = "BUY 100 SPY @ 521.42",
        openPosition = "Long 100 SPY",
        lastUpdate = "12:04:03"
    ),
    defaultStrategyInstance(
        strategyType = StrategyType.TOUCH_AND_TURN_SCALPER,
        symbol = "QQQ",
        status = InstanceStatus.STOPPED
    ).copy(
        name = "T&T — QQQ 5m",
        timeframe = "5m",
        riskDollars = 350,
        todayPnL = -28.00,
        tradesToday = 3,
        lastSignal = "Flat — no touch level",
        lastOrder = "—",
        openPosition = "Flat",
        lastUpdate = "11:52:18"
    ),
    defaultStrategyInstance(
        strategyType = StrategyType.QUICK_FLIP_SCALPER,
        symbol = "NVDA",
        status = InstanceStatus.STOPPED
    ).copy(
        name = "QF — NVDA 1m",
        todayPnL = 64.00,
        tradesToday = 12,
        lastSignal = "Short @ 485.10 — momentum flip",
        lastOrder = "SELL 50 NVDA @ 485.08",
        openPosition = "Flat",
        lastUpdate = "12:01:44"
    )
)
