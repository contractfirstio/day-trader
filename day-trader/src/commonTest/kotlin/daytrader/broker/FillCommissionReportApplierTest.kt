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
    fun applyReport_exitFill_setsIbNetRealizedPnLAfterAllLegCommissions() {
        val calculator = TransactionCostCalculator()
        val entry = FillCommissionReportApplier.applyReport(
            fill = sampleFill(execId = "entry", parentOrderId = 0, realizedPnL = null),
            orderType = "LMT",
            priceBasedRealizedPnL = null,
        )
        val execution = sampleFill(execId = "exit", parentOrderId = 1, realizedPnL = null)
        val exitCommission = calculator.calculateCommission(execution.quantity, "STP").toDouble()

        val reported = FillCommissionReportApplier.applyReport(
            fill = execution,
            orderType = "STP",
            priceBasedRealizedPnL = 500.0,
            priorFillsForRoundTrip = listOf(entry),
        )

        assertEquals(exitCommission, reported.commission)
        assertEquals(500.0 - (entry.commission ?: 0.0) - exitCommission, reported.realizedPnL)
    }

    @Test
    fun applyReport_executionPhaseLeavesCommissionUnsetUntilReport() {
        val execution = sampleFill(realizedPnL = null)

        assertNull(execution.commission)
        assertNull(execution.realizedPnL)
    }

    private fun sampleFill(
        realizedPnL: Double?,
        execId: String = "exec-1",
        parentOrderId: Int = 0,
    ) = BrokerFill(
        execId = execId,
        orderId = 1,
        permId = 1L,
        parentOrderId = parentOrderId,
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
