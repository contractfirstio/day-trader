package daytrader.domain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

class StrategyConfigurationSnapshotTest {
    @Test
    fun fingerprint_isStableForSameConfiguration() {
        val first = snapshot(maxDollars = 500, invertTradeSide = false)
        val second = snapshot(maxDollars = 500, invertTradeSide = false)
        assertEquals(first.fingerprint(), second.fingerprint())
    }

    @Test
    fun fingerprint_changesWhenRulesChange() {
        val baseline = snapshot(maxDollars = 500, invertTradeSide = false)
        val inverted = snapshot(maxDollars = 500, invertTradeSide = true)
        val resized = snapshot(maxDollars = 1_000, invertTradeSide = false)
        assertNotEquals(baseline.fingerprint(), inverted.fingerprint())
        assertNotEquals(baseline.fingerprint(), resized.fingerprint())
    }

    @Test
    fun fingerprint_changesWhenTrailingActivationGatesChange() {
        val baseline = StrategyConfigurationSnapshot(
            strategyType = StrategyType.TOUCH_AND_TURN_SCALPER,
            maxDollars = 500,
            touchTurnRules = TouchTurnRuleConfig.DEFAULT
        )
        val delayed = baseline.copy(
            touchTurnRules = TouchTurnRuleConfig.DEFAULT.copy(trailingActivateAfterMinutes = 80)
        )
        assertNotEquals(baseline.fingerprint(), delayed.fingerprint())
    }

    @Test
    fun fingerprint_hasVersionPrefix() {
        val fingerprint = snapshot().fingerprint()
        assertEquals(true, fingerprint.startsWith(StrategyConfigurationSnapshot.FINGERPRINT_PREFIX))
    }

    @Test
    fun rollupsForConfiguration_excludesSessionsFromOtherConfigurations() {
        val currentRules = TouchTurnRuleConfig.DEFAULT.copy(takeProfitToStopLossRatio = 2.0)
        val oldRules = currentRules.copy(takeProfitToStopLossRatio = 3.0)
        val deployment = defaultStrategyDeployment(
            strategyType = StrategyType.TOUCH_AND_TURN_SCALPER,
            symbol = "MSFT",
            maxDollars = 500,
        ).copy(touchTurnRules = currentRules)
        val currentFingerprint = deployment.currentConfigurationFingerprint()
        val oldFingerprint = StrategyConfigurationSnapshot(
            strategyType = deployment.strategyType,
            maxDollars = 500,
            touchTurnRules = oldRules,
        ).fingerprint()
        val sessions = listOf(
            closedSession(
                id = "old-win",
                pnl = 20.0,
                fingerprint = oldFingerprint,
                positionOpened = true,
            ),
            closedSession(
                id = "old-loss",
                pnl = -10.0,
                fingerprint = oldFingerprint,
                positionOpened = true,
            ),
            closedSession(
                id = "current-no-trade",
                pnl = 0.0,
                fingerprint = currentFingerprint,
                positionOpened = false,
            ),
            closedSession(
                id = "current-win",
                pnl = 15.0,
                fingerprint = currentFingerprint,
                positionOpened = true,
            ),
        )
        val rollup = sessions.rollupsForConfiguration("2026-05-22", deployment)
        assertEquals(2, rollup.closedDays)
        assertEquals(1, rollup.winDays)
        assertEquals(0, rollup.lossDays)
        assertEquals(1, rollup.noTradeDays)
        assertEquals(15.0, rollup.totalPnl)
    }

    @Test
    fun onSessionStopped_persistsConfigurationFingerprint() {
        val deployment = defaultStrategyDeployment(
            strategyType = StrategyType.QUICK_FLIP_SCALPER,
            symbol = "SPY",
            maxDollars = 500,
        )
        val started = deployment.onSessionStarted("2026-05-22", startedAt = "2026-05-22T09:30:00")
        val stopped = started.onSessionStopped(
            stoppedAt = "2026-05-22T10:00:00",
            snapshot = SessionStopSnapshot(positionOpened = false, sessionPnL = 0.0),
        )
        val closed = stopped.sessionHistory.single()
        assertEquals(deployment.currentConfigurationFingerprint(), closed.configurationFingerprint)
    }

    private fun snapshot(
        maxDollars: Int = 500,
        invertTradeSide: Boolean = false,
    ): StrategyConfigurationSnapshot =
        StrategyConfigurationSnapshot(
            strategyType = StrategyType.TOUCH_AND_TURN_SCALPER,
            maxDollars = maxDollars,
            touchTurnRules = TouchTurnRuleConfig.DEFAULT.copy(invertTradeSide = invertTradeSide),
        )

    private fun closedSession(
        id: String,
        pnl: Double,
        fingerprint: String,
        positionOpened: Boolean,
    ): StrategySession = StrategySession(
        id = id,
        date = "2026-05-22",
        pnl = pnl,
        trades = if (positionOpened) 1 else 0,
        maxAtRisk = 500,
        status = SessionStatus.CLOSED,
        positionOpened = positionOpened,
        configurationFingerprint = fingerprint,
    )
}
