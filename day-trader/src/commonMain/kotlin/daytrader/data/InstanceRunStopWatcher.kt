package daytrader.data

import daytrader.gateway.AccountPosition
import daytrader.gateway.BrokerGateway
import daytrader.broker.SessionTradeMatcher
import daytrader.broker.SymbolMarkets
import daytrader.domain.currentRunTimestampIso
import daytrader.domain.InstanceRunStopLogic
import daytrader.domain.InstanceStatus
import daytrader.domain.InstanceRunStopAction
import daytrader.domain.SessionTrade
import daytrader.domain.StrategyInstance
import daytrader.domain.inProgressRun
import daytrader.domain.onRunStopped
import daytrader.gateway.BrokerFill
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
class InstanceRunStopWatcher(
    private val gateway: BrokerGateway,
    private val repository: StrategyInstanceRepository,
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
        for (instance in repository.instances.value) {
            if (instance.status != InstanceStatus.RUNNING) continue
            val hasOpenPosition = SymbolMarkets.hasOpenPosition(instance.symbol, positions)
            val hasOpenOrders = SymbolMarkets.hasOpenOrders(instance.symbol, openOrders)
            val sessionTrades = sessionTradesForRun(instance, fills)
            if (InstanceRunStopLogic.shouldStopAfterTradeOutcome(
                    instance = instance,
                    sessionTrades = sessionTrades,
                    hasOpenPosition = hasOpenPosition,
                    hasOpenOrders = hasOpenOrders
                )
            ) {
                stopInstance(instance, positions)
                continue
            }
            when (InstanceRunStopLogic.evaluateDeadlineForInstance(instance, now)) {
                InstanceRunStopAction.STOP_AFTER_OPEN_DEADLINE -> stopInstance(instance, positions)
                InstanceRunStopAction.CONTINUE,
                InstanceRunStopAction.STOP_TRADE_OUTCOME_KNOWN,
                null -> Unit
            }
        }
    }

    private fun sessionTradesForRun(
        instance: StrategyInstance,
        fills: List<BrokerFill>
    ): List<SessionTrade> {
        val run = instance.inProgressRun() ?: return emptyList()
        return SessionTradeMatcher.toSessionTrades(
            SessionTradeMatcher.fillsForSession(
                symbol = instance.symbol,
                startedAt = run.startedAt,
                stoppedAt = null,
                fills = fills
            )
        )
    }

    private fun stopInstance(instance: StrategyInstance, positions: List<AccountPosition>) {
        SessionStopOrderCleanup.flattenSymbolForSession(gateway, instance.symbol)
        val brokerPosition = SymbolMarkets.findOpenPosition(instance.symbol, positions)
        val stoppedAt = currentRunTimestampIso()
        val sessionTrades = SessionTradeMatcher.captureForRunStop(
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
            current.onRunStopped(stoppedAt = stoppedAt, snapshot = snapshot)
        }
        repository.flushPersistence()
    }

    private companion object {
        const val POLL_MS = 30_000L
    }
}
