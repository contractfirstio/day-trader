package daytrader.domain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class TouchTurnAdjustableStopTest {

    @Test
    fun compute_longBracket_triggerAtHalfTp_armsAtEntry() {
        val params = TouchTurnAdjustableStop.compute(
            entry = 100.0,
            stopLoss = 95.0,
            takeProfit = 110.0
        )
        assertNotNull(params)
        assertEquals(105.0, params.triggerPrice, 0.001)
        assertEquals(100.0, params.armStopPrice, 0.001)
    }

    @Test
    fun compute_shortBracket_triggerAtHalfTp_armsAtEntry() {
        val params = TouchTurnAdjustableStop.compute(
            entry = 100.0,
            stopLoss = 105.0,
            takeProfit = 90.0
        )
        assertNotNull(params)
        assertEquals(95.0, params.triggerPrice, 0.001)
        assertEquals(100.0, params.armStopPrice, 0.001)
    }

    @Test
    fun compute_longBracket_armFraction_placesStopBetweenEntryAndStop() {
        val params = TouchTurnAdjustableStop.compute(
            entry = 100.0,
            stopLoss = 95.0,
            takeProfit = 110.0,
            armFractionOfEntryToStop = 0.5
        )
        assertNotNull(params)
        assertEquals(97.5, params.armStopPrice, 0.001)
    }

    @Test
    fun compute_shortBracket_armFraction_placesStopBetweenEntryAndStop() {
        val params = TouchTurnAdjustableStop.compute(
            entry = 100.0,
            stopLoss = 105.0,
            takeProfit = 90.0,
            armFractionOfEntryToStop = 0.5
        )
        assertNotNull(params)
        assertEquals(102.5, params.armStopPrice, 0.001)
    }

    @Test
    fun validate_rejectsArmFractionAboveOne() {
        assertNotNull(
            TouchTurnAdjustableStop.validate(
                entry = 100.0,
                stopLoss = 95.0,
                takeProfit = 110.0,
                triggerFraction = 0.5,
                armFractionOfEntryToStop = 1.01
            )
        )
    }

    @Test
    fun inferBarRange_usesLargerBracketLeg() {
        assertEquals(10.0, TouchTurnAdjustableStop.inferBarRange(100.0, 95.0, 110.0), 0.001)
        assertEquals(3.0, TouchTurnAdjustableStop.inferBarRange(100.0, 99.0, 103.0), 0.001)
    }
}
