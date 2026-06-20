package daytrader.data

import daytrader.broker.SymbolMarkets
import daytrader.domain.DeploymentStatus
import daytrader.domain.StrategyDeployment
import daytrader.domain.isTouchTurn
import daytrader.gateway.AccountPosition
import daytrader.gateway.WorkingOrder

/**
 * Detects drift between persisted Touch Turn session state and live broker snapshots.
 * Used after reconnect and when broker positions/orders change during RUNNING sessions.
 */
object RunningBrokerReconciliation {
    enum class Kind {
        /** Working orders at the broker were not placed by this session's bracket flow. */
        ORPHAN_BROKER_ORDERS,
        /** Broker reports a position but this session never submitted a bracket. */
        UNEXPECTED_OPEN_POSITION,
    }

    data class Finding(
        val deploymentId: String,
        val symbol: String,
        val kind: Kind,
        val detail: String,
    )

    fun evaluate(
        deployments: List<StrategyDeployment>,
        positions: List<AccountPosition>,
        openOrders: List<WorkingOrder>,
    ): List<Finding> {
        val findings = mutableListOf<Finding>()
        for (instance in deployments) {
            if (instance.status != DeploymentStatus.RUNNING) continue
            if (!instance.isTouchTurn) continue
            val session = instance.touchTurnSession ?: continue
            val hasOpenPosition = SymbolMarkets.hasOpenPosition(instance, positions)
            val hasOpenOrders = SymbolMarkets.hasOpenOrders(instance, openOrders)

            if (hasOpenOrders && !session.ordersPlacedForSession) {
                findings += Finding(
                    deploymentId = instance.id,
                    symbol = instance.symbol,
                    kind = Kind.ORPHAN_BROKER_ORDERS,
                    detail = "Open broker orders on symbol are not tied to this session's bracket.",
                )
            }

            if (hasOpenPosition && !session.ordersPlacedForSession &&
                session.milestones?.positionOpenedAt == null
            ) {
                findings += Finding(
                    deploymentId = instance.id,
                    symbol = instance.symbol,
                    kind = Kind.UNEXPECTED_OPEN_POSITION,
                    detail = "Broker reports an open position but this session did not place bracket orders.",
                )
            }
        }
        return findings
    }
}
