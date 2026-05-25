package daytrader.data

import daytrader.gateway.AccountPosition
import daytrader.gateway.BrokerGateway
import daytrader.broker.SessionTradeMatcher
import daytrader.broker.SymbolMarkets
import daytrader.domain.currentRunTimestampIso
import daytrader.domain.InstanceRunStopLogic
import daytrader.domain.InstanceStatus
import daytrader.domain.SessionStopAction
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
 * Auto-stops running instances per [StrategyCatalog.stopAfterMinOpen]:
 * flat (no IB position and no open orders) after the deadline, or at RTH close if still exposed.
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
            val stopAfterMinOpen = StrategyCatalog.stopAfterMinOpen(instance.strategyType)
            val action = InstanceRunStopLogic.evaluateForInstance(
                instance = instance,
                stopAfterMinOpen = stopAfterMinOpen,
                positions = positions,
                openOrders = openOrders,
                nowEpochMillis = now
            ) ?: continue
            when (action) {
                SessionStopAction.CONTINUE,
                SessionStopAction.STOP_TRADE_OUTCOME_KNOWN -> Unit
                SessionStopAction.STOP_FLAT_AFTER_OPEN,
                SessionStopAction.STOP_AT_MARKET_CLOSE -> stopInstance(instance, positions)
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
