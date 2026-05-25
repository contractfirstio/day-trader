package daytrader.domain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

class SessionTradeDetailsBuilderTest {
    @Test
    fun build_longEntryAndExit() {
        val trades = listOf(
            sessionTrade(side = "BUY", parentOrderId = 0, orderId = 1, price = 100.0, qty = 5),
            sessionTrade(side = "SELL", parentOrderId = 1, orderId = 2, price = 105.0, qty = 5, realized = 25.0)
        )
        val details = SessionTradeDetailsBuilder.build(trades)
        assertNotNull(details)
        assertEquals("Long", details.sideLabel)
        assertEquals(5, details.quantity)
        assertEquals(100.0, details.entryPrice)
        assertEquals(105.0, details.exitPrice)
        assertEquals(25.0, details.realizedPnL)
    }

    @Test
    fun build_shortFromSellEntry() {
        val trades = listOf(
            sessionTrade(side = "SELL", parentOrderId = 0, orderId = 10, price = 200.0, qty = 3)
        )
        val details = SessionTradeDetailsBuilder.build(trades)
        assertNotNull(details)
        assertEquals("Short", details.sideLabel)
        assertEquals(true, details.isOpen)
    }

    @Test
    fun fillDisplays_labelsEntryAndExit() {
        val trades = listOf(
            sessionTrade(side = "BOT", parentOrderId = 0, orderId = 1, price = 50.0, qty = 1),
            sessionTrade(side = "SLD", parentOrderId = 1, orderId = 2, price = 52.0, qty = 1, realized = 2.0)
        )
        val fills = SessionTradeDetailsBuilder.fillDisplays(trades)
        assertEquals("Entry", fills[0].roleLabel)
        assertEquals("Long", fills[0].actionLabel)
        assertEquals("Exit", fills[1].roleLabel)
        assertEquals("Short", fills[1].actionLabel)
    }

    private fun sessionTrade(
        side: String,
        parentOrderId: Int,
        orderId: Int,
        price: Double,
        qty: Int,
        realized: Double? = null
    ) = SessionTrade(
        execId = "exec-$orderId",
        orderId = orderId,
        permId = orderId.toLong(),
        parentOrderId = parentOrderId,
        side = side,
        quantity = qty,
        price = price,
        currency = "USD",
        time = "2026-05-25T10:00:00",
        realizedPnL = realized
    )
}
