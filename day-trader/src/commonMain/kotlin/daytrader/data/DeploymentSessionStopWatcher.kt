package daytrader.data

import daytrader.gateway.AccountPosition
import daytrader.gateway.BrokerGateway
import daytrader.broker.SessionTradeMatcher
import daytrader.broker.SymbolMarkets
import daytrader.domain.currentSessionTimestampIso
import daytrader.domain.DeploymentSessionStopLogic
import daytrader.domain.DeploymentStatus
import daytrader.domain.DeploymentSessionStopAction
import daytrader.domain.SessionTrade
import daytrader.domain.StrategyDeployment
import daytrader.domain.inProgressSession
import daytrader.domain.SessionStopParams
import daytrader.domain.TouchTurnSessionStopTrigger
import daytrader.domain.onSessionStopped
import daytrader.domain.withTouchTurnClosingMilestoneIfNeeded
import daytrader.gateway.BrokerFill
import daytrader.diagnostics.SessionTrace
import daytrader.domain.resolveStopSnapshot
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Auto-stops running instances: strategy-specific rules (e.g. Touch Turn 90m open deadline) and
 * shared trade-outcome completion when flat.
 */
class DeploymentSessionStopWatcher(
    private val gateway: BrokerGateway,
    private val repository: StrategyDeploymentRepository,
    private val scope: CoroutineScope
) {
    fun start() {
        scope.launch {
            combine(
                gateway.positions,
                gateway.openOrders,
                gateway.fills
            ) { _, _, _ -> }
                .collect { checkRunningInstances() }
        }
        scope.launch {
            while (isActive) {
                delay(POLL_MS)
                checkRunningInstances()
            }
        }
    }

    private fun checkRunningInstances() {
        val now = System.currentTimeMillis()
        val positions = gateway.positions.value
        val openOrders = gateway.openOrders.value
        val fills = gateway.fills.value
        for (instance in repository.deployments.value) {
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
            val stopAfterDeadline =
                DeploymentSessionStopLogic.evaluateDeadlineForInstance(instance, now) ==
                    DeploymentSessionStopAction.STOP_AFTER_OPEN_DEADLINE
            if (stopAfterTrade || stopAfterDeadline) {
                SessionTrace.autoStopCheck(
                    deploymentId = instance.id,
                    symbol = instance.symbol,
                    sessionId = instance.inProgressSession()?.id,
                    wouldStop = true,
                    hasOpenPosition = hasOpenPosition,
                    hasOpenOrders = hasOpenOrders,
                    tradeCycleComplete = stopAfterTrade
                )
                stampClosingMilestone(instance.id)
            }
            if (stopAfterTrade) {
                stopInstance(instance, positions, TouchTurnSessionStopTrigger.TRADE_OUTCOME_KNOWN)
                continue
            }
            when (DeploymentSessionStopLogic.evaluateDeadlineForInstance(instance, now)) {
                DeploymentSessionStopAction.STOP_AFTER_OPEN_DEADLINE ->
                    stopInstance(instance, positions, TouchTurnSessionStopTrigger.OPEN_DEADLINE)
                DeploymentSessionStopAction.CONTINUE,
                DeploymentSessionStopAction.STOP_TRADE_OUTCOME_KNOWN,
                null -> Unit
            }
        }
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

    private fun stampClosingMilestone(instanceId: String) {
        repository.update(instanceId) { it.withTouchTurnClosingMilestoneIfNeeded() }
    }

    private fun stopInstance(
        instance: StrategyDeployment,
        positions: List<AccountPosition>,
        stopTrigger: TouchTurnSessionStopTrigger
    ) {
        val hasOpenOrders = SymbolMarkets.hasOpenOrders(instance, gateway.openOrders.value)
        val brokerPosition = SymbolMarkets.findOpenPosition(instance, positions)
        SessionStopOrderCleanup.flattenSymbolForSession(gateway, instance.symbol)
        val stoppedAt = currentSessionTimestampIso()
        val sessionTrades = SessionTradeMatcher.captureForSessionStop(
            instance = instance,
            fills = gateway.fills.value,
            stoppedAt = stoppedAt
        )
        repository.update(instance.id) { current ->
            val snapshot = current.resolveStopSnapshot(
                hadOpenBrokerPosition = brokerPosition != null,
                brokerUnrealizedPnL = brokerPosition?.totalUnrealizedPnL,
                sessionTrades = sessionTrades
            )
            current.onSessionStopped(
                stoppedAt = stoppedAt,
                snapshot = snapshot,
                stopParams = SessionStopParams(
                    stopTrigger = stopTrigger,
                    brokerId = gateway.brokerId,
                    brokerUnrealizedPnLAtStop = brokerPosition?.totalUnrealizedPnL,
                    hasOpenPosition = brokerPosition != null,
                    hasOpenOrders = hasOpenOrders
                )
            )
        }
        repository.flushPersistence()
    }

    private companion object {
        const val POLL_MS = 30_000L
    }
}
