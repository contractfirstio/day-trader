package daytrader.domain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TouchTurnTrailingActivationTest {

    @Test
    fun defaults_preserveImmediatePriceTriggeredPlacement() {
        val rules = TouchTurnRuleConfig.DEFAULT
        assertEquals(0, rules.trailingActivateAfterMinutes)
        assertEquals(TouchTurnTrailingActivateClockBase.SESSION_OPEN, rules.trailingActivateClockBase)
        assertTrue(rules.trailingRequirePriceTrigger)
        assertTrue(TouchTurnTrailingActivation.attachAdjustableAtPlacement(rules))
    }

    @Test
    fun attachAdjustableAtPlacement_falseWhenMinutesDelayed() {
        val rules = TouchTurnRuleConfig.DEFAULT.copy(trailingActivateAfterMinutes = 80)
        assertFalse(TouchTurnTrailingActivation.attachAdjustableAtPlacement(rules))
    }

    @Test
    fun attachAdjustableAtPlacement_falseWhenPriceTriggerNotRequired() {
        val rules = TouchTurnRuleConfig.DEFAULT.copy(trailingRequirePriceTrigger = false)
        assertFalse(TouchTurnTrailingActivation.attachAdjustableAtPlacement(rules))
    }

    @Test
    fun activationEpochMs_nullWhenNoDelay() {
        assertNull(TouchTurnTrailingActivation.activationEpochMs(TouchTurnRuleConfig.DEFAULT, 1_000L))
    }

    @Test
    fun activationEpochMs_addsMinutesToClockBase() {
        val rules = TouchTurnRuleConfig.DEFAULT.copy(trailingActivateAfterMinutes = 80)
        val open = 1_700_000_000_000L
        assertEquals(open + 80 * 60_000L, TouchTurnTrailingActivation.activationEpochMs(rules, open))
    }

    @Test
    fun mayArmTrailing_timeAndPriceGates() {
        val delayed = TouchTurnRuleConfig.DEFAULT.copy(trailingActivateAfterMinutes = 80)
        val open = 1_000L
        val activate = open + 80 * 60_000L
        assertFalse(
            TouchTurnTrailingActivation.mayArmTrailing(
                rules = delayed,
                nowEpochMs = activate - 1,
                activationEpochMs = activate,
                priceTriggerCrossed = true
            )
        )
        assertFalse(
            TouchTurnTrailingActivation.mayArmTrailing(
                rules = delayed,
                nowEpochMs = activate,
                activationEpochMs = activate,
                priceTriggerCrossed = false
            )
        )
        assertTrue(
            TouchTurnTrailingActivation.mayArmTrailing(
                rules = delayed,
                nowEpochMs = activate,
                activationEpochMs = activate,
                priceTriggerCrossed = true
            )
        )
    }

    @Test
    fun mayArmTrailing_timeOnlyWhenPriceTriggerNotRequired() {
        val rules = TouchTurnRuleConfig.DEFAULT.copy(
            trailingActivateAfterMinutes = 80,
            trailingRequirePriceTrigger = false
        )
        val activate = 80 * 60_000L
        assertTrue(
            TouchTurnTrailingActivation.mayArmTrailing(
                rules = rules,
                nowEpochMs = activate,
                activationEpochMs = activate,
                priceTriggerCrossed = false
            )
        )
    }

    @Test
    fun withFieldValue_roundTripsActivationFieldsIncludingZeroMinutes() {
        var config = TouchTurnRuleConfig.DEFAULT
        config = TouchTurnRuleConfig.withFieldValue(config, "trailingActivateAfterMinutes", "0")!!
        assertEquals(0, config.trailingActivateAfterMinutes)
        config = TouchTurnRuleConfig.withFieldValue(config, "trailingActivateAfterMinutes", "80")!!
        assertEquals(80, config.trailingActivateAfterMinutes)
        config = TouchTurnRuleConfig.withFieldValue(config, "trailingActivateClockBase", "FILL")!!
        assertEquals(TouchTurnTrailingActivateClockBase.FILL, config.trailingActivateClockBase)
        config = TouchTurnRuleConfig.withFieldValue(config, "trailingRequirePriceTrigger", "false")!!
        assertFalse(config.trailingRequirePriceTrigger)
    }

    @Test
    fun visibleFields_hidesTrailArmToTpWhenPriceTriggerNotRequired() {
        val toggleOn = mapOf("adjustableTrailingStop" to true)
        val visibleWithPrice = TouchTurnRuleConfig.visibleFieldDefinitions(
            invertTradeSide = false,
            toggleValues = toggleOn,
            fieldValues = mapOf("trailingRequirePriceTrigger" to "true")
        )
        assertTrue(visibleWithPrice.any { it.key == "trailingStopTriggerFractionOfEntryToTp" })
        assertTrue(visibleWithPrice.any { it.key == "trailingStopArmFractionOfEntryToStop" })

        val visibleTimeOnly = TouchTurnRuleConfig.visibleFieldDefinitions(
            invertTradeSide = false,
            toggleValues = toggleOn,
            fieldValues = mapOf("trailingRequirePriceTrigger" to "false")
        )
        assertFalse(visibleTimeOnly.any { it.key == "trailingStopTriggerFractionOfEntryToTp" })
        assertTrue(visibleTimeOnly.any { it.key == "trailingStopArmFractionOfEntryToStop" })
    }

    @Test
    fun fieldGroups_postEntry_putsArmLevelsAfterActivation() {
        val groups = TouchTurnRuleConfig.fieldGroupsForCategory(
            category = TouchTurnRuleCategory.POST_ENTRY,
            invertTradeSide = false,
            toggleValues = mapOf("adjustableTrailingStop" to true),
            fieldValues = mapOf("trailingRequirePriceTrigger" to "true")
        )
        assertEquals(
            listOf("trailing_activation", "trailing_arm_levels"),
            groups.map { it.testTagSuffix }
        )
        assertEquals(
            listOf(
                "trailingActivateAfterMinutes",
                "trailingActivateClockBase",
                "trailingRequirePriceTrigger"
            ).sorted(),
            groups[0].fields.map { it.key }.sorted()
        )
        assertEquals(
            listOf(
                "trailingStopArmFractionOfEntryToStop",
                "trailingStopTriggerFractionOfEntryToTp"
            ).sorted(),
            groups[1].fields.map { it.key }.sorted()
        )
    }

    @Test
    fun buildOrderPlan_deferredTrail_keepsTrailPricesButDisablesAttachAtPlacement() {
        val rules = TouchTurnRuleConfig.DEFAULT.copy(trailingActivateAfterMinutes = 80)
        val setup = TouchTurnBracketSetup(
            range = 10.0,
            rangeThreshold = 2.0,
            isLiquidityCandle = true,
            candleColor = FirstCandleColor.GREEN,
            side = TouchTurnTradeSide.LONG,
            entry = 100.0,
            stopLoss = 95.0,
            takeProfit = 110.0
        )
        val plan = TouchTurnOrderPlanner.buildOrderPlan("AAPL", setup, maxDollars = 1000, rules = rules)!!
        val stop = plan.orders.first { it.role == TouchTurnOrderRole.STOP_LOSS }
        assertEquals(105.0, stop.trailTriggerPrice!!, 0.001)
        assertEquals(100.0, stop.trailArmStopPrice!!, 0.001)
        assertFalse(stop.attachAdjustableAtPlacement)
        assertEquals(80, stop.trailActivateAfterMinutes)
    }

    @Test
    fun withTrailingActivationSchedule_stampsSessionOpenEpoch() {
        val rules = TouchTurnRuleConfig.DEFAULT.copy(trailingActivateAfterMinutes = 80)
        val setup = TouchTurnBracketSetup(
            range = 10.0,
            rangeThreshold = 2.0,
            isLiquidityCandle = true,
            candleColor = FirstCandleColor.GREEN,
            side = TouchTurnTradeSide.LONG,
            entry = 100.0,
            stopLoss = 95.0,
            takeProfit = 110.0
        )
        val plan = TouchTurnOrderPlanner.buildOrderPlan("AAPL", setup, maxDollars = 1000, rules = rules)!!
        val open = 1_700_000_000_000L
        val scheduled = plan.withTrailingActivationSchedule(rules, sessionOpenEpochMs = open)
        val stop = scheduled.orders.first { it.role == TouchTurnOrderRole.STOP_LOSS }
        assertEquals(open + 80 * 60_000L, stop.trailActivateAfterEpochMs)
        assertFalse(stop.attachAdjustableAtPlacement)
    }

    @Test
    fun clockBaseEpochMs_resolvesOpenOrFill() {
        val openRules = TouchTurnRuleConfig.DEFAULT
        assertEquals(
            10L,
            TouchTurnTrailingActivation.clockBaseEpochMs(openRules, sessionOpenEpochMs = 10L, entryFillEpochMs = 20L)
        )
        val fillRules = openRules.copy(trailingActivateClockBase = TouchTurnTrailingActivateClockBase.FILL)
        assertEquals(
            20L,
            TouchTurnTrailingActivation.clockBaseEpochMs(fillRules, sessionOpenEpochMs = 10L, entryFillEpochMs = 20L)
        )
        assertNull(
            TouchTurnTrailingActivation.clockBaseEpochMs(fillRules, sessionOpenEpochMs = 10L, entryFillEpochMs = null)
        )
    }
}
