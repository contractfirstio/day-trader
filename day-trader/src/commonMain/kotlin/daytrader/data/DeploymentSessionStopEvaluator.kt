package daytrader.data

import daytrader.broker.SessionTradeMatcher
import daytrader.broker.SymbolMarkets
import daytrader.domain.DeploymentSessionStopAction
import daytrader.domain.DeploymentSessionStopLogic
import daytrader.domain.DeploymentStatus
import daytrader.domain.SessionTrade
import daytrader.domain.StrategyDeployment
import daytrader.domain.TouchTurnSessionStopTrigger
import daytrader.domain.inProgressSession
import daytrader.gateway.AccountPosition
import daytrader.gateway.BrokerFill
import daytrader.gateway.WorkingOrder

/**
 * Pure evaluation of auto-stop conditions for running deployments.
 * Used by [daytrader.engine.TouchTurnEngine].
 */
object DeploymentSessionStopEvaluator {
    enum class StopReason {
        NO_TRADE_DECISION,
        TRADE_OUTCOME,
        OPEN_DEADLINE
    }

    data class StopCandidate(
        val instanceId: String,
        val reason: StopReason,
        val trigger: TouchTurnSessionStopTrigger
    )

    fun evaluate(
        deployments: List<StrategyDeployment>,
        positions: List<AccountPosition>,
        openOrders: List<WorkingOrder>,
        fills: List<BrokerFill>,
        nowEpochMillis: Long = System.currentTimeMillis()
    ): List<StopCandidate> {
        val candidates = mutableListOf<StopCandidate>()
        for (instance in deployments) {
            if (instance.status != DeploymentStatus.RUNNING) continue
            val hasOpenPosition = SymbolMarkets.hasOpenPosition(instance, positions)
            val hasOpenOrders = SymbolMarkets.hasOpenOrders(instance, openOrders)
            val sessionTrades = sessionTradesForRun(instance, fills)
            val stopAfterTrade = DeploymentSessionStopLogic.shouldStopAfterTradeOutcome(
                instance = instance,
                sessionTrades = sessionTrades,
                hasOpenPosition = hasOpenPosition,
                hasOpenOrders = hasOpenOrders
            )
            val stopAfterNoTradeDecision = DeploymentSessionStopLogic.shouldStopAfterNoTradeDecision(instance)
            val stopAfterDeadline =
                DeploymentSessionStopLogic.evaluateDeadlineForInstance(instance, nowEpochMillis) ==
                    DeploymentSessionStopAction.STOP_AFTER_OPEN_DEADLINE
            when {
                stopAfterNoTradeDecision -> candidates += StopCandidate(
                    instanceId = instance.id,
                    reason = StopReason.NO_TRADE_DECISION,
                    trigger = TouchTurnSessionStopTrigger.NO_TRADE_DECISION
                )
                stopAfterTrade -> candidates += StopCandidate(
                    instanceId = instance.id,
                    reason = StopReason.TRADE_OUTCOME,
                    trigger = TouchTurnSessionStopTrigger.TRADE_OUTCOME_KNOWN
                )
                stopAfterDeadline -> candidates += StopCandidate(
                    instanceId = instance.id,
                    reason = StopReason.OPEN_DEADLINE,
                    trigger = TouchTurnSessionStopTrigger.OPEN_DEADLINE
                )
            }
        }
        return candidates
    }

    private fun sessionTradesForRun(
        instance: StrategyDeployment,
        fills: List<BrokerFill>
    ): List<SessionTrade> {
        val run = instance.inProgressSession() ?: return emptyList()
        return SessionTradeMatcher.toSessionTrades(
            SessionTradeMatcher.fillsForSession(
                symbol = instance.symbol,
                startedAt = run.startedAt,
                stoppedAt = null,
                fills = fills
            )
        )
    }
}
