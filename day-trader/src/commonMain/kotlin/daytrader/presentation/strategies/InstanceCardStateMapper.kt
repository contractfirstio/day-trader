package daytrader.presentation.strategies

import daytrader.gateway.WorkingOrder
import daytrader.broker.SymbolMarkets
import daytrader.data.StrategyCatalog
import daytrader.domain.ExecutionState
import daytrader.domain.InstanceStatus
import daytrader.domain.RunStatus
import daytrader.domain.StrategyInstance
import daytrader.domain.riskReward

object InstanceCardStateMapper {
    private const val NEUTRAL_PNL_EPSILON = 0.005

    fun resolve(
        instance: StrategyInstance,
        sessionDate: String,
        brokerUnrealizedPnL: Double? = null,
        brokerOpenOrders: List<WorkingOrder> = emptyList()
    ): InstanceCardPresentation {
        if (instance.status == InstanceStatus.ERROR) {
            return InstanceCardPresentation(
                accent = InstanceCardAccent.ERROR,
                chipLabel = "Error"
            )
        }
        openOrdersPresentation(instance, brokerOpenOrders)?.let { return it }
        return when (instance.status) {
            InstanceStatus.STOPPED -> stoppedPresentation(instance, sessionDate)
            InstanceStatus.RUNNING -> runningPresentation(instance, brokerUnrealizedPnL)
            InstanceStatus.ERROR -> InstanceCardPresentation(
                accent = InstanceCardAccent.ERROR,
                chipLabel = "Error"
            )
        }
    }

    private fun openOrdersPresentation(
        instance: StrategyInstance,
        brokerOpenOrders: List<WorkingOrder>
    ): InstanceCardPresentation? {
        val orders = SymbolMarkets.openOrdersForSymbol(instance.symbol, brokerOpenOrders)
        if (orders.isEmpty()) return null
        val chipLabel = when (orders.size) {
            1 -> "Open order"
            else -> "${orders.size} open orders"
        }
        return InstanceCardPresentation(
            accent = InstanceCardAccent.OPEN_ORDERS,
            chipLabel = chipLabel
        )
    }

    private fun stoppedPresentation(
        instance: StrategyInstance,
        sessionDate: String
    ): InstanceCardPresentation {
        val run = lastClosedRunForSession(instance, sessionDate)
        if (run == null) {
            return InstanceCardPresentation(
                accent = InstanceCardAccent.STOPPED_IDLE,
                chipLabel = "Stopped"
            )
        }
        return when {
            run.pnl > NEUTRAL_PNL_EPSILON -> InstanceCardPresentation(
                accent = InstanceCardAccent.STOPPED_WIN,
                chipLabel = "Win"
            )
            run.pnl < -NEUTRAL_PNL_EPSILON -> InstanceCardPresentation(
                accent = InstanceCardAccent.STOPPED_LOSS,
                chipLabel = "Loss"
            )
            else -> InstanceCardPresentation(
                accent = InstanceCardAccent.STOPPED_NEUTRAL,
                chipLabel = "Neutral"
            )
        }
    }

    private fun runningPresentation(
        instance: StrategyInstance,
        brokerUnrealizedPnL: Double?
    ): InstanceCardPresentation {
        if (instance.live.state != ExecutionState.FILLED) {
            return InstanceCardPresentation(
                accent = InstanceCardAccent.RUNNING_FLAT,
                chipLabel = "Active"
            )
        }
        val unrealized = openPositionUnrealizedPnL(instance, brokerUnrealizedPnL)
        if (unrealized == null) {
            return InstanceCardPresentation(
                accent = InstanceCardAccent.RUNNING_FLAT,
                chipLabel = "Active"
            )
        }
        return if (unrealized >= 0) {
            InstanceCardPresentation(
                accent = InstanceCardAccent.RUNNING_IN_THE_MONEY,
                chipLabel = "In the money"
            )
        } else {
            InstanceCardPresentation(
                accent = InstanceCardAccent.RUNNING_OUT_OF_THE_MONEY,
                chipLabel = "Out of the money"
            )
        }
    }

    private fun openPositionUnrealizedPnL(
        instance: StrategyInstance,
        brokerUnrealizedPnL: Double?
    ): Double? {
        if (brokerUnrealizedPnL != null) return brokerUnrealizedPnL
        return instance.live.riskReward(
            maxDollars = instance.maxDollars,
            rewardMultiple = StrategyCatalog.rewardMultiple(instance.strategyType)
        ).unrealizedPnL
    }

    private fun lastClosedRunForSession(
        instance: StrategyInstance,
        sessionDate: String
    ) = instance.performance
        .filter { it.status == RunStatus.CLOSED && it.date == sessionDate }
        .maxByOrNull { it.stoppedAt.ifBlank { it.startedAt } }
}
