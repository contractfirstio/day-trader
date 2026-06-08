package daytrader.data

import daytrader.domain.OhlcBar
import daytrader.domain.TouchTurnLogic
import daytrader.domain.TouchTurnRuleConfig
import daytrader.domain.TouchTurnRuleEnables
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TouchTurnOrderLogTest {
    private val dojiBar = OhlcBar(open = 592.6, high = 593.2, low = 587.2, close = 592.6, volume = 489_555.0)
    private val dojiSetup = TouchTurnLogic.computeBracketSetup(dojiBar, rangeThreshold = 0.5)

    @Test
    fun logAfterLiquidityEvaluation_allowsDojiByDefault() {
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

    @Test
    fun logAfterLiquidityEvaluation_blocksDojiWhenNotDojiRuleEnabled() {
        assertFalse(dojiSetup.isActionable)
        val rules = TouchTurnRuleConfig.DEFAULT.copy(
            enables = TouchTurnRuleEnables.DEFAULT.copy(notDoji = true)
        )

        assertFalse(
            TouchTurnOrderLog.logAfterLiquidityEvaluation(
                instanceId = "inst-test",
                symbol = "NWG",
                sessionDate = "2026-06-08",
                maxDollars = 10_000,
                currencyCode = "GBP",
                setup = dojiSetup,
                rules = rules
            )
        )
    }
}
