package daytrader.broker.emulator

import daytrader.domain.FirstCandleColor
import daytrader.domain.OhlcBar
import daytrader.domain.TouchTurnLogic
import kotlin.test.Test
import kotlin.test.assertTrue

class BracketExitSimulationTest {

    @Test
    fun naturalTakeProfitFirstProbability_redLiquidityLong_entryCloserToStop() {
        val bar = OhlcBar(open = 410.0, high = 410.0, low = 400.0, close = 402.0)
        val setup = TouchTurnLogic.computeBracketSetup(bar, rangeThreshold = 5.0)!!
        val p = naturalTakeProfitFirstProbability(setup.entry, setup.stopLoss, setup.takeProfit)
        assertTrue(
            p < 0.45,
            "Touch Turn long places stop at half the TP distance; expected TP-first well below 50% (was $p)"
        )
    }

    @Test
    fun naturalTakeProfitFirstProbability_symmetricEntry_isFiftyFifty() {
        val p = naturalTakeProfitFirstProbability(entry = 100.0, stopLoss = 99.0, takeProfit = 101.0)
        assertTrue(kotlin.math.abs(p - 0.5) < 0.01, "expected 50% TP-first at midpoint, got $p")
    }
}
