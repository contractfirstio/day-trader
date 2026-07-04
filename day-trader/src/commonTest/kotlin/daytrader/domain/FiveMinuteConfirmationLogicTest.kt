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
    fun projectedGrossProfit_usesAbsoluteDistanceToFifteenMinuteTakeProfit() {
        val projected = TouchTurnGrossProfitGate.projectedGrossProfit(
            takeProfitPrice = 103.0,
            entryPrice = 101.0,
            quantity = 10
        )
        assertEquals(20.0, projected)
    }

    @Test
    fun passesGrossProfitGate_whenMinZeroOrProjectedAboveThreshold() {
        val hammer = OhlcBar(open = 101.0, high = 101.3, low = 100.0, close = 101.0)
        assertTrue(
            FiveMinuteConfirmationLogic.passesGrossProfitGate(
                fifteenMinuteSetup = fifteenMinuteSetup,
                hammerBar = hammer,
                quantity = 10,
                minGrossProfit = 0.0
            )
        )
        assertTrue(
            FiveMinuteConfirmationLogic.passesGrossProfitGate(
                fifteenMinuteSetup = fifteenMinuteSetup,
                hammerBar = hammer,
                quantity = 10,
                minGrossProfit = 20.0
            )
        )
        assertFalse(
            FiveMinuteConfirmationLogic.passesGrossProfitGate(
                fifteenMinuteSetup = fifteenMinuteSetup,
                hammerBar = hammer,
                quantity = 10,
                minGrossProfit = 21.0
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
