package daytrader.domain

import kotlin.test.Test
import kotlin.test.assertEquals

class SessionTradeDedupeTest {

    @Test
    fun dedupeByExecId_keepsFirstOccurrence() {
        val trades = listOf(
            trade("emu-1-0", 0),
            trade("emu-1-0", 0),
            trade("emu-2-1", 1000)
        )
        assertEquals(2, trades.dedupeByExecId().size)
    }

    @Test
    fun roundTripCount_isOneForEntryAndExit() {
        val trades = listOf(
            trade("e", 0),
            trade("x", 1000)
        )
        assertEquals(1, trades.roundTripCount())
    }

    private fun trade(execId: String, parent: Int) = SessionTrade(
        execId = execId,
        orderId = 1,
        permId = 1,
        parentOrderId = parent,
        side = if (parent == 0) "BUY" else "SELL",
        quantity = 10,
        price = 100.0,
        time = "2026-05-27T10:00:00"
    )
}
