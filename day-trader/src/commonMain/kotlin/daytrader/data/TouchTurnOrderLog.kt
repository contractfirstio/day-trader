package daytrader.data

import daytrader.domain.OhlcBar
import daytrader.domain.TouchTurnBracketSetup
import daytrader.domain.TouchTurnLogic
import daytrader.domain.TouchTurnOrderPlan
import daytrader.domain.TouchTurnOrderPlanner
import daytrader.domain.TouchTurnOrderRole
import daytrader.domain.TouchTurnPlannedOrder
import daytrader.gateway.BrokerGateway
import daytrader.gateway.BrokerId
import daytrader.presentation.Formatters

/**
 * Console log for Touch Turn bracket orders placed after a liquidity candle closes.
 * Does not call IB [placeOrder] (emulator places working orders via [BrokerGateway.placeTouchTurnBracket]).
 */
object TouchTurnOrderLog {
    /** @return true when a liquidity bracket was logged/placed. */
    fun logAfterLiquidityEvaluation(
        instanceId: String,
        symbol: String,
        sessionDate: String,
        maxDollars: Int,
        currencyCode: String,
        setup: TouchTurnBracketSetup?,
        brokerGateway: BrokerGateway? = null
    ): Boolean {
        if (setup == null || !setup.isLiquidityCandle || !setup.isActionable) return false
        val plan = TouchTurnOrderPlanner.buildOrderPlan(symbol, setup, maxDollars, currencyCode)
            ?: return false
        logPlannedBracket(instanceId, sessionDate, maxDollars, setup, plan, brokerGateway)
        return true
    }

    private fun logPlannedBracket(
        instanceId: String,
        sessionDate: String,
        maxDollars: Int,
        setup: TouchTurnBracketSetup,
        plan: TouchTurnOrderPlan,
        brokerGateway: BrokerGateway?
    ) {
        val fib = TouchTurnLogic.takeProfitFibLabel(setup.candleColor)
        val side = TouchTurnLogic.tradeSideLabel(setup.side)
        line(
            "instance=$instanceId symbol=${plan.symbol} session=$sessionDate — LIQUIDITY CONFIRMED — " +
                "planned ${side.lowercase()} bracket ($fib TP) — NOT sent to IB"
        )
        line(
            "  sizing: qty=${plan.quantity} from \$$maxDollars max at risk @ entry ${fmt(setup.entry, plan.currencyCode)}"
        )
        plan.orders.forEachIndexed { index, order ->
            line(formatPlannedOrder(index + 1, order, plan.currencyCode))
        }
        if (brokerGateway?.brokerId == BrokerId.EMULATOR) {
            brokerGateway.placeTouchTurnBracket(plan)
            line("  (emulator — bracket working orders placed; entry fills on market ticks)")
        } else {
            line("  (preview only — placeOrder not called)")
        }
    }

    private fun formatPlannedOrder(index: Int, order: TouchTurnPlannedOrder, currency: String): String {
        val role = when (order.role) {
            TouchTurnOrderRole.ENTRY -> "ENTRY"
            TouchTurnOrderRole.TAKE_PROFIT -> "TAKE_PROFIT"
            TouchTurnOrderRole.STOP_LOSS -> "STOP_LOSS"
        }
        return "  #$index $role  ${order.action}  ${order.quantity}  ${order.orderType}  @  ${fmt(order.price, currency)}"
    }

    private fun fmt(price: Double, currency: String): String = Formatters.moneyPlain(price, currency)

    private fun line(message: String) {
        println("[TouchTurn] $message")
    }
}
