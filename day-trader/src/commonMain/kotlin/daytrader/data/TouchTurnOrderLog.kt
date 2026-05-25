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
 * Submits brackets via [BrokerGateway.placeTouchTurnBracket] (IB or emulator).
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
        val submissionLabel = when (brokerGateway?.brokerId) {
            BrokerId.INTERACTIVE_BROKERS -> "submitted to Interactive Brokers"
            BrokerId.EMULATOR -> "placed with Broker Emulator (paper)"
            else -> "planned only (no broker connected)"
        }
        line(
            "instance=$instanceId symbol=${plan.symbol} session=$sessionDate — LIQUIDITY CONFIRMED — " +
                "planned ${side.lowercase()} bracket ($fib TP) — $submissionLabel"
        )
        line(
            "  sizing: qty=${plan.quantity} from \$$maxDollars max at risk @ entry ${fmt(setup.entry, plan.currencyCode)}"
        )
        plan.orders.forEachIndexed { index, order ->
            line(formatPlannedOrder(index + 1, order, plan.currencyCode))
        }
        when (brokerGateway?.brokerId) {
            BrokerId.EMULATOR -> {
                brokerGateway.placeTouchTurnBracket(plan)
                line("  (paper emulator — bracket fills when IB live price crosses limit/stop)")
            }
            BrokerId.INTERACTIVE_BROKERS -> {
                brokerGateway.placeTouchTurnBracket(plan)
                line("  (IB — entry LMT + take-profit LMT + stop STP bracket queued; fills coalesced on fill)")
            }
            else -> line("  (preview only — connect a broker to submit)")
        }
    }

    private fun formatPlannedOrder(index: Int, order: TouchTurnPlannedOrder, currency: String): String {
        val role = when (order.role) {
            TouchTurnOrderRole.ENTRY -> "ENTRY"
            TouchTurnOrderRole.TAKE_PROFIT -> "TAKE_PROFIT"
            TouchTurnOrderRole.STOP_LOSS -> "STOP_LOSS"
        }
        return "  #$index $role  ${order.action}  ${order.quantity}  ${order.orderType}  " +
            "${order.timeInForce}  @  ${fmt(order.price, currency)}"
    }

    private fun fmt(price: Double, currency: String): String = Formatters.moneyPlain(price, currency)

    private fun line(message: String) {
        println("[TouchTurn] $message")
    }
}
