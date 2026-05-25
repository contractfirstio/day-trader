package daytrader.data

import daytrader.domain.ActiveExecution
import daytrader.domain.ExecutionState
import daytrader.domain.DeploymentStatus
import daytrader.domain.SessionStatus
import daytrader.domain.StrategySession
import daytrader.domain.StrategyType
import daytrader.domain.TradeSide
import daytrader.domain.defaultStrategyDeployment
import daytrader.domain.newStrategySessionId

private const val MOCK_TODAY = "2026-05-21"

private fun mockSessionHistory(
    history: List<Triple<String, Double, Int>>,
    inProgress: Triple<String, Double, Int>? = null
): List<StrategySession> {
    val closed = history.map { (date, pnl, trades) ->
        StrategySession(
            id = newStrategySessionId(),
            date = date,
            pnl = pnl,
            trades = trades,
            maxAtRisk = 0,
            status = SessionStatus.CLOSED
        )
    }
    val live = inProgress?.let { (date, pnl, trades) ->
        StrategySession(
            id = newStrategySessionId(),
            date = date,
            pnl = pnl,
            trades = trades,
            maxAtRisk = 0,
            status = SessionStatus.IN_PROGRESS
        )
    }
    return closed + listOfNotNull(live)
}

fun mockStrategyDeployments() = listOf(
    defaultStrategyDeployment(
        strategyType = StrategyType.TOUCH_AND_TURN_SCALPER,
        symbol = "SPY",
        maxDollars = 500,
        status = DeploymentStatus.RUNNING
    ).let { instance ->
        instance.copy(
            sessionHistory = mockSessionHistory(
                history = listOf(
                    Triple("2026-05-19", 88.00, 5),
                    Triple("2026-05-20", 52.25, 4)
                ),
                inProgress = Triple(MOCK_TODAY, 142.50, 7)
            ).map { day ->
                day.copy(maxAtRisk = 500)
            },
            live = ActiveExecution(
                state = ExecutionState.FILLED,
                side = TradeSide.LONG,
                quantity = 100,
                entryPrice = 521.42,
                stopPrice = 521.10,
                targetPrice = 522.06,
                marketPrice = 521.60,
                orderStatus = "Filled 12:04:01",
                updatedAt = "12:04:03"
            )
        )
    },
    defaultStrategyDeployment(
        strategyType = StrategyType.TOUCH_AND_TURN_SCALPER,
        symbol = "QQQ",
        maxDollars = 350,
        status = DeploymentStatus.STOPPED
    ).let { instance ->
        instance.copy(
            sessionHistory = mockSessionHistory(
                history = listOf(
                    Triple("2026-05-19", 41.00, 2),
                    Triple("2026-05-20", -28.00, 3),
                    Triple(MOCK_TODAY, -12.50, 1)
                )
            ).map { day -> day.copy(maxAtRisk = 350) },
            live = ActiveExecution.flat(updatedAt = "11:52:18")
        )
    },
    defaultStrategyDeployment(
        strategyType = StrategyType.QUICK_FLIP_SCALPER,
        symbol = "NVDA",
        maxDollars = 250,
        status = DeploymentStatus.STOPPED
    ).let { instance ->
        instance.copy(
            sessionHistory = mockSessionHistory(
                history = listOf(
                    Triple("2026-05-17", 31.00, 8),
                    Triple("2026-05-19", -18.00, 6),
                    Triple("2026-05-20", 64.00, 12),
                    Triple(MOCK_TODAY, 22.00, 4)
                )
            ).map { day -> day.copy(maxAtRisk = 250) },
            live = ActiveExecution.flat(updatedAt = "12:01:44")
        )
    }
)
