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

    @Test
    fun sessionEntryNotional_isEntryQtyTimesPrice() {
        val trades = listOf(
            sessionTrade(side = "BUY", parentOrderId = 0, orderId = 1, price = 100.0, qty = 50),
            sessionTrade(side = "SELL", parentOrderId = 1, orderId = 2, price = 105.0, qty = 50, realized = 250.0),
        )
        assertEquals(5_000.0, trades.sessionEntryNotional())
    }

    @Test
    fun sessionEntryNotional_sumsPartialEntryFills_excludesExitAndDeadlineClose() {
        val trades = listOf(
            sessionTrade(side = "BUY", parentOrderId = 0, orderId = 1, price = 10.0, qty = 40, execId = "e1"),
            sessionTrade(side = "BUY", parentOrderId = 0, orderId = 1, price = 12.0, qty = 60, execId = "e2"),
            sessionTrade(side = "SELL", parentOrderId = 1, orderId = 2, price = 11.0, qty = 100, realized = 0.0),
            // OPEN_DEADLINE-style close also uses parentOrderId == 0 with a different orderId
            sessionTrade(side = "SELL", parentOrderId = 0, orderId = 3, price = 9.0, qty = 100, realized = -100.0),
        )
        // 40*10 + 60*12 = 1_120; exit and deadline must not count
        assertEquals(1_120.0, trades.sessionEntryNotional())
    }

    @Test
    fun sessionEntryNotional_empty_isZero() {
        assertEquals(0.0, emptyList<SessionTrade>().sessionEntryNotional())
    }

    private fun sessionTrade(
        side: String,
        parentOrderId: Int,
        orderId: Int,
        price: Double,
        qty: Int,
        realized: Double? = null,
        execId: String = "exec-$orderId",
    ) = SessionTrade(
        execId = execId,
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
