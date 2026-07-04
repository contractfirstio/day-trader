package daytrader.domain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TouchTurnGrossProfitGateTest {
    private val setup = TouchTurnBracketSetup(
        range = 11.0,
        rangeThreshold = 0.0,
        isLiquidityCandle = true,
        candleColor = FirstCandleColor.RED,
        side = TouchTurnTradeSide.LONG,
        entry = 99.02,
        stopLoss = 97.0,
        takeProfit = 103.0
    )

    @Test
    fun projectedGrossProfit_usesSignedDistanceToTakeProfit() {
        assertEquals(
            20.0,
            TouchTurnGrossProfitGate.projectedGrossProfit(
                takeProfitPrice = 103.0,
                entryPrice = 101.0,
                quantity = 10,
                side = TouchTurnTradeSide.LONG
            )
        )
        assertEquals(
            0.0,
            TouchTurnGrossProfitGate.projectedGrossProfit(
                takeProfitPrice = 103.0,
                entryPrice = 105.0,
                quantity = 10,
                side = TouchTurnTradeSide.LONG
            )
        )
    }

    @Test
    fun passes_whenMinZeroOrProjectedAboveThreshold() {
        assertTrue(
            TouchTurnGrossProfitGate.passes(
                setup = setup,
                entryPrice = 101.0,
                quantity = 10,
                minGrossProfit = 0.0
            )
        )
        assertTrue(
            TouchTurnGrossProfitGate.passes(
                setup = setup,
                entryPrice = 101.0,
                quantity = 10,
                minGrossProfit = 20.0
            )
        )
        assertFalse(
            TouchTurnGrossProfitGate.passes(
                setup = setup,
                entryPrice = 101.0,
                quantity = 10,
                minGrossProfit = 21.0
            )
        )
    }
}
