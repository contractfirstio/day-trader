package daytrader.data

import daytrader.domain.OhlcBar
import daytrader.domain.TouchTurnLogic
import daytrader.domain.TouchTurnRuleConfig
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TouchTurnOrderLogTest {
    private val dojiBar = OhlcBar(open = 592.6, high = 593.2, low = 587.2, close = 592.6, volume = 489_555.0)
    private val dojiSetup = TouchTurnLogic.computeBracketSetup(dojiBar, rangeThreshold = 0.5)

    @Test
    fun logAfterLiquidityEvaluation_allowsDojiBar() {
        assertFalse(dojiSetup.isActionable)
        assertTrue(TouchTurnLogic.setupActionableForEntry(dojiSetup, TouchTurnRuleConfig.DEFAULT))

        assertTrue(
            TouchTurnOrderLog.logAfterLiquidityEvaluation(
                instanceId = "inst-test",
                symbol = "NWG",
                sessionDate = "2026-06-08",
                maxDollars = 10_000,
                currencyCode = "GBP",
                setup = dojiSetup
            )
        )
    }
}
