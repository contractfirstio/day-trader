package daytrader.presentation.strategies

import daytrader.domain.FirstCandleColor
import daytrader.domain.OhlcBar
import daytrader.domain.TouchTurnBracketSetup
import daytrader.domain.TouchTurnCandleStatus
import daytrader.domain.TouchTurnLogic
import daytrader.domain.TouchTurnRuleConfig
import daytrader.domain.TouchTurnRuleEnables
import daytrader.domain.TouchTurnSessionContext
import daytrader.domain.TouchTurnTradeSide
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TouchTurnRuleExplanationMapperTest {
    @Test
    fun buildChecks_includesAllConfiguredRules() {
        val checks = TouchTurnRuleExplanationMapper.buildChecks(
            session = sampleSession(),
            evaluationInstant = barEndPlusOne(),
            verboseExplanations = true,
            requireLivePriceChecks = false
        )
        val checkKeys = checks.map { it.key }.toSet()
        TouchTurnRuleConfig.toggleDefinitions.forEach { def ->
            assertTrue(def.key in checkKeys, "missing toggle check: ${def.key}")
        }
        assertTrue("greenLiquidityBarAction" in checkKeys)
        assertTrue("redLiquidityBarAction" in checkKeys)
    }

    @Test
    fun buildChecks_verboseEnabledRuleHasSteps() {
        val checks = TouchTurnRuleExplanationMapper.buildChecks(
            session = sampleSession(),
            evaluationInstant = barEndPlusOne(),
            verboseExplanations = true,
            requireLivePriceChecks = false
        )
        val liquidity = checks.first { it.key == "liquidityRangeDailyAtr" }
        assertTrue(liquidity.enabled)
        assertTrue(liquidity.explanationSteps.size >= 4)
        assertTrue(liquidity.explanationSteps.last().startsWith("Result:"))
    }

    @Test
    fun buildChecks_liquidityGuardUsesConfiguredRatioAndShowsObserved() {
        val dailyAtr = 1.3871428571428575
        val session = sampleSession().copy(
            sessionDate = "2026-07-27",
            candle = OhlcBar(
                open = 26.88,
                high = 28.32,
                low = 26.84,
                close = 27.96,
                volume = 50_798_400.0,
                time = "20260727  09:30:00"
            ),
            setup = TouchTurnBracketSetup(
                range = 1.48,
                rangeThreshold = dailyAtr * 0.7,
                isLiquidityCandle = true,
                candleColor = FirstCandleColor.GREEN,
                side = TouchTurnTradeSide.LONG,
                entry = 28.32,
                stopLoss = 28.08,
                takeProfit = 28.89
            ),
            marketZoneId = "Asia/Hong_Kong",
            currencyCode = "HKD",
            dailyAtr14 = dailyAtr,
            rangeThreshold = dailyAtr * 0.7,
            rules = TouchTurnRuleConfig.DEFAULT.copy(
                atrLiquidityRatio = 0.7,
                enables = TouchTurnRuleEnables.DEFAULT.copy(liquidityRangeDailyAtr = true)
            )
        )
        val checks = TouchTurnRuleExplanationMapper.buildChecks(
            session = session,
            evaluationInstant = TouchTurnLogic.barEndEpochMillis("20260727  09:30:00", "Asia/Hong_Kong")!! + 1,
            verboseExplanations = true,
            requireLivePriceChecks = false
        )
        val liquidity = checks.first { it.key == "liquidityRangeDailyAtr" }
        assertTrue(liquidity.description.contains("70%"))
        assertFalse(liquidity.description.contains("25%"))
        assertEquals("OK · 106.7% of ATR (need ≥70%)", liquidity.detail)
        assertTrue(liquidity.explanationSteps.any { it.contains("106.7%") && it.contains("70%") })
    }

    @Test
    fun buildChecks_disabledRuleOmitsStepsWhenVerbose() {
        val session = sampleSession().copy(
            rules = TouchTurnRuleConfig.DEFAULT.copy(
                enables = TouchTurnRuleEnables.DEFAULT.copy(liquidityRangeDailyAtr = false)
            )
        )
        val checks = TouchTurnRuleExplanationMapper.buildChecks(
            session = session,
            evaluationInstant = barEndPlusOne(),
            verboseExplanations = true,
            requireLivePriceChecks = false
        )
        val liquidity = checks.first { it.key == "liquidityRangeDailyAtr" }
        assertFalse(liquidity.enabled)
        assertTrue(liquidity.explanationSteps.isEmpty())
    }

    @Test
    fun buildChecks_failedTrailingKeepsStepsDuringLiveReview() {
        val session = sampleSession().copy(
            setup = TouchTurnBracketSetup(
                range = 9.2,
                rangeThreshold = 2.5,
                isLiquidityCandle = true,
                candleColor = FirstCandleColor.RED,
                side = TouchTurnTradeSide.LONG,
                entry = 382.034,
                stopLoss = 382.724,
                takeProfit = 388.244
            ),
            rules = TouchTurnRuleConfig.DEFAULT.copy(
                trailingStopTriggerFractionOfEntryToTp = 0.4,
                takeProfitToStopLossRatio = 8.0
            )
        )
        val checks = TouchTurnRuleExplanationMapper.buildChecks(
            session = session,
            evaluationInstant = barEndPlusOne(),
            verboseExplanations = false,
            requireLivePriceChecks = false
        )
        val trailing = checks.first { it.key == "adjustableTrailingStop" }
        assertEquals(false, trailing.passed)
        assertTrue(trailing.explanationSteps.isNotEmpty())
        assertTrue(trailing.detail!!.contains("favorable side"))
    }

    @Test
    fun rulesEvaluation_populatesStepsForClosedSessionReview() {
        val session = sampleSession()
        val evaluation = TouchTurnPipelineDetailUiMapper.rulesEvaluation(
            session = session,
            verboseExplanations = true,
            requireLivePriceChecks = false
        )
        requireNotNull(evaluation)
        val enabledWithSteps = evaluation.checks.filter { it.enabled && it.explanationSteps.isNotEmpty() }
        assertTrue(enabledWithSteps.isNotEmpty())
    }

    private fun sampleSession(): TouchTurnSessionContext {
        val barTime = "20260522  09:30:00"
        val candle = OhlcBar(open = 100.0, high = 110.0, low = 99.0, close = 108.0, volume = 50_000.0, time = barTime)
        val setup = TouchTurnBracketSetup(
            range = 11.0,
            rangeThreshold = 2.5,
            isLiquidityCandle = true,
            candleColor = FirstCandleColor.GREEN,
            side = TouchTurnTradeSide.SHORT,
            entry = 110.0,
            stopLoss = 113.0,
            takeProfit = 103.0
        )
        return TouchTurnSessionContext(
            sessionDate = "2026-05-22",
            status = TouchTurnCandleStatus.READY,
            candle = candle,
            setup = setup,
            marketZoneId = "America/New_York",
            currencyCode = "USD",
            dailyAtr14 = 10.0,
            volumeSma20 = 40_000.0,
            rangeThreshold = 2.5,
            rules = TouchTurnRuleConfig.DEFAULT.copy(
                enables = TouchTurnRuleEnables.DEFAULT.copy(liquidityRangeDailyAtr = true)
            ),
            entryOrdersPermitted = true,
            milestones = daytrader.domain.TouchTurnMilestoneTimestamps(
                liquidityEvaluatedAt = "2026-05-22T09:45:01"
            )
        )
    }

    private fun barEndPlusOne(): Long {
        val barEnd = TouchTurnLogic.barEndEpochMillis("20260522  09:30:00", "America/New_York")!!
        return barEnd + 1
    }
}
