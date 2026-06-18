package daytrader.domain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TouchTurnTrailingStopConfigTest {

    private fun longSetup() = TouchTurnBracketSetup(
        range = 10.0,
        rangeThreshold = 2.0,
        isLiquidityCandle = true,
        candleColor = FirstCandleColor.GREEN,
        side = TouchTurnTradeSide.LONG,
        entry = 100.0,
        stopLoss = 95.0,
        takeProfit = 110.0
    )

    @Test
    fun buildOrderPlan_defaultTrailing_matchesHalfTpAndArmsAtEntry() {
        val plan = TouchTurnOrderPlanner.buildOrderPlan("AAPL", longSetup(), maxDollars = 1000)!!
        val stop = plan.orders.first { it.role == TouchTurnOrderRole.STOP_LOSS }
        assertEquals(105.0, stop.trailTriggerPrice!!, 0.001)
        assertEquals(100.0, stop.trailArmStopPrice!!, 0.001)
    }

    @Test
    fun buildOrderPlan_customArmFraction_appliesToStopLeg() {
        val rules = TouchTurnRuleConfig.DEFAULT.copy(
            trailingStopArmFractionOfEntryToStop = 0.5
        )
        val plan = TouchTurnOrderPlanner.buildOrderPlan(
            "AAPL",
            longSetup(),
            maxDollars = 1000,
            rules = rules
        )!!
        val stop = plan.orders.first { it.role == TouchTurnOrderRole.STOP_LOSS }
        assertEquals(97.5, stop.trailArmStopPrice!!, 0.001)
    }

    @Test
    fun buildOrderPlan_trailingDisabled_hasNoTrailOnStopLeg() {
        val rules = TouchTurnRuleConfig.DEFAULT.copy(
            enables = TouchTurnRuleEnables.DEFAULT.copy(adjustableTrailingStop = false)
        )
        val plan = TouchTurnOrderPlanner.buildOrderPlan(
            "AAPL",
            longSetup(),
            maxDollars = 1000,
            rules = rules
        )!!
        val stop = plan.orders.first { it.role == TouchTurnOrderRole.STOP_LOSS }
        assertNull(stop.trailTriggerPrice)
        assertNull(stop.trailArmStopPrice)
    }

    @Test
    fun buildOrderPlan_customTriggerFraction_appliesToStopLeg() {
        val rules = TouchTurnRuleConfig.DEFAULT.copy(
            trailingStopTriggerFractionOfEntryToTp = 0.25
        )
        val plan = TouchTurnOrderPlanner.buildOrderPlan(
            "AAPL",
            longSetup(),
            maxDollars = 1000,
            rules = rules
        )!!
        val stop = plan.orders.first { it.role == TouchTurnOrderRole.STOP_LOSS }
        assertEquals(102.5, stop.trailTriggerPrice!!, 0.001)
        assertEquals(100.0, stop.trailArmStopPrice!!, 0.001)
    }

    @Test
    fun withFieldValue_roundTripsTrailingFractionFields() {
        var config = TouchTurnRuleConfig.DEFAULT
        config = TouchTurnRuleConfig.withFieldValue(
            config,
            "trailingStopTriggerFractionOfEntryToTp",
            "0.4"
        )!!
        assertEquals(0.4, config.trailingStopTriggerFractionOfEntryToTp)
        config = TouchTurnRuleConfig.withFieldValue(
            config,
            "trailingStopArmFractionOfEntryToStop",
            "0.5"
        )!!
        assertEquals(0.5, config.trailingStopArmFractionOfEntryToStop)
    }

    @Test
    fun validate_defaultsAreValid() {
        assertNull(
            TouchTurnAdjustableStop.validate(
                entry = 100.0,
                stopLoss = 95.0,
                takeProfit = 110.0,
                triggerFraction = 0.5
            )
        )
        assertNull(TouchTurnRuleConfig.DEFAULT.trailingStopValidationError())
    }

    @Test
    fun validate_rejectsTriggerOutsideZeroToOne() {
        assertNotNull(
            TouchTurnAdjustableStop.validate(
                entry = 100.0,
                stopLoss = 95.0,
                takeProfit = 110.0,
                triggerFraction = -0.1
            )
        )
        assertNotNull(
            TouchTurnAdjustableStop.validate(
                entry = 100.0,
                stopLoss = 95.0,
                takeProfit = 110.0,
                triggerFraction = 1.1
            )
        )
        assertNull(
            TouchTurnRuleConfig.withFieldValue(
                TouchTurnRuleConfig.DEFAULT,
                "trailingStopTriggerFractionOfEntryToTp",
                "1.2"
            )
        )
    }

    @Test
    fun validate_rejectsWhenEntryOnWrongSideOfInitialStop() {
        val error = TouchTurnAdjustableStop.validate(
            entry = 94.0,
            stopLoss = 95.0,
            takeProfit = 110.0,
            triggerFraction = 0.5
        )
        assertNotNull(error)
        assertTrue(error.contains("favorable side"))
    }

    @Test
    fun computeAdjustableStop_returnsNullWhenConfigIncompatible() {
        assertNull(
            TouchTurnAdjustableStop.compute(
                entry = 94.0,
                stopLoss = 95.0,
                takeProfit = 110.0
            )
        )
        assertNull(
            TouchTurnRuleConfig.DEFAULT.computeAdjustableStop(
                entry = 94.0,
                stopLoss = 95.0,
                takeProfit = 110.0
            )
        )
    }

    @Test
    fun computeAdjustableStop_respectsToggleAndFractions() {
        val disabled = TouchTurnRuleConfig.DEFAULT.copy(
            enables = TouchTurnRuleEnables.DEFAULT.copy(adjustableTrailingStop = false)
        )
        assertNull(disabled.computeAdjustableStop(100.0, 95.0, 110.0))

        val custom = TouchTurnRuleConfig.DEFAULT.copy(
            trailingStopTriggerFractionOfEntryToTp = 0.25,
            trailingStopArmFractionOfEntryToStop = 0.5
        )
        val params = custom.computeAdjustableStop(100.0, 95.0, 110.0)
        assertNotNull(params)
        assertEquals(102.5, params.triggerPrice, 0.001)
        assertEquals(97.5, params.armStopPrice, 0.001)
    }
}
