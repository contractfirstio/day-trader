package daytrader.data

import daytrader.broker.SymbolMarkets
import daytrader.domain.DeploymentStatus
import daytrader.domain.StrategyDeployment
import daytrader.domain.TouchTurnSessionStopTrigger
import daytrader.gateway.AccountPosition
import daytrader.gateway.BrokerFill
import daytrader.gateway.BrokerGateway
import daytrader.gateway.BrokerKind
import daytrader.gateway.WorkingOrder

/**
 * Emergency stop: flatten broker exposure and stop every running deployment.
 */
object GlobalSessionKillSwitch {
    fun activate(
        repository: StrategyDeploymentRepository,
        gateway: BrokerGateway?,
        brokerKind: BrokerKind? = null,
        brokerPositions: List<AccountPosition> = emptyList(),
        brokerOpenOrders: List<WorkingOrder> = emptyList(),
        brokerFills: List<BrokerFill> = emptyList(),
    ): List<StrategyDeployment> {
        val running = repository.deployments.value.filter { it.status == DeploymentStatus.RUNNING }
        val stopped = RunningSessionShutdown.stopAllRunning(
            repository = repository,
            gateway = gateway,
            brokerKind = brokerKind,
            brokerPositions = brokerPositions,
            brokerOpenOrders = brokerOpenOrders,
            brokerFills = brokerFills,
            trigger = TouchTurnSessionStopTrigger.GLOBAL_KILL_SWITCH,
        )
        flattenRemainingBrokerExposure(
            gateway = gateway,
            sessionSymbols = running.map { it.symbol }.toSet(),
            brokerPositions = brokerPositions,
            brokerOpenOrders = brokerOpenOrders,
        )
        return stopped
    }

    internal fun flattenRemainingBrokerExposure(
        gateway: BrokerGateway?,
        sessionSymbols: Set<String>,
        brokerPositions: List<AccountPosition>,
        brokerOpenOrders: List<WorkingOrder>,
    ) {
        if (gateway == null) return
        val remainingSymbols = linkedSetOf<String>()
        brokerOpenOrders.forEach { order ->
            if (sessionSymbols.none { SymbolMarkets.symbolsMatch(order.symbol, it) }) {
                remainingSymbols += order.symbol
            }
        }
        brokerPositions.forEach { position ->
            if (position.quantity != 0 &&
                sessionSymbols.none { SymbolMarkets.symbolsMatch(position.symbol, it) }
            ) {
                remainingSymbols += position.symbol
            }
        }
        remainingSymbols.forEach { symbol ->
            gateway.flattenSymbolForSymbol(symbol)
        }
    }
}
