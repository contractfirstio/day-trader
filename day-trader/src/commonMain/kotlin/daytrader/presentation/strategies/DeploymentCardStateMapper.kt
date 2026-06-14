package daytrader.presentation.strategies

import daytrader.gateway.WorkingOrder
import daytrader.broker.SymbolMarkets
import daytrader.data.StrategyCatalog
import daytrader.domain.ExecutionState
import daytrader.domain.DeploymentStatus
import daytrader.domain.SessionStatus
import daytrader.domain.StrategyDeployment
import daytrader.domain.riskReward

object DeploymentCardStateMapper {
    private const val NEUTRAL_PNL_EPSILON = 0.005

    fun resolve(
        instance: StrategyDeployment,
        sessionDate: String,
        brokerUnrealizedPnL: Double? = null,
        brokerOpenOrders: List<WorkingOrder> = emptyList(),
        hasOpenPosition: Boolean = false
    ): DeploymentCardPresentation {
        if (instance.status == DeploymentStatus.ERROR) {
            return DeploymentCardPresentation(
                accent = DeploymentCardAccent.ERROR,
                chipLabel = "Error"
            )
        }
        if (hasActivePosition(instance, hasOpenPosition)) {
            return openPositionPresentation(instance, brokerUnrealizedPnL)
        }
        openOrdersPresentation(instance, brokerOpenOrders)?.let { return it }
        return when (instance.status) {
            DeploymentStatus.STOPPED -> stoppedPresentation(instance, sessionDate)
            DeploymentStatus.RUNNING -> DeploymentCardPresentation(
                accent = DeploymentCardAccent.RUNNING_FLAT,
                chipLabel = "Active"
            )
            DeploymentStatus.ERROR -> DeploymentCardPresentation(
                accent = DeploymentCardAccent.ERROR,
                chipLabel = "Error"
            )
        }
    }

    private fun hasActivePosition(instance: StrategyDeployment, brokerHasPosition: Boolean): Boolean =
        brokerHasPosition ||
            (instance.status == DeploymentStatus.RUNNING && instance.live.state == ExecutionState.FILLED)

    private fun openPositionPresentation(
        instance: StrategyDeployment,
        brokerUnrealizedPnL: Double?
    ): DeploymentCardPresentation {
        val unrealized = openPositionUnrealizedPnL(instance, brokerUnrealizedPnL) ?: 0.0
        return if (unrealized >= 0) {
            DeploymentCardPresentation(
                accent = DeploymentCardAccent.RUNNING_IN_THE_MONEY,
                chipLabel = "In the money"
            )
        } else {
            DeploymentCardPresentation(
                accent = DeploymentCardAccent.RUNNING_OUT_OF_THE_MONEY,
                chipLabel = "Out of the money"
            )
        }
    }

    private fun openOrdersPresentation(
        instance: StrategyDeployment,
        brokerOpenOrders: List<WorkingOrder>
    ): DeploymentCardPresentation? {
        val orders = SymbolMarkets.openOrdersForDeployment(instance, brokerOpenOrders)
        if (orders.isEmpty()) return null
        val chipLabel = when (orders.size) {
            1 -> "Open order"
            else -> "${orders.size} open orders"
        }
        return DeploymentCardPresentation(
            accent = DeploymentCardAccent.OPEN_ORDERS,
            chipLabel = chipLabel
        )
    }

    private fun stoppedPresentation(
        instance: StrategyDeployment,
        sessionDate: String
    ): DeploymentCardPresentation {
        val run = lastClosedRunForSession(instance, sessionDate)
        if (run == null) {
            return DeploymentCardPresentation(
                accent = DeploymentCardAccent.STOPPED_IDLE,
                chipLabel = "Stopped"
            )
        }
        return when {
            run.pnl > NEUTRAL_PNL_EPSILON -> DeploymentCardPresentation(
                accent = DeploymentCardAccent.STOPPED_WIN,
                chipLabel = "Win"
            )
            run.pnl < -NEUTRAL_PNL_EPSILON -> DeploymentCardPresentation(
                accent = DeploymentCardAccent.STOPPED_LOSS,
                chipLabel = "Loss"
            )
            else -> DeploymentCardPresentation(
                accent = DeploymentCardAccent.STOPPED_NEUTRAL,
                chipLabel = "Neutral"
            )
        }
    }

    private fun openPositionUnrealizedPnL(
        instance: StrategyDeployment,
        brokerUnrealizedPnL: Double?
    ): Double? {
        if (brokerUnrealizedPnL != null) return brokerUnrealizedPnL
        return instance.live.riskReward(
            maxDollars = instance.maxDollars,
            rewardMultiple = StrategyCatalog.rewardMultiple(instance.strategyType)
        ).unrealizedPnL
    }

    private fun lastClosedRunForSession(
        instance: StrategyDeployment,
        sessionDate: String
    ) = instance.sessionHistory
        .filter { it.status == SessionStatus.CLOSED && it.date == sessionDate }
        .maxByOrNull { it.stoppedAt.ifBlank { it.startedAt } }
}
