package daytrader.broker

import daytrader.gateway.BrokerFill
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class FillCommissionReportApplierTest {
    @Test
    fun applyReport_entryFill_setsCommissionAndZeroRealizedPnL() {
        val execution = sampleFill(realizedPnL = null)

        val reported = FillCommissionReportApplier.applyReport(
            fill = execution,
            orderType = "LMT",
            priceBasedRealizedPnL = null,
        )

        assertEquals(0.35, reported.commission)
        assertEquals(0.0, reported.realizedPnL)
    }

    @Test
    fun applyReport_exitFill_setsCommissionAndPriceBasedRealizedPnL() {
        val execution = sampleFill(realizedPnL = null)

        val reported = FillCommissionReportApplier.applyReport(
            fill = execution,
            orderType = "STP",
            priceBasedRealizedPnL = 500.0,
        )

        assertEquals(0.35, reported.commission)
        assertEquals(500.0, reported.realizedPnL)
    }

    @Test
    fun applyReport_executionPhaseLeavesCommissionUnsetUntilReport() {
        val execution = sampleFill(realizedPnL = null)

        assertNull(execution.commission)
        assertNull(execution.realizedPnL)
    }

    private fun sampleFill(realizedPnL: Double?) = BrokerFill(
        execId = "exec-1",
        orderId = 1,
        permId = 1L,
        parentOrderId = 0,
        symbol = "NVDA",
        side = "BUY",
        quantity = 10,
        price = 100.0,
        time = "2026-05-25T10:00:00",
        currency = "USD",
        commission = null,
        realizedPnL = realizedPnL,
    )
}
