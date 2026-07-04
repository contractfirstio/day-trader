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
    fun computeHammerBracketSetup_enforcesTwoToOneRewardRisk() {
        val hammer = OhlcBar(open = 101.0, high = 101.3, low = 100.0, close = 101.2)
        val setup = FiveMinuteConfirmationLogic.computeHammerBracketSetup(hammer, TouchTurnTradeSide.LONG)
        assertEquals(101.2, setup.entry)
        assertEquals(100.0, setup.stopLoss)
        assertEquals(101.2 + (101.2 - 100.0) * 2.0, setup.takeProfit)
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
        // Stored preference is preserved — not cleared when invert is on.
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
    fun buildHammerConfirmationOrderPlan_usesMarketEntry() {
        val hammer = OhlcBar(open = 101.0, high = 101.3, low = 100.0, close = 101.2)
        val plan = TouchTurnOrderPlanner.buildHammerConfirmationOrderPlan(
            symbol = "SPY",
            hammerBar = hammer,
            side = TouchTurnTradeSide.LONG,
            maxDollars = 10_000
        )
        requireNotNull(plan)
        assertEquals("MKT", plan.orders.first { it.role == TouchTurnOrderRole.ENTRY }.orderType)
    }
}
