package daytrader.broker

import daytrader.domain.StrategyDeployment
import daytrader.gateway.AccountPosition
import daytrader.gateway.WorkingOrder

/**
 * Pre-resolved broker positions and orders keyed by deployment, built once per broker snapshot.
 * Avoids O(deployments × orders) linear scans on every UI row during refresh.
 */
class BrokerDeploymentIndex private constructor(
    private val openPositionByDeploymentId: Map<String, AccountPosition>,
    private val openOrdersByDeploymentId: Map<String, List<WorkingOrder>>,
    private val ordersByNormalizedSymbol: Map<String, List<WorkingOrder>>,
) {
    fun openPosition(deployment: StrategyDeployment): AccountPosition? =
        openPositionByDeploymentId[deployment.id]

    fun openOrders(deployment: StrategyDeployment): List<WorkingOrder> =
        openOrdersByDeploymentId[deployment.id].orEmpty()

    fun hasOpenPosition(deployment: StrategyDeployment): Boolean =
        openPosition(deployment) != null

    fun hasOpenOrders(deployment: StrategyDeployment): Boolean =
        openOrders(deployment).isNotEmpty()

    /** Orders for [symbol] by normalized symbol match (all currencies). */
    fun openOrdersForSymbol(symbol: String): List<WorkingOrder> =
        ordersByNormalizedSymbol[SymbolMarkets.normalizeSymbol(symbol)].orEmpty()

    companion object {
        val EMPTY = BrokerDeploymentIndex(emptyMap(), emptyMap(), emptyMap())

        fun build(
            deployments: List<StrategyDeployment>,
            positions: List<AccountPosition>,
            openOrders: List<WorkingOrder>,
        ): BrokerDeploymentIndex {
            if (deployments.isEmpty() && positions.isEmpty() && openOrders.isEmpty()) {
                return EMPTY
            }
            val positionsBySymbol = positions.groupBy { SymbolMarkets.normalizeSymbol(it.symbol) }
            val ordersBySymbol = openOrders.groupBy { SymbolMarkets.normalizeSymbol(it.symbol) }
            val openPositionByDeploymentId = HashMap<String, AccountPosition>(deployments.size)
            val openOrdersByDeploymentId = HashMap<String, List<WorkingOrder>>(deployments.size)
            for (deployment in deployments) {
                val norm = SymbolMarkets.normalizeSymbol(deployment.symbol)
                positionsBySymbol[norm]
                    ?.firstOrNull { pos ->
                        SymbolMarkets.matchesDeployment(deployment, pos) && pos.quantity != 0
                    }
                    ?.let { openPositionByDeploymentId[deployment.id] = it }
                ordersBySymbol[norm]
                    ?.filter { order -> SymbolMarkets.matchesDeployment(deployment, order) }
                    ?.takeIf { it.isNotEmpty() }
                    ?.let { openOrdersByDeploymentId[deployment.id] = it }
            }
            return BrokerDeploymentIndex(
                openPositionByDeploymentId = openPositionByDeploymentId,
                openOrdersByDeploymentId = openOrdersByDeploymentId,
                ordersByNormalizedSymbol = ordersBySymbol,
            )
        }
    }
}
