package daytrader.broker

import daytrader.gateway.BrokerFill
import daytrader.gateway.BrokerGateway

/** Filters [BrokerGateway.fills] by order or bracket (entry) id. */
object BrokerFillMatcher {
    /** Fills for a single order id (entry, take-profit, or stop leg). */
    fun fillsForOrder(orderId: Int, fills: List<BrokerFill>): List<BrokerFill> =
        fills.filter { it.orderId == orderId }

    /**
     * Fills for a Touch Turn bracket rooted at [entryOrderId] (the parent entry order).
     * Includes the entry fill ([parentOrderId] == 0 and [BrokerFill.orderId] == [entryOrderId])
     * and child-leg fills ([parentOrderId] == [entryOrderId]).
     */
    fun fillsForBracket(entryOrderId: Int, fills: List<BrokerFill>): List<BrokerFill> =
        fills.filter { fill ->
            fill.orderId == entryOrderId || fill.parentOrderId == entryOrderId
        }

    fun fillsForOrder(gateway: BrokerGateway, orderId: Int): List<BrokerFill> =
        fillsForOrder(orderId, gateway.fills.value)

    fun fillsForBracket(gateway: BrokerGateway, entryOrderId: Int): List<BrokerFill> =
        fillsForBracket(entryOrderId, gateway.fills.value)
}
