package daytrader.data

import daytrader.domain.OhlcBar
import daytrader.domain.TouchTurnBracketSetup
import daytrader.domain.TouchTurnEntryWindowStatus
import daytrader.domain.TouchTurnLogic
import daytrader.domain.TouchTurnOrderPlan
import daytrader.domain.TouchTurnOrderPlanner
import daytrader.domain.TouchTurnOrderRole
import daytrader.domain.TouchTurnPlannedOrder
import daytrader.presentation.Formatters

/**
 * Console log for Touch Turn bracket orders that would be placed after a liquidity candle.
 * Does not call IB [placeOrder]. Orders are only logged inside the 1-minute entry window.
 */
object TouchTurnOrderLog {
    fun logAfterLiquidityEvaluation(
        instanceId: String,
        symbol: String,
        sessionDate: String,
        maxDollars: Int,
        currencyCode: String,
        candle: OhlcBar?,
        marketZoneId: String,
        setup: TouchTurnBracketSetup?,
        nowEpochMillis: Long = System.currentTimeMillis()
    ) {
        if (setup == null || !setup.isLiquidityCandle || !setup.isActionable) return

        when (TouchTurnLogic.entryWindowStatus(candle, marketZoneId, nowEpochMillis)) {
            TouchTurnEntryWindowStatus.EXPIRED -> {
                line(
                    "ALERT instance=$instanceId symbol=$symbol session=$sessionDate — " +
                        TouchTurnLogic.entryWindowExpiredAlert(candle, marketZoneId)
                )
            }
            TouchTurnEntryWindowStatus.WITHIN_WINDOW -> {
                val plan = TouchTurnOrderPlanner.buildOrderPlan(symbol, setup, maxDollars, currencyCode)
                    ?: return
                logPlannedBracket(instanceId, sessionDate, maxDollars, setup, plan)
            }
            else -> Unit
        }
    }

    private fun logPlannedBracket(
        instanceId: String,
        sessionDate: String,
        maxDollars: Int,
        setup: TouchTurnBracketSetup,
        plan: TouchTurnOrderPlan
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
        line("  (preview only — placeOrder not called)")
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
