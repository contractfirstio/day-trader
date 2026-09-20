package daytrader.domain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class FiveMinuteConfirmationLogicTest {
    private val fifteenMinBar = OhlcBar(
        open = 100.0,
        high = 110.0,
        low = 99.0,
        close = 108.0,
        time = "20260522  09:30:00"
    )

    private val fifteenMinuteSetup = TouchTurnBracketSetup(
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
    fun shouldBypass_whenToggleOffOrInvertOn() {
        val enabled = TouchTurnRuleConfig(enables = TouchTurnRuleEnables(fiveMinuteConfirmation = true))
        assertTrue(FiveMinuteConfirmationLogic.shouldUseModule(enabled))
        assertFalse(FiveMinuteConfirmationLogic.shouldBypass(enabled))

        assertFalse(
            FiveMinuteConfirmationLogic.shouldUseModule(
                enabled.copy(invertTradeSide = true)
            )
        )
        assertTrue(
            FiveMinuteConfirmationLogic.shouldBypass(
                TouchTurnRuleConfig(enables = TouchTurnRuleEnables(fiveMinuteConfirmation = false))
            )
        )
    }

    @Test
    fun sweepPrice_usesBarExtremeBySide() {
        assertEquals(99.0, FiveMinuteConfirmationLogic.sweepPrice(fifteenMinBar, TouchTurnTradeSide.LONG))
        assertEquals(110.0, FiveMinuteConfirmationLogic.sweepPrice(fifteenMinBar, TouchTurnTradeSide.SHORT))
    }

    @Test
    fun isHammerPattern_longRejectionShadow() {
        val hammer = OhlcBar(open = 101.0, high = 101.3, low = 100.0, close = 101.2)
        assertTrue(FiveMinuteConfirmationLogic.isHammerPattern(hammer, TouchTurnTradeSide.LONG))
        val notHammer = OhlcBar(open = 100.5, high = 101.5, low = 100.0, close = 101.4)
        assertFalse(FiveMinuteConfirmationLogic.isHammerPattern(notHammer, TouchTurnTradeSide.LONG))
    }

    @Test
    fun evaluateHammer_invalidatesWhenCloseOutsideFifteenMinRange() {
        val outside = OhlcBar(open = 101.0, high = 101.2, low = 98.0, close = 98.5)
        val result = FiveMinuteConfirmationLogic.evaluateHammer(
            bar = outside,
            side = TouchTurnTradeSide.LONG,
            fifteenMinuteBar = fifteenMinBar
        )
        assertTrue(result.invalidatesSetup)
        assertFalse(result.closeInsideSweepRange)
    }

    @Test
    fun isEngulfingPattern_bullishClassic() {
        val prior = OhlcBar(open = 102.0, high = 102.2, low = 100.8, close = 101.0) // red
        val current = OhlcBar(open = 100.5, high = 103.5, low = 100.4, close = 103.0) // green, body covers
        assertTrue(FiveMinuteConfirmationLogic.isEngulfingPattern(prior, current, TouchTurnTradeSide.LONG))
        assertFalse(FiveMinuteConfirmationLogic.isHammerPattern(current, TouchTurnTradeSide.LONG))
    }

    @Test
    fun isEngulfingPattern_bearishClassic() {
        val prior = OhlcBar(open = 101.0, high = 102.2, low = 100.8, close = 102.0) // green
        val current = OhlcBar(open = 102.5, high = 102.6, low = 100.0, close = 100.5) // red, body covers
        assertTrue(FiveMinuteConfirmationLogic.isEngulfingPattern(prior, current, TouchTurnTradeSide.SHORT))
        assertFalse(FiveMinuteConfirmationLogic.isHammerPattern(current, TouchTurnTradeSide.SHORT))
    }

    @Test
    fun isEngulfingPattern_rejectsSameColorPrior() {
        val prior = OhlcBar(open = 101.0, high = 102.0, low = 100.5, close = 101.8) // green
        val current = OhlcBar(open = 100.5, high = 103.0, low = 100.4, close = 102.5) // green
        assertFalse(FiveMinuteConfirmationLogic.isEngulfingPattern(prior, current, TouchTurnTradeSide.LONG))
    }

    @Test
    fun isEngulfingPattern_rejectsPartialBodyCover() {
        val prior = OhlcBar(open = 102.0, high = 102.2, low = 100.0, close = 100.5) // red, wide body
        val current = OhlcBar(open = 101.0, high = 102.5, low = 100.8, close = 102.0) // green, does not cover prior low body
        assertFalse(FiveMinuteConfirmationLogic.isEngulfingPattern(prior, current, TouchTurnTradeSide.LONG))
    }

    @Test
    fun isEngulfingPattern_rejectsDojiPrior() {
        val prior = OhlcBar(open = 101.0, high = 101.5, low = 100.5, close = 101.0)
        val current = OhlcBar(open = 100.5, high = 102.5, low = 100.4, close = 102.0)
        assertFalse(FiveMinuteConfirmationLogic.isEngulfingPattern(prior, current, TouchTurnTradeSide.LONG))
    }

    @Test
    fun evaluateConfirmation_qualifiesOnEngulfingWithoutHammer() {
        val prior = OhlcBar(open = 102.0, high = 102.2, low = 100.8, close = 101.0)
        val current = OhlcBar(open = 100.5, high = 103.5, low = 100.4, close = 103.0)
        val result = FiveMinuteConfirmationLogic.evaluateConfirmation(
            bar = current,
            priorBar = prior,
            side = TouchTurnTradeSide.LONG,
            fifteenMinuteBar = fifteenMinBar
        )
        assertTrue(result.isQualifying)
        assertTrue(result.isEngulfing)
        assertFalse(result.isHammer)
        assertFalse(result.invalidatesSetup)
    }

    @Test
    fun evaluateConfirmation_qualifiesOnHammerWithoutEngulfing() {
        val hammer = OhlcBar(open = 101.0, high = 101.3, low = 100.0, close = 101.2)
        val result = FiveMinuteConfirmationLogic.evaluateConfirmation(
            bar = hammer,
            priorBar = null,
            side = TouchTurnTradeSide.LONG,
            fifteenMinuteBar = fifteenMinBar
        )
        assertTrue(result.isQualifying)
        assertTrue(result.isHammer)
        assertFalse(result.isEngulfing)
    }

    @Test
    fun evaluateConfirmation_invalidatesWhenCloseOutsideFifteenMinRange() {
        val prior = OhlcBar(open = 102.0, high = 102.2, low = 100.8, close = 101.0)
        val outside = OhlcBar(open = 100.5, high = 101.0, low = 98.0, close = 98.5)
        val result = FiveMinuteConfirmationLogic.evaluateConfirmation(
            bar = outside,
            priorBar = prior,
            side = TouchTurnTradeSide.LONG,
            fifteenMinuteBar = fifteenMinBar
        )
        assertTrue(result.invalidatesSetup)
        assertFalse(result.isQualifying)
        assertFalse(result.closeInsideSweepRange)
    }

    @Test
    fun partitionFiveMinuteBars_separatesPreWindowPriorFromWindowBars() {
        val windowStart = 1_000_000L
        val prior = OhlcBar(open = 100.0, high = 101.0, low = 99.0, close = 99.5, time = "prior")
        val first = OhlcBar(open = 99.5, high = 102.0, low = 99.0, close = 101.5, time = "first")
        val second = OhlcBar(open = 101.5, high = 102.0, low = 101.0, close = 101.8, time = "second")
        val barOpenEpochMs: (OhlcBar) -> Long? = { bar ->
            when (bar.time) {
                "prior" -> windowStart - FiveMinuteConfirmationLogic.BAR_DURATION_MS
                "first" -> windowStart
                "second" -> windowStart + FiveMinuteConfirmationLogic.BAR_DURATION_MS
                else -> null
            }
        }
        val partitioned = FiveMinuteConfirmationLogic.partitionFiveMinuteBars(
            bars = listOf(prior, first, second),
            windowStartEpochMs = windowStart,
            barOpenEpochMs = barOpenEpochMs
        )
        assertEquals(prior, partitioned.contextPrior)
        assertEquals(listOf(first, second), partitioned.windowBars)
    }

    @Test
    fun buildConfirmationSetup_recomputesStopFromMarketEntryWithRatio() {
        val hammer = OhlcBar(open = 101.0, high = 101.3, low = 100.0, close = 101.2)
        val setup = FiveMinuteConfirmationLogic.buildConfirmationSetup(
            fifteenMinuteSetup,
            marketEntry = hammer.close
        )
        assertEquals(101.2, setup.entry)
        assertEquals(fifteenMinuteSetup.takeProfit, setup.takeProfit)
        assertEquals(100.3, setup.stopLoss, absoluteTolerance = 1e-9)
        val reward = setup.takeProfit - setup.entry
        val risk = setup.entry - setup.stopLoss
        assertEquals(reward / risk, 2.0, absoluteTolerance = 1e-9)
    }

    @Test
    fun entryPastTakeProfit_detectsHammerCrossingFifteenMinuteTp_greenShort() {
        val openingBar = OhlcBar(
            open = 380.33504,
            high = 383.77664,
            low = 379.922048,
            close = 380.74803199999997
        )
        val fifteenMinuteSetup = TouchTurnLogic.computeBracketSetup(
            bar = openingBar,
            liquidityThresholds = TouchTurnLiquidityThresholds(thresholdDailyAtr = 0.0)
        )
        assertEquals(TouchTurnTradeSide.SHORT, fifteenMinuteSetup.side)

        val hammerEntry = 380.83822945279996
        assertTrue(FiveMinuteConfirmationLogic.entryPastTakeProfit(fifteenMinuteSetup, hammerEntry))
        assertEquals(null, TouchTurnOrderPlanner.buildHammerConfirmationOrderPlan(
            symbol = "SPY",
            fifteenMinuteSetup = fifteenMinuteSetup,
            hammerBar = OhlcBar(open = hammerEntry, high = hammerEntry, low = hammerEntry, close = hammerEntry),
            maxDollars = 10_000
        ))
    }

    @Test
    fun entryPastTakeProfit_falseWhenHammerStillInsideProfitableZone() {
        val hammer = OhlcBar(open = 101.0, high = 101.3, low = 100.0, close = 101.2)
        assertFalse(FiveMinuteConfirmationLogic.entryPastTakeProfit(fifteenMinuteSetup, hammer.close))
    }

    @Test
    fun applyMarketEntryToFifteenMinuteSetup_delegatesToBuildConfirmationSetup() {
        val hammer = OhlcBar(open = 101.0, high = 101.3, low = 100.0, close = 101.2)
        val setup = FiveMinuteConfirmationLogic.applyMarketEntryToFifteenMinuteSetup(
            fifteenMinuteSetup,
            hammer
        )
        assertEquals(
            FiveMinuteConfirmationLogic.buildConfirmationSetup(fifteenMinuteSetup, hammer.close),
            setup
        )
    }

    @Test
    fun projectedGrossProfit_usesSignedDistanceToFifteenMinuteTakeProfit() {
        val projected = TouchTurnGrossProfitGate.projectedMaxProfit(
            takeProfitPrice = 103.0,
            entryPrice = 101.0,
            quantity = 10,
            side = TouchTurnTradeSide.LONG,
            currency = "USD",
        )
        assertEquals(19.30, projected, 0.001)
    }

    @Test
    fun passesGrossProfitGate_whenMinZeroOrProjectedRatioAboveThreshold() {
        val hammer = OhlcBar(open = 101.0, high = 101.3, low = 100.0, close = 101.0)
        // Confirmation setup: TP 103, entry 101 → stop recomputed at 100 (default 2.0 geometry)
        // Net ratio ≈ 19.30 / 10.70 ≈ 1.804
        assertTrue(
            FiveMinuteConfirmationLogic.passesGrossProfitGate(
                fifteenMinuteSetup = fifteenMinuteSetup,
                hammerBar = hammer,
                quantity = 10,
                minProfitToLossRatio = 0.0,
                currency = "USD",
            )
        )
        assertTrue(
            FiveMinuteConfirmationLogic.passesGrossProfitGate(
                fifteenMinuteSetup = fifteenMinuteSetup,
                hammerBar = hammer,
                quantity = 10,
                minProfitToLossRatio = 1.8,
                currency = "USD",
            )
        )
        assertFalse(
            FiveMinuteConfirmationLogic.passesGrossProfitGate(
                fifteenMinuteSetup = fifteenMinuteSetup,
                hammerBar = hammer,
                quantity = 10,
                minProfitToLossRatio = 1.9,
                currency = "USD",
            )
        )
        assertFalse(
            FiveMinuteConfirmationLogic.passesGrossProfitGate(
                fifteenMinuteSetup = fifteenMinuteSetup,
                hammerBar = hammer,
                quantity = 10,
                minProfitToLossRatio = 100.0,
                currency = "USD",
            )
        )
    }

    @Test
    fun fiveMinuteConfirmation_hiddenAndIgnoredWhenInvertOn() {
        val invertWithFiveMinStored = TouchTurnRuleConfig(
            enables = TouchTurnRuleEnables(fiveMinuteConfirmation = true),
            invertTradeSide = true
        )
        assertFalse(TouchTurnRuleConfig.isFiveMinuteConfirmationVisible(invertWithFiveMinStored))
        assertFalse(TouchTurnRuleConfig.isFiveMinuteConfirmationEffective(invertWithFiveMinStored))
        assertFalse(FiveMinuteConfirmationLogic.shouldUseModule(invertWithFiveMinStored))
        assertTrue(invertWithFiveMinStored.enables.fiveMinuteConfirmation)

        val reversalWithFiveMin = TouchTurnRuleConfig(
            enables = TouchTurnRuleEnables(fiveMinuteConfirmation = true),
            invertTradeSide = false
        )
        assertTrue(TouchTurnRuleConfig.isFiveMinuteConfirmationVisible(reversalWithFiveMin))
        assertTrue(TouchTurnRuleConfig.isFiveMinuteConfirmationEffective(reversalWithFiveMin))
        assertTrue(FiveMinuteConfirmationLogic.shouldUseModule(reversalWithFiveMin))
    }

    @Test
    fun buildHammerConfirmationOrderPlan_usesMarketEntryAndRecomputedStopWithFifteenMinuteTarget() {
        val hammer = OhlcBar(open = 101.0, high = 101.3, low = 100.0, close = 101.2)
        val plan = TouchTurnOrderPlanner.buildHammerConfirmationOrderPlan(
            symbol = "SPY",
            fifteenMinuteSetup = fifteenMinuteSetup,
            hammerBar = hammer,
            maxDollars = 10_000
        )
        requireNotNull(plan)
        val confirmationSetup = FiveMinuteConfirmationLogic.buildConfirmationSetup(
            fifteenMinuteSetup,
            hammer.close
        )
        assertEquals("MKT", plan.orders.first { it.role == TouchTurnOrderRole.ENTRY }.orderType)
        assertEquals(101.2, plan.orders.first { it.role == TouchTurnOrderRole.ENTRY }.price)
        assertEquals(
            confirmationSetup.takeProfit,
            plan.orders.first { it.role == TouchTurnOrderRole.TAKE_PROFIT }.price
        )
        assertEquals(
            confirmationSetup.stopLoss,
            plan.orders.first { it.role == TouchTurnOrderRole.STOP_LOSS }.price
        )
    }

    @Test
    fun buildHammerConfirmationOrderPlan_trailingUsesMarketEntry() {
        val hammer = OhlcBar(open = 101.0, high = 101.3, low = 100.0, close = 101.2)
        val rules = TouchTurnRuleConfig(
            enables = TouchTurnRuleEnables(adjustableTrailingStop = true),
            trailingStopTriggerFractionOfEntryToTp = 0.5,
            trailingStopArmFractionOfEntryToStop = 0.0
        )
        val confirmationSetup = FiveMinuteConfirmationLogic.buildConfirmationSetup(
            fifteenMinuteSetup,
            hammer.close,
            rules
        )
        val plan = TouchTurnOrderPlanner.buildHammerConfirmationOrderPlan(
            symbol = "SPY",
            fifteenMinuteSetup = fifteenMinuteSetup,
            hammerBar = hammer,
            maxDollars = 10_000,
            rules = rules
        )!!
        val stop = plan.orders.first { it.role == TouchTurnOrderRole.STOP_LOSS }
        val expectedTrail = rules.computeAdjustableStop(
            entry = confirmationSetup.entry,
            stopLoss = confirmationSetup.stopLoss,
            takeProfit = confirmationSetup.takeProfit
        )
        requireNotNull(expectedTrail)
        assertEquals(expectedTrail.triggerPrice, stop.trailTriggerPrice)
        assertEquals(expectedTrail.armStopPrice, stop.trailArmStopPrice)
    }

    @Test
    fun stateAfterBarEvaluated_tracksProcessedTimesAndBarBodies() {
        val initial = FiveMinuteConfirmationLogic.initialState(
            candle = fifteenMinBar,
            side = TouchTurnTradeSide.LONG,
            nowEpochMillis = 1L
        )
        val bar = OhlcBar(open = 105.0, high = 106.0, low = 104.0, close = 105.5, time = "20260522  09:35:00")
        val updated = FiveMinuteConfirmationLogic.stateAfterBarEvaluated(initial, bar)
        assertEquals(listOf("20260522  09:35:00"), updated.processedBarTimes)
        assertEquals(listOf(bar), updated.evaluatedBars)
        assertEquals(updated, FiveMinuteConfirmationLogic.stateAfterBarEvaluated(updated, bar))
    }
}
