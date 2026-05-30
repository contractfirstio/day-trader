package daytrader.data

import daytrader.domain.DeploymentStatus
import daytrader.domain.StrategyDeployment
import daytrader.domain.TouchTurnSessionStopTrigger
import daytrader.gateway.AccountPosition
import daytrader.gateway.BrokerFill
import daytrader.gateway.BrokerGateway
import daytrader.gateway.BrokerKind
import daytrader.gateway.WorkingOrder

/**
 * Stops every [DeploymentStatus.RUNNING] deployment and persists the result.
 * Used on application exit and on startup to clear orphaned in-progress rows from disk.
 */
object RunningSessionShutdown {
    fun stopAllRunning(
        repository: StrategyDeploymentRepository,
        gateway: BrokerGateway?,
        brokerKind: BrokerKind? = null,
        brokerPositions: List<AccountPosition> = emptyList(),
        brokerOpenOrders: List<WorkingOrder> = emptyList(),
        brokerFills: List<BrokerFill> = emptyList(),
        trigger: TouchTurnSessionStopTrigger = TouchTurnSessionStopTrigger.APPLICATION_SHUTDOWN
    ): List<StrategyDeployment> {
        val running = repository.deployments.value.filter { it.status == DeploymentStatus.RUNNING }
        if (running.isEmpty()) return emptyList()
        val stopped = running.map { instance ->
            val result = TouchTurnManualStopHandler.stop(
                input = TouchTurnManualStopHandler.Input(
                    instance = instance,
                    brokerPositions = brokerPositions,
                    brokerOpenOrders = brokerOpenOrders,
                    brokerFills = brokerFills,
                    brokerKind = brokerKind
                ),
                gateway = gateway,
                explicitTrigger = trigger
            )
            repository.update(instance.id) { result.stoppedDeployment }
            result.stoppedDeployment
        }
        repository.flushPersistence()
        return stopped
    }
}
