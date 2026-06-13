package daytrader.domain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TouchTurnTrailingStopWarningsTest {

    private fun msftLikeSetup() = TouchTurnBracketSetup(
        range = 9.2,
        rangeThreshold = 3.26,
        isLiquidityCandle = true,
        candleColor = FirstCandleColor.RED,
        side = TouchTurnTradeSide.LONG,
        entry = 382.724,
        stopLoss = 382.034,
        takeProfit = 388.244
    )

    private fun msftLikeRules() = TouchTurnRuleConfig.DEFAULT.copy(
        takeProfitFibRatioRed = 0.6,
        takeProfitToStopLossRatio = 8.0,
        trailingStopTriggerFractionOfEntryToTp = 0.4,
    )

    @Test
    fun validationError_whenEntryBelowInitialStopForLong() {
        val setup = msftLikeSetup().copy(
            entry = 382.034,
            stopLoss = 382.724
        )
        val error = TouchTurnTrailingStopWarnings.validationError(msftLikeRules(), setup)
        assertNotNull(error)
        assertTrue(error.contains("favorable side"))
    }

    @Test
    fun validationError_nullWhenTrailingDisabled() {
        val rules = msftLikeRules().copy(
            enables = TouchTurnRuleEnables.DEFAULT.copy(adjustableTrailingStop = false)
        )
        assertNull(TouchTurnTrailingStopWarnings.validationError(rules, msftLikeSetup()))
    }

    @Test
    fun validationError_nullForDefaultSetup() {
        assertNull(TouchTurnTrailingStopWarnings.validationError(msftLikeRules(), msftLikeSetup()))
    }

    @Test
    fun validationError_whenArmCushionTooWideForTightStop() {
        val rules = msftLikeRules().copy(
            trailingStopArmOffsetFractionOfBarRange = 0.10
        )
        val error = TouchTurnTrailingStopWarnings.validationError(rules, msftLikeSetup())
        assertNotNull(error)
        assertTrue(error.contains("initial fixed stop"))
    }

    @Test
    fun chartHint_prefixesValidationMessage() {
        val setup = msftLikeSetup().copy(
            entry = 382.034,
            stopLoss = 382.724
        )
        val hint = TouchTurnTrailingStopWarnings.chartHint(msftLikeRules(), setup)
        assertNotNull(hint)
        assertTrue(hint.startsWith("Trailing disabled —"))
    }

    @Test
    fun combineChartHints_joinsNonBlankParts() {
        assertEquals(
            "Bid/ask required · Trailing disabled — too wide",
            TouchTurnTrailingStopWarnings.combineChartHints(
                "Bid/ask required",
                "Trailing disabled — too wide"
            )
        )
        assertNull(TouchTurnTrailingStopWarnings.combineChartHints(null, null, ""))
    }
}
