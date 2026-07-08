package daytrader.domain

import daytrader.domain.requiresDailyHistoricalBootstrap
import daytrader.gateway.BrokerKind
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SimulatedBrokerTouchTurnRulesTest {
    @Test
    fun defaultForBrokerKind_newConfigurableRulesDisabledForAllModes() {
        BrokerKind.entries.forEach { kind ->
            val rules = TouchTurnRuleConfig.defaultForBrokerKind(kind)
            assertFalse(rules.enables.openDeadline, "openDeadline should be off for $kind")
        }
    }

    @Test
    fun defaultForBrokerKind_usesZeroOffsetForEmulatorAndReplay() {
        assertEquals(0.0, TouchTurnRuleConfig.defaultForBrokerKind(BrokerKind.EMULATOR).entryInwardOffsetRatioOfRange)
        assertEquals(0.0, TouchTurnRuleConfig.defaultForBrokerKind(BrokerKind.REPLAY).entryInwardOffsetRatioOfRange)
    }

    @Test
    fun defaultForBrokerKind_usesDailyAtrLiquidityForIbAndHybrid() {
        listOf(BrokerKind.INTERACTIVE_BROKERS, BrokerKind.EMULATOR_LIVE_IB_MARKET_DATA).forEach { kind ->
            val rules = TouchTurnRuleConfig.defaultForBrokerKind(kind)
            assertTrue(rules.enables.liquidityRangeDailyAtr, "daily ATR liquidity on for $kind")
        }
    }

    @Test
    fun defaultForBrokerKind_disablesDailyAtrLiquidityForEmulatorAndReplay() {
        listOf(BrokerKind.EMULATOR, BrokerKind.REPLAY).forEach { kind ->
            val rules = TouchTurnRuleConfig.defaultForBrokerKind(kind)
            assertFalse(rules.enables.liquidityRangeDailyAtr, "daily ATR liquidity off for $kind")
        }
    }

    @Test
    fun ruleEnables_dailyBootstrap_whenDailyAtrLiquidityOn() {
        val base = TouchTurnRuleEnables.DEFAULT.copy(liquidityRangeDailyAtr = false)
        assertFalse(base.requiresDailyHistoricalBootstrap())
        assertTrue(base.copy(liquidityRangeDailyAtr = true).requiresDailyHistoricalBootstrap())
    }

    @Test
    fun withLiquidityGatesForBrokerKind_patchesDeploymentSessionAndHistory() {
        val rules = TouchTurnRuleConfig.DEFAULT.copy(
            enables = TouchTurnRuleEnables.DEFAULT.copy(
                liquidityRangeDailyAtr = false
            )
        )
        val deployment = defaultStrategyDeployment(
            strategyType = StrategyType.TOUCH_AND_TURN_SCALPER,
            symbol = "AAPL",
            maxDollars = 500
        ).copy(
            touchTurnRules = rules,
            touchTurnSession = TouchTurnSessionContext(
                sessionDate = "2026-05-22",
                status = TouchTurnCandleStatus.LOADING,
                rules = rules
            )
        )

        val patchedIb = deployment.withLiquidityGatesForBrokerKind(BrokerKind.INTERACTIVE_BROKERS)
        assertTrue(patchedIb.touchTurnRules.enables.liquidityRangeDailyAtr)
        assertTrue(patchedIb.touchTurnSession?.rules?.enables?.liquidityRangeDailyAtr ?: false)

        val patchedEmulator = deployment.withLiquidityGatesForBrokerKind(BrokerKind.EMULATOR)
        assertFalse(patchedEmulator.touchTurnRules.enables.liquidityRangeDailyAtr)
    }

    @Test
    fun withEntryInwardOffsetForBrokerKind_patchesDeploymentSessionAndHistory() {
        val rules = TouchTurnRuleConfig.DEFAULT.copy(entryInwardOffsetRatioOfRange = 0.15)
        val deployment = defaultStrategyDeployment(
            strategyType = StrategyType.TOUCH_AND_TURN_SCALPER,
            symbol = "AAPL",
            maxDollars = 500
        ).copy(
            touchTurnRules = rules,
            touchTurnSession = TouchTurnSessionContext(
                sessionDate = "2026-05-22",
                status = TouchTurnCandleStatus.LOADING,
                rules = rules
            )
        )

        val patchedEmulator = deployment.withEntryInwardOffsetForBrokerKind(BrokerKind.EMULATOR)
        assertEquals(0.0, patchedEmulator.touchTurnRules.entryInwardOffsetRatioOfRange)
        assertEquals(0.0, patchedEmulator.touchTurnSession?.rules?.entryInwardOffsetRatioOfRange)

        val patchedHybrid = deployment.withEntryInwardOffsetForBrokerKind(BrokerKind.EMULATOR_LIVE_IB_MARKET_DATA)
        assertEquals(0.02, patchedHybrid.touchTurnRules.entryInwardOffsetRatioOfRange)
        assertEquals(0.02, patchedHybrid.touchTurnSession?.rules?.entryInwardOffsetRatioOfRange)
    }

    @Test
    fun withNewConfigurableTouchTurnRulesDisabled_migratesLegacyOpenDeadline() {
        val legacyRules = TouchTurnRuleConfig.DEFAULT.copy(
            enables = TouchTurnRuleEnables.DEFAULT.copy(openDeadline = true)
        )
        val deployment = defaultStrategyDeployment(
            strategyType = StrategyType.TOUCH_AND_TURN_SCALPER,
            symbol = "AAPL",
            maxDollars = 500,
            brokerKind = BrokerKind.INTERACTIVE_BROKERS
        ).copy(
            touchTurnRules = legacyRules,
            touchTurnSession = TouchTurnSessionContext(
                sessionDate = "2026-05-22",
                status = TouchTurnCandleStatus.LOADING,
                rules = legacyRules
            )
        )

        val patched = deployment.withNewConfigurableTouchTurnRulesDisabled()

        assertFalse(patched.touchTurnRules.enables.openDeadline)
        assertFalse(patched.touchTurnSession?.rules?.enables?.openDeadline ?: true)
    }

    @Test
    fun fieldsForCategoryDisplay_executionFieldsAreMostlyDefaultable() {
        val executionFields = TouchTurnRuleConfig.fieldsForCategoryDisplay(TouchTurnRuleCategory.EXECUTION)
        assertTrue(executionFields.isNotEmpty())
        assertTrue(executionFields.filter { it.key != "entryInwardOffsetRatioOfRange" }.all { it.defaultable })
    }

    @Test
    fun fieldGroupsForCategory_splitsExecutionIntoEntryAndTakeProfit() {
        val groups = TouchTurnRuleConfig.fieldGroupsForCategory(
            category = TouchTurnRuleCategory.EXECUTION,
            invertTradeSide = false,
            toggleValues = emptyMap()
        )
        assertEquals(2, groups.size)
        assertEquals("Reversal / default entry", groups[0].label)
        assertEquals("Take profit & risk", groups[1].label)
        assertEquals("entryInwardOffsetRatioOfRange", groups[0].fields.single().key)
        assertEquals(3, groups[1].fields.size)
    }

    @Test
    fun fieldGroupsForCategory_splitsTriggersIntoLiquiditySubmissionAndTiming() {
        val groups = TouchTurnRuleConfig.fieldGroupsForCategory(
            category = TouchTurnRuleCategory.TRIGGERS,
            invertTradeSide = false,
            toggleValues = mapOf("liquidityRangeDailyAtr" to true)
        )
        assertEquals(3, groups.size)
        assertEquals("15m bar range threshold", groups[0].label)
        assertEquals("Submission gates", groups[1].label)
        assertEquals("15m bar close timing", groups[2].label)
        val withClosePosition = TouchTurnRuleConfig.fieldGroupsForCategory(
            category = TouchTurnRuleCategory.TRIGGERS,
            invertTradeSide = false,
            toggleValues = mapOf(
                "liquidityRangeDailyAtr" to true,
                "closePositionGate" to true
            )
        )
        assertEquals(3, withClosePosition.size)
        assertFalse(withClosePosition.any { it.label == "Close position (cp)" })
        assertEquals("minGrossProfit", groups[1].fields.single().key)
        assertEquals("closedBarRefetchSettleMs", groups[2].fields.single().key)
    }

    @Test
    fun fieldGroupsForCategory_executionOrdersReversalBeforeInvert() {
        val reversalGroups = TouchTurnRuleConfig.fieldGroupsForCategory(
            category = TouchTurnRuleCategory.EXECUTION,
            invertTradeSide = false,
            toggleValues = mapOf("invertTradeSide" to false)
        )
        assertEquals(2, reversalGroups.size)
        assertEquals("entryInwardOffsetRatioOfRange", reversalGroups.first().fields.single().key)

        val invertGroups = TouchTurnRuleConfig.fieldGroupsForCategory(
            category = TouchTurnRuleCategory.EXECUTION,
            invertTradeSide = true,
            toggleValues = mapOf("invertTradeSide" to true)
        )
        assertEquals(3, invertGroups.size)
        assertEquals("reversal_entry", invertGroups[0].testTagSuffix)
        assertEquals("invert_entry", invertGroups[1].testTagSuffix)
        assertEquals("take_profit_and_risk", invertGroups[2].testTagSuffix)
    }

    @Test
    fun fieldGroupsForCategory_hidesLiquidityThresholdWhenToggleOff() {
        val groups = TouchTurnRuleConfig.fieldGroupsForCategory(
            category = TouchTurnRuleCategory.TRIGGERS,
            invertTradeSide = false,
            toggleValues = mapOf("liquidityRangeDailyAtr" to false)
        )
        assertEquals(2, groups.size)
        assertFalse(groups.any { it.label == "15m bar range threshold" })

        val enabled = TouchTurnRuleConfig.fieldGroupsForCategory(
            category = TouchTurnRuleCategory.TRIGGERS,
            invertTradeSide = false,
            toggleValues = mapOf("liquidityRangeDailyAtr" to true)
        )
        assertTrue(enabled.any { it.label == "15m bar range threshold" })
    }

    @Test
    fun fieldsForCategoryDisplay_showsOutwardOffsetUnderExecutionWhenInvertEnabled() {
        val reversalExecution = TouchTurnRuleConfig.fieldsForCategoryDisplay(
            TouchTurnRuleCategory.EXECUTION,
            invertTradeSide = false
        )
        assertFalse(reversalExecution.any { it.key == "entryOutwardOffsetRatioOfRange" })
        assertTrue(reversalExecution.any { it.key == "entryInwardOffsetRatioOfRange" })

        val invertExecution = TouchTurnRuleConfig.fieldsForCategoryDisplay(
            TouchTurnRuleCategory.EXECUTION,
            invertTradeSide = true,
            toggleValues = mapOf("invertTradeSide" to true)
        )
        assertTrue(invertExecution.any { it.key == "entryOutwardOffsetRatioOfRange" })

        val triggerFields = TouchTurnRuleConfig.fieldsForCategoryDisplay(
            TouchTurnRuleCategory.TRIGGERS,
            invertTradeSide = true
        )
        assertFalse(triggerFields.any { it.key == "entryOutwardOffsetRatioOfRange" })
        assertFalse(triggerFields.any { it.key == "entryInwardOffsetRatioOfRange" })
    }

    @Test
    fun togglesForCategory_groupsTriggerAndExecutionToggles() {
        val triggerToggles = TouchTurnRuleConfig.togglesForCategory(TouchTurnRuleCategory.TRIGGERS)
        assertEquals(
            listOf(
                "liquidityRangeDailyAtr",
                "fiveMinuteConfirmation",
                "closePositionGate"
            ),
            triggerToggles.map { it.key }
        )
        val executionToggles = TouchTurnRuleConfig.togglesForCategory(TouchTurnRuleCategory.EXECUTION)
        assertEquals(listOf("invertTradeSide"), executionToggles.map { it.key })
    }

    fun withFieldValue_acceptsOptionalClosePositionThresholds() {
        val cleared = TouchTurnRuleConfig.withFieldValue(
            TouchTurnRuleConfig.DEFAULT.copy(greenSkipClosePositionBelow = 0.60),
            "greenSkipClosePositionBelow",
            ""
        )
        assertNull(cleared?.greenSkipClosePositionBelow)

        val updated = TouchTurnRuleConfig.withFieldValue(
            TouchTurnRuleConfig.DEFAULT,
            "redSkipClosePositionAbove",
            "0.50"
        )
        assertEquals(0.50, updated?.redSkipClosePositionAbove)

        assertNull(
            TouchTurnRuleConfig.withFieldValue(
                TouchTurnRuleConfig.DEFAULT,
                "greenSkipClosePositionBelow",
                "1.5"
            )
        )
    }

    @Test
    fun withFieldValue_acceptsClosedBarRefetchSettleMs() {
        val updated = TouchTurnRuleConfig.withFieldValue(
            TouchTurnRuleConfig.DEFAULT,
            "closedBarRefetchSettleMs",
            "5000"
        )
        assertEquals(5_000L, updated?.closedBarRefetchSettleMs)
    }

    @Test
    fun visibleFieldDefinitions_respectsToggleVisibility() {
        val reversalFields = TouchTurnRuleConfig.visibleFieldDefinitions(
            invertTradeSide = false,
            toggleValues = mapOf(
                "liquidityRangeDailyAtr" to false,
                "adjustableTrailingStop" to false,
                "openDeadline" to false
            )
        )
        assertFalse(reversalFields.any { it.key == "entryOutwardOffsetRatioOfRange" })
        assertFalse(reversalFields.any { it.key == "atrLiquidityRatio" })
        assertFalse(reversalFields.any { it.key == "trailingStopTriggerFractionOfEntryToTp" })
        assertFalse(reversalFields.any { it.key == "stopAfterOpenMinutes" })
    }
}
