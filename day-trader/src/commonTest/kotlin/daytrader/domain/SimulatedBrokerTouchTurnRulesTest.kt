package daytrader.domain

import daytrader.domain.requiresDailyHistoricalBootstrap
import daytrader.gateway.BrokerKind
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
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
}
