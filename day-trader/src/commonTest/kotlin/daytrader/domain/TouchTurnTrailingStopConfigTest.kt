package daytrader.domain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

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
    fun buildOrderPlan_defaultTrailing_matchesHalfTpAndHalfRisk() {
        val plan = TouchTurnOrderPlanner.buildOrderPlan("AAPL", longSetup(), maxDollars = 1000)!!
        val stop = plan.orders.first { it.role == TouchTurnOrderRole.STOP_LOSS }
        assertEquals(105.0, stop.trailTriggerPrice!!, 0.001)
        assertEquals(2.5, stop.trailAmount!!, 0.001)
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
        assertNull(stop.trailAmount)
    }

    @Test
    fun buildOrderPlan_customFractions_applyToStopLeg() {
        val rules = TouchTurnRuleConfig.DEFAULT.copy(
            trailingStopTriggerFractionOfEntryToTp = 0.25,
            trailingStopTrailFractionOfEntryToStop = 0.75
        )
        val plan = TouchTurnOrderPlanner.buildOrderPlan(
            "AAPL",
            longSetup(),
            maxDollars = 1000,
            rules = rules
        )!!
        val stop = plan.orders.first { it.role == TouchTurnOrderRole.STOP_LOSS }
        assertEquals(102.5, stop.trailTriggerPrice!!, 0.001)
        assertEquals(3.75, stop.trailAmount!!, 0.001)
    }

    @Test
    fun withFieldValue_roundTripsTrailingFractionFields() {
        var config = TouchTurnRuleConfig.DEFAULT
        config = TouchTurnRuleConfig.withFieldValue(
            config,
            "trailingStopTriggerFractionOfEntryToTp",
            "0.4"
        )!!
        config = TouchTurnRuleConfig.withFieldValue(
            config,
            "trailingStopTrailFractionOfEntryToStop",
            "0.6"
        )!!
        assertEquals(0.4, config.trailingStopTriggerFractionOfEntryToTp)
        assertEquals(0.6, config.trailingStopTrailFractionOfEntryToStop)
    }

    @Test
    fun validateFractions_defaultsAreValid() {
        assertNull(
            TouchTurnAdjustableStop.validateFractions(
                triggerFraction = 0.5,
                trailFraction = 0.5,
                takeProfitToStopLossRatio = 2.0
            )
        )
        assertNull(TouchTurnRuleConfig.DEFAULT.trailingStopFractionsValidationError())
    }

    @Test
    fun validateFractions_rejectsTriggerOutsideZeroToOne() {
        assertNotNull(
            TouchTurnAdjustableStop.validateFractions(
                triggerFraction = -0.1,
                trailFraction = 0.5,
                takeProfitToStopLossRatio = 2.0
            )
        )
        assertNotNull(
            TouchTurnAdjustableStop.validateFractions(
                triggerFraction = 1.1,
                trailFraction = 0.5,
                takeProfitToStopLossRatio = 2.0
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
    fun validateFractions_rejectsArmTooEarlyForTrailDistance() {
        val error = TouchTurnAdjustableStop.validateFractions(
            triggerFraction = 0.10,
            trailFraction = 1.5,
            takeProfitToStopLossRatio = 2.0
        )
        assertNotNull(error)

        assertNotNull(
            TouchTurnRuleConfig.withFieldValue(
                TouchTurnRuleConfig.DEFAULT,
                "trailingStopTrailFractionOfEntryToStop",
                "1.5"
            )
        )
        assertNull(
            TouchTurnRuleConfig.withFieldValue(
                TouchTurnRuleConfig.DEFAULT.copy(
                    trailingStopTrailFractionOfEntryToStop = 1.5
                ),
                "trailingStopTriggerFractionOfEntryToTp",
                "0.10"
            )
        )
        assertNotNull(
            TouchTurnRuleConfig.withFieldValue(
                TouchTurnRuleConfig.DEFAULT.copy(
                    trailingStopTrailFractionOfEntryToStop = 1.5
                ),
                "trailingStopTriggerFractionOfEntryToTp",
                "0.25"
            )
        )
    }

    @Test
    fun withFieldValue_rejectsTakeProfitToStopLossRatioThatBreaksTrailingPair() {
        val incompatible = TouchTurnRuleConfig.DEFAULT.copy(
            trailingStopTriggerFractionOfEntryToTp = 0.20,
            trailingStopTrailFractionOfEntryToStop = 1.5
        )
        assertNotNull(incompatible.trailingStopFractionsValidationError())

        assertNotNull(
            TouchTurnRuleConfig.withFieldValue(
                incompatible,
                "takeProfitToStopLossRatio",
                "4"
            )
        )
        assertNull(
            TouchTurnRuleConfig.withFieldValue(
                incompatible,
                "takeProfitToStopLossRatio",
                "2"
            )
        )
    }

    @Test
    fun computeAdjustableStop_returnsNullWhenFractionsIncompatible() {
        val incompatible = TouchTurnRuleConfig.DEFAULT.copy(
            trailingStopTriggerFractionOfEntryToTp = 0.10,
            trailingStopTrailFractionOfEntryToStop = 1.5
        )
        assertNull(incompatible.computeAdjustableStop(100.0, 95.0, 110.0))
        assertNull(
            TouchTurnAdjustableStop.compute(
                entry = 100.0,
                stopLoss = 95.0,
                takeProfit = 110.0,
                triggerFraction = 0.10,
                trailFraction = 1.5
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
            trailingStopTrailFractionOfEntryToStop = 0.75
        )
        val params = custom.computeAdjustableStop(100.0, 95.0, 110.0)
        assertNotNull(params)
        assertEquals(102.5, params.triggerPrice, 0.001)
        assertEquals(3.75, params.trailAmount, 0.001)
    }
}
