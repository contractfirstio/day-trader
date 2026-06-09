package daytrader.domain

import daytrader.gateway.BrokerKind
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class SimulatedBrokerTouchTurnRulesTest {
    @Test
    fun defaultForBrokerKind_newConfigurableRulesDisabledForAllModes() {
        BrokerKind.entries.forEach { kind ->
            val rules = TouchTurnRuleConfig.defaultForBrokerKind(kind)
            assertFalse(rules.enables.notDoji, "notDoji should be off for $kind")
            assertFalse(rules.enables.openDeadline, "openDeadline should be off for $kind")
        }
    }

    @Test
    fun defaultForBrokerKind_usesZeroOffsetForEmulatorAndReplay() {
        assertEquals(0.0, TouchTurnRuleConfig.defaultForBrokerKind(BrokerKind.EMULATOR).entryInwardOffsetRatioOfRange)
        assertEquals(0.0, TouchTurnRuleConfig.defaultForBrokerKind(BrokerKind.REPLAY).entryInwardOffsetRatioOfRange)
    }

    @Test
    fun defaultForBrokerKind_usesLiveOffsetForIbAndHybrid() {
        assertEquals(
            TouchTurnDefaults.ENTRY_INWARD_OFFSET_RATIO_OF_RANGE,
            TouchTurnRuleConfig.defaultForBrokerKind(BrokerKind.INTERACTIVE_BROKERS)
                .entryInwardOffsetRatioOfRange
        )
        assertEquals(
            TouchTurnDefaults.ENTRY_INWARD_OFFSET_RATIO_OF_RANGE,
            TouchTurnRuleConfig.defaultForBrokerKind(BrokerKind.EMULATOR_LIVE_IB_MARKET_DATA)
                .entryInwardOffsetRatioOfRange
        )
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
    fun withNewConfigurableTouchTurnRulesDisabled_migratesLegacyNotDojiAndOpenDeadline() {
        val legacyRules = TouchTurnRuleConfig.DEFAULT.copy(
            enables = TouchTurnRuleEnables.DEFAULT.copy(notDoji = true, openDeadline = true)
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

        assertFalse(patched.touchTurnRules.enables.notDoji)
        assertFalse(patched.touchTurnRules.enables.openDeadline)
        assertFalse(patched.touchTurnSession?.rules?.enables?.notDoji ?: true)
        assertFalse(patched.touchTurnSession?.rules?.enables?.openDeadline ?: true)
    }
}
