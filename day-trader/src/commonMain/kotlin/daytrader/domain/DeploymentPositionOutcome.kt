package daytrader.domain

import daytrader.broker.SymbolMarkets
import daytrader.gateway.AccountPosition
import daytrader.gateway.WorkingOrder

/** P&L bounds if the current bracket legs fill at their working prices. */
data class DeploymentPositionOutcome(
    val maxProfit: Double,
    /** P&L if the current stop (including a ratcheted trailing stop) fills. */
    val stopOutcome: Double,
) {
    val stopIsMinWin: Boolean get() = stopOutcome >= 0.0
}

object DeploymentPositionOutcomeCalculator {
    fun resolve(
        deployment: StrategyDeployment,
        brokerPosition: AccountPosition?,
        brokerOpenOrders: List<WorkingOrder> = emptyList(),
    ): DeploymentPositionOutcome? {
        val positionContext = positionContext(deployment, brokerPosition) ?: return null
        val orders = SymbolMarkets.openOrdersForDeployment(deployment, brokerOpenOrders)
            .filter { !it.isTrailAdjustment }
        val planned = deployment.touchTurnSession?.plannedBracket
        val stopPrice = liveStopPrice(orders) ?: planned?.stopLoss ?: deployment.live.stopPrice
        val takeProfitPrice = takeProfitPrice(orders) ?: planned?.takeProfit ?: deployment.live.targetPrice
        val maxProfit = takeProfitPrice?.let {
            pnlAtExit(positionContext, it)
        } ?: return null
        val stopOutcome = stopPrice?.let {
            pnlAtExit(positionContext, it)
        } ?: return null
        return DeploymentPositionOutcome(
            maxProfit = maxProfit,
            stopOutcome = stopOutcome,
        )
    }

    private data class PositionContext(
        val entryPrice: Double,
        val quantity: Int,
        val isLong: Boolean,
        val currency: String,
        val primaryExch: String?,
        val exchange: String?,
    )

    private fun positionContext(
        deployment: StrategyDeployment,
        brokerPosition: AccountPosition?,
    ): PositionContext? {
        brokerPosition?.takeIf { it.quantity != 0 }?.let { pos ->
            return PositionContext(
                entryPrice = pos.avgPrice,
                quantity = kotlin.math.abs(pos.quantity),
                isLong = pos.quantity > 0,
                currency = pos.currency,
                primaryExch = deployment.instrument?.primaryExch,
                exchange = deployment.instrument?.exchange,
            )
        }
        val live = deployment.live
        if (live.state != ExecutionState.FILLED) return null
        val entry = live.entryPrice ?: return null
        if (live.quantity <= 0) return null
        return PositionContext(
            entryPrice = entry,
            quantity = live.quantity,
            isLong = live.side == TradeSide.LONG,
            currency = deployment.instrument?.currency ?: SymbolMarkets.currencyCode(deployment.symbol),
            primaryExch = deployment.instrument?.primaryExch,
            exchange = deployment.instrument?.exchange,
        )
    }

    private fun liveStopPrice(orders: List<WorkingOrder>): Double? =
        orders.firstOrNull { order ->
            order.orderType.equals("STP", ignoreCase = true) ||
                order.orderType.equals("TRAIL", ignoreCase = true)
        }?.stopPrice?.takeIf { it > 0.0 }

    private fun takeProfitPrice(orders: List<WorkingOrder>): Double? =
        orders.firstOrNull { order ->
            order.orderType.equals("LMT", ignoreCase = true) &&
                order.limitPrice != null &&
                order.limitPrice > 0.0 &&
                order.parentOrderId != 0
        }?.limitPrice

    private fun pnlAtExit(context: PositionContext, exitPriceRaw: Double): Double =
        InstrumentPriceScale.realizedPnLOnClose(
            closeQty = context.quantity,
            avgPriceRaw = context.entryPrice,
            exitPriceRaw = exitPriceRaw,
            currency = context.currency,
            isLong = context.isLong,
            primaryExch = context.primaryExch,
            exchange = context.exchange,
        )
}
