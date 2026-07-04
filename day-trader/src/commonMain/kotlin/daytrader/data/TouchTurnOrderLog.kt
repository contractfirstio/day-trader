package daytrader.data

import daytrader.domain.InstrumentIdentity
import daytrader.domain.OhlcBar
import daytrader.domain.TouchTurnBracketSetup
import daytrader.domain.TouchTurnLogic
import daytrader.domain.TouchTurnOrderPlan
import daytrader.domain.TouchTurnOrderPlanner
import daytrader.domain.TouchTurnOrderRole
import daytrader.domain.TouchTurnPlannedOrder
import daytrader.domain.TouchTurnRuleConfig
import daytrader.gateway.BrokerGateway
import daytrader.gateway.BrokerId
import daytrader.diagnostics.TimestampedConsoleLog
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
        instrument: InstrumentIdentity? = null,
        setup: TouchTurnBracketSetup?,
        openingBarClose: Double? = null,
        brokerGateway: BrokerGateway? = null,
        rules: TouchTurnRuleConfig = TouchTurnRuleConfig.DEFAULT
    ): Boolean {
        if (setup == null || !TouchTurnLogic.setupActionableForEntry(setup, rules)) return false
        val plan = TouchTurnOrderPlanner.buildOrderPlan(
            symbol = symbol,
            setup = setup,
            maxDollars = maxDollars,
            currencyCode = currencyCode,
            instrument = instrument,
            openingBarClose = openingBarClose,
            rules = rules
        )
            ?: return false
        logPlannedBracket(instanceId, sessionDate, maxDollars, setup, plan, brokerGateway)
        return true
    }

    /** @return true when a hammer-confirmed market bracket was logged/placed. */
    fun logHammerConfirmationBracket(
        instanceId: String,
        symbol: String,
        sessionDate: String,
        maxDollars: Int,
        currencyCode: String,
        instrument: InstrumentIdentity? = null,
        setup: TouchTurnBracketSetup,
        hammerBar: OhlcBar,
        plan: TouchTurnOrderPlan,
        brokerGateway: BrokerGateway?
    ): Boolean {
        val side = TouchTurnLogic.tradeSideLabel(setup.side)
        val submissionLabel = when (brokerGateway?.brokerId) {
            BrokerId.INTERACTIVE_BROKERS -> "submitted to Interactive Brokers"
            BrokerId.EMULATOR -> "placed with Broker Emulator (paper)"
            else -> "planned only (no broker connected)"
        }
        line(
            "instance=$instanceId symbol=$symbol session=$sessionDate — 5M HAMMER CONFIRMED — " +
                "planned ${side.lowercase()} market bracket (2:1 TP) — $submissionLabel"
        )
        line(
            "  hammer close=${fmt(hammerBar.close, currencyCode)} low=${fmt(hammerBar.low, currencyCode)} " +
                "high=${fmt(hammerBar.high, currencyCode)}"
        )
        line(
            "  sizing: qty=${plan.quantity} from \$$maxDollars max at risk @ entry ${fmt(setup.entry, currencyCode)}"
        )
        plan.orders.forEachIndexed { index, order ->
            line(formatPlannedOrder(index + 1, order, currencyCode))
        }
        when (brokerGateway?.brokerId) {
            BrokerId.EMULATOR -> {
                brokerGateway.placeTouchTurnBracket(plan)
                line("  (paper emulator — market entry fills immediately; bracket legs follow)")
            }
            BrokerId.INTERACTIVE_BROKERS -> {
                brokerGateway.placeTouchTurnBracket(plan)
                line("  (IB — entry MKT + take-profit LMT + stop STP bracket queued)")
            }
            else -> line("  (preview only — connect a broker to submit)")
        }
        return brokerGateway != null
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
                line("  (paper emulator — bracket fills when live price crosses limit/stop)")
            }
            BrokerId.INTERACTIVE_BROKERS -> {
                brokerGateway.placeTouchTurnBracket(plan)
                val entryType = plan.orders.firstOrNull { it.role == TouchTurnOrderRole.ENTRY }?.orderType ?: "LMT"
                line("  (IB — entry $entryType + take-profit LMT + adjustable stop STP→TRAIL bracket queued)")
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
        val trailSuffix = if (order.role == TouchTurnOrderRole.STOP_LOSS &&
            order.trailTriggerPrice != null
        ) {
            "  trail@${fmt(order.trailTriggerPrice, currency)}"
        } else {
            ""
        }
        return "  #$index $role  ${order.action}  ${order.quantity}  ${order.orderType}  " +
            "${order.timeInForce}  @  ${fmt(order.price, currency)}$trailSuffix"
    }

    private fun fmt(price: Double, currency: String): String = Formatters.moneyPlain(price, currency)

    private fun line(message: String) {
        TimestampedConsoleLog.line("TouchTurn", message)
    }
}
