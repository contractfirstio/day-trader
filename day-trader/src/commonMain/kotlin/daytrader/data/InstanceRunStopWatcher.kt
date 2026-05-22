package daytrader.data

import daytrader.broker.BrokerPosition
import daytrader.broker.IbGatewayConnection
import daytrader.broker.SymbolMarkets
import daytrader.domain.InstanceRunStopLogic
import daytrader.domain.InstanceStatus
import daytrader.domain.SessionStopAction
import daytrader.domain.StrategyInstance
import daytrader.domain.onRunStopped
import daytrader.domain.resolveStopSnapshot
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Auto-stops running instances per [StrategyCatalog.stopAfterMinOpen]:
 * flat (no IB position and no open orders) after the deadline, or at RTH close if still exposed.
 */
class InstanceRunStopWatcher(
    private val gateway: IbGatewayConnection,
    private val repository: StrategyInstanceRepository,
    private val scope: CoroutineScope
) {
    fun start() {
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
        for (instance in repository.instances.value) {
            if (instance.status != InstanceStatus.RUNNING) continue
            val stopAfterMinOpen = StrategyCatalog.stopAfterMinOpen(instance.strategyType)
            val action = InstanceRunStopLogic.evaluateForInstance(
                instance = instance,
                stopAfterMinOpen = stopAfterMinOpen,
                positions = positions,
                openOrders = openOrders,
                nowEpochMillis = now
            ) ?: continue
            when (action) {
                SessionStopAction.CONTINUE -> Unit
                SessionStopAction.STOP_FLAT_AFTER_OPEN,
                SessionStopAction.STOP_AT_MARKET_CLOSE -> stopInstance(instance, positions)
            }
        }
    }

    private fun stopInstance(instance: StrategyInstance, positions: List<BrokerPosition>) {
        val brokerPosition = SymbolMarkets.findOpenPosition(instance.symbol, positions)
        repository.update(instance.id) { current ->
            val snapshot = current.resolveStopSnapshot(
                hadOpenBrokerPosition = brokerPosition != null,
                brokerUnrealizedPnL = brokerPosition?.totalUnrealizedPnL
            )
            current.onRunStopped(snapshot = snapshot)
        }
        repository.flushPersistence()
    }

    private companion object {
        const val POLL_MS = 30_000L
    }
}
