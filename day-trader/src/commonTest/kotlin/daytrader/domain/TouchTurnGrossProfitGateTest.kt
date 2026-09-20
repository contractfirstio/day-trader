package daytrader.domain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TouchTurnGrossProfitGateTest {
    /** Geometry ~2.0 reward:risk before commission (TP dist 10, stop dist 5). */
    private val setup = TouchTurnBracketSetup(
        range = 11.0,
        rangeThreshold = 0.0,
        isLiquidityCandle = true,
        candleColor = FirstCandleColor.RED,
        side = TouchTurnTradeSide.LONG,
        entry = 100.0,
        stopLoss = 95.0,
        takeProfit = 110.0,
    )

    @Test
    fun projectedMaxProfit_netsUsCommission() {
        assertEquals(
            99.30,
            TouchTurnGrossProfitGate.projectedMaxProfit(
                takeProfitPrice = 110.0,
                entryPrice = 100.0,
                quantity = 10,
                side = TouchTurnTradeSide.LONG,
                currency = "USD",
            ),
            0.001,
        )
        assertEquals(
            0.0,
            TouchTurnGrossProfitGate.projectedMaxProfit(
                takeProfitPrice = 90.0,
                entryPrice = 100.0,
                quantity = 10,
                side = TouchTurnTradeSide.LONG,
                currency = "USD",
            ),
            0.001,
        )
    }

    @Test
    fun projectedMaxLoss_netsUsStopCommission() {
        // Gross stop loss 50 + RT LMT+STP 0.70
        assertEquals(
            50.70,
            TouchTurnGrossProfitGate.projectedMaxLoss(
                stopLossPrice = 95.0,
                entryPrice = 100.0,
                quantity = 10,
                side = TouchTurnTradeSide.LONG,
                currency = "USD",
            ),
            0.001,
        )
    }

    @Test
    fun projectedRatio_isMaxProfitOverMaxLoss() {
        val ratio = TouchTurnGrossProfitGate.projectedRatio(
            setup = setup,
            entryPrice = 100.0,
            quantity = 10,
            currency = "USD",
        )
        assertEquals(99.30 / 50.70, ratio!!, 0.001)
    }

    @Test
    fun passes_whenRatioZeroDisablesGate() {
        assertTrue(
            TouchTurnGrossProfitGate.passes(
                setup = setup,
                entryPrice = 100.0,
                quantity = 10,
                minProfitToLossRatio = 0.0,
                currency = "USD",
            )
        )
    }

    @Test
    fun passes_whenNetRatioMeetsThreshold() {
        assertTrue(
            TouchTurnGrossProfitGate.passes(
                setup = setup,
                entryPrice = 100.0,
                quantity = 10,
                minProfitToLossRatio = 1.8,
                currency = "USD",
            )
        )
        assertFalse(
            TouchTurnGrossProfitGate.passes(
                setup = setup,
                entryPrice = 100.0,
                quantity = 10,
                minProfitToLossRatio = 2.0,
                currency = "USD",
            )
        )
    }

    @Test
    fun passes_failsWhenPriceRatioWouldPassButCommissionDropsBelow() {
        // Price-only R:R = 100/50 = 2.0; net ≈ 1.958 — fails a 1.96 floor
        assertFalse(
            TouchTurnGrossProfitGate.passes(
                setup = setup,
                entryPrice = 100.0,
                quantity = 10,
                minProfitToLossRatio = 1.96,
                currency = "USD",
            )
        )
        assertTrue(
            TouchTurnGrossProfitGate.passes(
                setup = setup,
                entryPrice = 100.0,
                quantity = 10,
                minProfitToLossRatio = 1.95,
                currency = "USD",
            )
        )
    }

    @Test
    fun passes_rejectsWhenMaxLossIsZeroAndGateEnabled() {
        val minWinSetup = setup.copy(stopLoss = 102.0)
        assertFalse(
            TouchTurnGrossProfitGate.passes(
                setup = minWinSetup,
                entryPrice = 100.0,
                quantity = 10,
                minProfitToLossRatio = 1.8,
                currency = "USD",
            )
        )
        assertNull(
            TouchTurnGrossProfitGate.projectedRatio(
                setup = minWinSetup,
                entryPrice = 100.0,
                quantity = 10,
                currency = "USD",
            )
        )
    }

    @Test
    fun projectedMaxProfit_hkSubtractsSehkRoundTripFees() {
        val projected = TouchTurnGrossProfitGate.projectedMaxProfit(
            takeProfitPrice = 8.80,
            entryPrice = 8.56,
            quantity = 9_000,
            side = TouchTurnTradeSide.LONG,
            currency = "HKD",
            primaryExch = "SEHK",
            exchange = "SEHK",
        )
        val gross = (8.80 - 8.56) * 9_000
        assertTrue(projected < gross)
        assertTrue(projected > 0.0)
    }
}
