package daytrader.data

import daytrader.domain.ActiveExecution
import daytrader.domain.ExecutionState
import daytrader.domain.InstanceStatus
import daytrader.domain.StrategyInstance
import daytrader.domain.StrategyType
import daytrader.domain.TradeSide
import daytrader.domain.updateInProgressRun

private data class DemoPrices(val entry: Double, val stop: Double, val market: Double)

private fun pricesFor(symbol: String): DemoPrices = when (symbol.uppercase()) {
    "SPY" -> DemoPrices(521.42, 521.10, 521.60)
    "QQQ" -> DemoPrices(440.00, 439.70, 440.25)
    "NVDA" -> DemoPrices(485.08, 484.80, 485.20)
    "AAPL" -> DemoPrices(198.50, 198.20, 198.72)
    else -> DemoPrices(100.00, 99.75, 100.18)
}

fun demoActiveExecution(symbol: String, strategyType: StrategyType): ActiveExecution {
    val prices = pricesFor(symbol)
    val quantity = when (strategyType) {
        StrategyType.QUICK_FLIP_SCALPER -> 50
        StrategyType.TOUCH_AND_TURN_SCALPER -> 100
    }
    val rewardMultiple = StrategyCatalog.rewardMultiple(strategyType)
    val r = prices.entry - prices.stop
    val target = prices.entry + (r * rewardMultiple)

    return ActiveExecution(
        state = ExecutionState.FILLED,
        side = TradeSide.LONG,
        quantity = quantity,
        entryPrice = prices.entry,
        stopPrice = prices.stop,
        targetPrice = target,
        marketPrice = prices.market,
        orderStatus = "Filled (demo)",
        updatedAt = "Demo"
    )
}

fun demoUnrealizedPnL(execution: ActiveExecution): Double {
    val entry = execution.entryPrice ?: return 0.0
    val market = execution.marketPrice ?: return 0.0
    val qty = execution.quantity
    return when (execution.side) {
        TradeSide.LONG -> (market - entry) * qty
        TradeSide.SHORT -> (entry - market) * qty
    }
}

/** Populates a filled demo execution when an instance is started (UI preview only). */
fun StrategyInstance.withDemoLiveExecutionOnStart(sessionDate: String): StrategyInstance {
    if (status != InstanceStatus.RUNNING) return this
    val execution = demoActiveExecution(symbol, strategyType)
    val unrealized = demoUnrealizedPnL(execution)
    return copy(live = execution).updateInProgressRun { day ->
        day.copy(
            pnl = unrealized,
            trades = maxOf(day.trades, 1)
        )
    }
}
