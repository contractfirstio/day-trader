package daytrader.domain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

class TouchTurnAdjustableStopTest {

    @Test
    fun compute_longBracket_triggerAtHalfTp_trailHalfRisk() {
        val params = TouchTurnAdjustableStop.compute(
            entry = 100.0,
            stopLoss = 95.0,
            takeProfit = 110.0
        )
        assertNotNull(params)
        assertEquals(105.0, params.triggerPrice, 0.001)
        assertEquals(2.5, params.trailAmount, 0.001)
    }

    @Test
    fun compute_shortBracket_triggerAtHalfTp_trailHalfRisk() {
        val params = TouchTurnAdjustableStop.compute(
            entry = 100.0,
            stopLoss = 105.0,
            takeProfit = 90.0
        )
        assertNotNull(params)
        assertEquals(95.0, params.triggerPrice, 0.001)
        assertEquals(2.5, params.trailAmount, 0.001)
    }
}
