package daytrader.domain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TouchTurnExtremeBounceEvaluatorTest {

    private val bounceRules = TouchTurnRuleConfig.DEFAULT.copy(
        enables = TouchTurnRuleEnables.DEFAULT.copy(bounceRejection = true),
        requiredExtremeBounceCount = 2,
        bounceTouchZoneRatioOfRange = 0.05,
        bounceRecoveryRatioOfRange = 0.15
    )

    @Test
    fun countBounces_long_twoQualifiedBounces() {
        val bar = OhlcBar(open = 110.0, high = 110.0, low = 100.0, close = 105.0)
        val setup = TouchTurnLogic.computeBracketSetup(bar, rangeThreshold = 1.0)
        val samples = listOf(
            sample(100.2),
            sample(102.0),
            sample(100.1),
            sample(103.0)
        )
        val result = TouchTurnExtremeBounceEvaluator.evaluate(setup, bar, samples, bounceRules)
        assertEquals(2, result.bounceCount)
        assertTrue(result.passed)
    }

    @Test
    fun countBounces_long_insufficientBounces() {
        val bar = OhlcBar(open = 110.0, high = 110.0, low = 100.0, close = 100.5)
        val setup = TouchTurnLogic.computeBracketSetup(bar, rangeThreshold = 1.0)
        val samples = listOf(sample(100.2), sample(100.4))
        val result = TouchTurnExtremeBounceEvaluator.evaluate(setup, bar, samples, bounceRules)
        assertEquals(0, result.bounceCount)
        assertFalse(result.passed)
    }

    @Test
    fun countBounces_short_twoQualifiedBounces() {
        val bar = OhlcBar(open = 100.0, high = 110.0, low = 100.0, close = 105.0)
        val setup = TouchTurnLogic.computeBracketSetup(bar, rangeThreshold = 1.0)
        val samples = listOf(
            sample(109.8),
            sample(107.0),
            sample(109.7),
            sample(106.0)
        )
        val result = TouchTurnExtremeBounceEvaluator.evaluate(setup, bar, samples, bounceRules)
        assertEquals(2, result.bounceCount)
        assertTrue(result.passed)
    }

    @Test
    fun evaluateEntryGate_blocksWhenBounceCountTooLow() {
        val bar = OhlcBar(open = 110.0, high = 110.0, low = 100.0, close = 100.5, time = "20250522  09:30:00")
        val setup = TouchTurnLogic.computeBracketSetup(bar, rangeThreshold = 1.0)
        val barEnd = TouchTurnLogic.barEndEpochMillis(bar.time!!, "America/New_York")!!
        val gate = TouchTurnLogic.evaluateEntryGate(
            setup = setup,
            candle = bar,
            marketZoneId = "America/New_York",
            nowEpochMillis = barEnd + 1_000,
            sessionDateIso = "2025-05-22",
            rules = bounceRules,
            openingBarPriceSamples = listOf(sample(100.2))
        )
        assertFalse(gate.entryOrdersPermitted)
        assertEquals(TouchTurnSessionOutcome.NO_TRADE_BOUNCE_REJECTION_FAILED, gate.decisionOutcome)
    }

    @Test
    fun evaluateEntryGate_noSamplesWhenBounceEnabled_failsDataUnavailable() {
        val bar = OhlcBar(open = 110.0, high = 110.0, low = 100.0, close = 100.5, time = "20250522  09:30:00")
        val setup = TouchTurnLogic.computeBracketSetup(bar, rangeThreshold = 1.0)
        val barEnd = TouchTurnLogic.barEndEpochMillis(bar.time!!, "America/New_York")!!
        val gate = TouchTurnLogic.evaluateEntryGate(
            setup = setup,
            candle = bar,
            marketZoneId = "America/New_York",
            nowEpochMillis = barEnd + 1_000,
            sessionDateIso = "2025-05-22",
            rules = bounceRules,
            openingBarPriceSamples = emptyList()
        )
        assertFalse(gate.entryOrdersPermitted)
        assertEquals(TouchTurnSessionOutcome.NO_TRADE_BOUNCE_DATA_UNAVAILABLE, gate.decisionOutcome)
    }

    @Test
    fun evaluateEntryGate_bounceDisabled_skipsRule() {
        val bar = OhlcBar(open = 110.0, high = 110.0, low = 100.0, close = 100.5, time = "20250522  09:30:00")
        val setup = TouchTurnLogic.computeBracketSetup(bar, rangeThreshold = 1.0)
        val barEnd = TouchTurnLogic.barEndEpochMillis(bar.time!!, "America/New_York")!!
        val gate = TouchTurnLogic.evaluateEntryGate(
            setup = setup,
            candle = bar,
            marketZoneId = "America/New_York",
            nowEpochMillis = barEnd + 1_000,
            sessionDateIso = "2025-05-22"
        )
        assertTrue(gate.entryOrdersPermitted)
    }

    private fun sample(price: Double, at: Long = 1_000L) =
        TouchTurnOpeningBarPriceSample(epochMs = at, price = price)
}
