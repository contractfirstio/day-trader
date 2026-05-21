package daytrader.data

import daytrader.domain.ActiveExecution
import daytrader.domain.ExecutionState
import daytrader.domain.InstanceStatus
import daytrader.domain.RunStatus
import daytrader.domain.StrategyRun
import daytrader.domain.StrategyType
import daytrader.domain.TradeSide
import daytrader.domain.defaultStrategyInstance
import daytrader.domain.newStrategyRunId

private const val MOCK_TODAY = "2026-05-21"

private fun mockRuns(
    instanceId: String,
    maxDollars: Int,
    history: List<Triple<String, Double, Int>>,
    inProgress: Triple<String, Double, Int>? = null
): List<StrategyRun> {
    val closed = history.map { (date, pnl, trades) ->
        StrategyRun(
            id = newStrategyRunId(),
            instanceId = instanceId,
            sessionDate = date,
            pnl = pnl,
            trades = trades,
            maxDollarsAtRun = maxDollars,
            status = RunStatus.CLOSED
        )
    }
    val live = inProgress?.let { (date, pnl, trades) ->
        StrategyRun(
            id = newStrategyRunId(),
            instanceId = instanceId,
            sessionDate = date,
            pnl = pnl,
            trades = trades,
            maxDollarsAtRun = maxDollars,
            status = RunStatus.IN_PROGRESS
        )
    }
    return closed + listOfNotNull(live)
}

fun mockStrategyInstances() = listOf(
    defaultStrategyInstance(
        strategyType = StrategyType.TOUCH_AND_TURN_SCALPER,
        symbol = "SPY",
        maxDollars = 500,
        status = InstanceStatus.RUNNING
    ).let { instance ->
        instance.copy(
            runs = mockRuns(
                instanceId = instance.id,
                maxDollars = 500,
                history = listOf(
                    Triple("2026-05-19", 88.00, 5),
                    Triple("2026-05-20", 52.25, 4)
                ),
                inProgress = Triple(MOCK_TODAY, 142.50, 7)
            ),
            activeExecution = ActiveExecution(
                state = ExecutionState.FILLED,
                side = TradeSide.LONG,
                quantity = 100,
                entryPrice = 521.42,
                stopPrice = 521.10,
                targetPrice = 522.06,
                marketPrice = 521.60,
                orderStatus = "Filled 12:04:01",
                updatedAt = "12:04:03"
            ),
            todayPnL = 142.50,
            tradesToday = 7,
            lastSignal = "Long @ 521.40 — touch of prior high",
            lastOrder = "BUY 100 SPY @ 521.42 (filled 12:04:01)",
            lastUpdate = "12:04:03"
        )
    },
    defaultStrategyInstance(
        strategyType = StrategyType.TOUCH_AND_TURN_SCALPER,
        symbol = "QQQ",
        maxDollars = 350,
        status = InstanceStatus.STOPPED
    ).let { instance ->
        instance.copy(
            runs = mockRuns(
                instanceId = instance.id,
                maxDollars = 350,
                history = listOf(
                    Triple("2026-05-19", 41.00, 2),
                    Triple("2026-05-20", -28.00, 3),
                    Triple(MOCK_TODAY, -12.50, 1)
                )
            ),
            todayPnL = -12.50,
            tradesToday = 1,
            activeExecution = ActiveExecution.flat(updatedAt = "11:52:18"),
            lastSignal = "Flat — no touch level",
            lastOrder = "—",
            lastUpdate = "11:52:18"
        )
    },
    defaultStrategyInstance(
        strategyType = StrategyType.QUICK_FLIP_SCALPER,
        symbol = "NVDA",
        maxDollars = 250,
        status = InstanceStatus.STOPPED
    ).let { instance ->
        instance.copy(
            runs = mockRuns(
                instanceId = instance.id,
                maxDollars = 250,
                history = listOf(
                    Triple("2026-05-17", 31.00, 8),
                    Triple("2026-05-19", -18.00, 6),
                    Triple("2026-05-20", 64.00, 12),
                    Triple(MOCK_TODAY, 22.00, 4)
                )
            ),
            todayPnL = 22.00,
            tradesToday = 4,
            activeExecution = ActiveExecution.flat(updatedAt = "12:01:44"),
            lastSignal = "Short @ 485.10 — momentum flip",
            lastOrder = "SELL 50 NVDA @ 485.08",
            lastUpdate = "12:01:44"
        )
    }
)
