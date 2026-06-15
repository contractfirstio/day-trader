package daytrader.data

import daytrader.domain.StrategyType
import daytrader.domain.TouchTurnRuleConfig
import daytrader.domain.TouchTurnRuleEnables
import daytrader.domain.defaultStrategyDeployment
import daytrader.gateway.BrokerKind
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DeploymentLoadNormalizerTest {
    @Test
    fun normalize_preservesOpenDeadlineWhenPersisted() {
        val rules = TouchTurnRuleConfig.DEFAULT.copy(
            enables = TouchTurnRuleEnables.DEFAULT.copy(openDeadline = true)
        )
        val deployment = defaultStrategyDeployment(
            strategyType = StrategyType.TOUCH_AND_TURN_SCALPER,
            symbol = "0700",
            maxDollars = 500
        ).copy(touchTurnRules = rules)

        val normalized = DeploymentLoadNormalizer.normalize(
            deployment = deployment,
            hadPersistedTouchTurnRules = true,
            brokerKind = BrokerKind.EMULATOR_LIVE_IB_MARKET_DATA
        )

        assertTrue(normalized.touchTurnRules.enables.openDeadline)
    }

    @Test
    fun normalize_preservesPersistedHybridRuleOverrides() {
        val customRules = TouchTurnRuleConfig.DEFAULT.copy(
            entryInwardOffsetRatioOfRange = 0.15,
            enables = TouchTurnRuleEnables.DEFAULT.copy(liquidityRangeDailyAtr = false)
        )
        val deployment = defaultStrategyDeployment(
            strategyType = StrategyType.TOUCH_AND_TURN_SCALPER,
            symbol = "0700",
            maxDollars = 500,
            brokerKind = BrokerKind.EMULATOR_LIVE_IB_MARKET_DATA
        ).copy(touchTurnRules = customRules)

        val normalized = DeploymentLoadNormalizer.normalize(
            deployment = deployment,
            hadPersistedTouchTurnRules = true,
            brokerKind = BrokerKind.EMULATOR_LIVE_IB_MARKET_DATA
        )

        assertEquals(0.15, normalized.touchTurnRules.entryInwardOffsetRatioOfRange)
        assertFalse(normalized.touchTurnRules.enables.liquidityRangeDailyAtr)
    }

    @Test
    fun normalize_seedsBrokerDefaultsWhenRulesWereNeverPersisted() {
        val deployment = defaultStrategyDeployment(
            strategyType = StrategyType.TOUCH_AND_TURN_SCALPER,
            symbol = "0700",
            maxDollars = 500
        )

        val normalized = DeploymentLoadNormalizer.normalize(
            deployment = deployment,
            hadPersistedTouchTurnRules = false,
            brokerKind = BrokerKind.EMULATOR_LIVE_IB_MARKET_DATA
        )

        assertEquals(0.02, normalized.touchTurnRules.entryInwardOffsetRatioOfRange)
        assertTrue(normalized.touchTurnRules.enables.liquidityRangeDailyAtr)
    }

    @Test
    fun normalize_replaySkipsBrokerPatchingEvenWithoutPersistedRules() {
        val deployment = defaultStrategyDeployment(
            strategyType = StrategyType.TOUCH_AND_TURN_SCALPER,
            symbol = "0700",
            maxDollars = 500
        ).copy(
            touchTurnRules = TouchTurnRuleConfig.DEFAULT.copy(
                entryInwardOffsetRatioOfRange = 0.15,
                enables = TouchTurnRuleEnables.DEFAULT.copy(liquidityRangeDailyAtr = false)
            )
        )

        val normalized = DeploymentLoadNormalizer.normalize(
            deployment = deployment,
            hadPersistedTouchTurnRules = false,
            brokerKind = BrokerKind.REPLAY
        )

        assertEquals(deployment, normalized)
    }
}
