package daytrader.broker

import daytrader.gateway.BrokerFill
import kotlin.test.Test
import kotlin.test.assertEquals

class BrokerFillMatcherTest {
    @Test
    fun fillsForBracket_includesEntryAndChildLegs() {
        val fills = listOf(
            fill(orderId = 10, parentOrderId = 0),
            fill(orderId = 11, parentOrderId = 10),
            fill(orderId = 12, parentOrderId = 10),
            fill(orderId = 99, parentOrderId = 0, execId = "other")
        )
        val bracket = BrokerFillMatcher.fillsForBracket(entryOrderId = 10, fills = fills)
        assertEquals(3, bracket.size)
        assertEquals(setOf(10, 11, 12), bracket.map { it.orderId }.toSet())
    }

    @Test
    fun fillsForOrder_matchesSingleLeg() {
        val fills = listOf(
            fill(orderId = 11, parentOrderId = 10),
            fill(orderId = 12, parentOrderId = 10)
        )
        assertEquals(1, BrokerFillMatcher.fillsForOrder(11, fills).size)
    }

    private fun fill(orderId: Int, parentOrderId: Int, execId: String = "e-$orderId") = BrokerFill(
        execId = execId,
        orderId = orderId,
        permId = orderId.toLong(),
        parentOrderId = parentOrderId,
        symbol = "AAPL",
        side = "BOT",
        quantity = 1,
        price = 100.0,
        time = "2026-05-25T10:00:00"
    )
}
