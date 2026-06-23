package daytrader.presentation.strategies

import daytrader.domain.SessionTrade
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SessionTradeDetailUiMapperTest {
    @Test
    fun fromSessionTrades_closedRoundTripWithCommission_showsNetBreakdown() {
        val trades = listOf(
            trade(parentOrderId = 0, orderId = 1, price = 100.0, commission = 0.35, realizedPnL = 0.0),
            trade(parentOrderId = 1, orderId = 2, price = 105.0, commission = 0.35, realizedPnL = 500.0),
        )

        val ui = SessionTradeDetailUiMapper.fromSessionTrades(trades)

        assertNotNull(ui)
        assertTrue(ui.hasCommissionData)
        assertTrue(ui.showNetAsPrimary)
        assertEquals(500.0, ui.realizedPnL)
        assertEquals(499.30, ui.netPnL)
        assertEquals(0.70, ui.totalCommission)
        assertEquals("-$0.70", ui.formattedTotalCommission)
        assertEquals("+$499.30", ui.formattedNetPnL)
        assertTrue(ui.detailLine.contains("net"))
        assertEquals("-$0.35", ui.fills[0].formattedCommission)
        assertEquals("-$0.35", ui.fills[1].formattedCommission)
    }

    @Test
    fun fromSessionTrades_withoutCommission_omitsNetBreakdown() {
        val trades = listOf(
            trade(parentOrderId = 0, orderId = 1, price = 100.0, commission = null, realizedPnL = 0.0),
            trade(parentOrderId = 1, orderId = 2, price = 105.0, commission = null, realizedPnL = 500.0),
        )

        val ui = SessionTradeDetailUiMapper.fromSessionTrades(trades)

        assertNotNull(ui)
        assertFalse(ui.hasCommissionData)
        assertFalse(ui.showNetAsPrimary)
        assertNull(ui.netPnL)
        assertNull(ui.formattedTotalCommission)
        assertTrue(ui.detailLine.contains("realized"))
    }

    @Test
    fun tradeSummaryForRow_includesNetWhenCommissionPresent() {
        val trades = listOf(
            trade(parentOrderId = 0, orderId = 1, price = 100.0, commission = 0.35, realizedPnL = 0.0),
            trade(parentOrderId = 1, orderId = 2, price = 105.0, commission = 0.35, realizedPnL = 500.0),
        )

        val (_, summary) = SessionTradeDetailUiMapper.tradeSummaryForRow(trades)

        assertNotNull(summary)
        assertTrue(summary!!.contains("net"))
    }

    private fun trade(
        parentOrderId: Int,
        orderId: Int,
        price: Double,
        commission: Double?,
        realizedPnL: Double?,
    ) = SessionTrade(
        execId = "exec-$orderId",
        orderId = orderId,
        permId = orderId.toLong(),
        parentOrderId = parentOrderId,
        side = if (parentOrderId == 0) "BUY" else "SELL",
        quantity = 100,
        price = price,
        currency = "USD",
        time = "2026-05-25T10:00:00",
        commission = commission,
        realizedPnL = realizedPnL,
    )
}
