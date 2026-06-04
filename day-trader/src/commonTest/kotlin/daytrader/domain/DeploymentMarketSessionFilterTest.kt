package daytrader.domain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DeploymentMarketSessionFilterTest {
    @Test
    fun zonesMatch_treatsBerlinAsLondon() {
        assertTrue(
            DeploymentMarket.zonesMatch(
                RthMarketSessions.EUR.zoneId,
                "Europe/Berlin"
            )
        )
    }

    @Test
    fun sessionMatchesMarketFilter_usesFrozenRunRecordZone() {
        val deployment = defaultStrategyDeployment(
            strategyType = StrategyType.TOUCH_AND_TURN_SCALPER,
            symbol = "SPY",
            maxDollars = 500
        ).copy(marketZoneId = RthMarketSessions.US.zoneId)
        val session = StrategySession(
            id = "s1",
            date = "2026-05-22",
            pnl = 10.0,
            trades = 1,
            maxAtRisk = 500,
            status = SessionStatus.CLOSED,
            touchTurnRunRecord = TouchTurnRunRecord(
                runContext = TouchTurnRunContext(
                    maxDollars = 500,
                    startedBy = TouchTurnSessionStartedBy.MANUAL,
                    brokerId = daytrader.gateway.BrokerId.EMULATOR
                ),
                marketInputs = TouchTurnRunMarketInputs(marketZoneId = RthMarketSessions.HK.zoneId),
                decision = TouchTurnSessionDecision(outcome = TouchTurnSessionOutcome.NO_TRADE_NOT_LIQUIDITY),
                stopEvent = TouchTurnStopEvent(stopTrigger = TouchTurnSessionStopTrigger.MANUAL),
                milestones = TouchTurnMilestoneTimestamps()
            )
        )

        assertTrue(
            DeploymentMarket.sessionMatchesMarketFilter(session, deployment, RthMarketSessions.HK.zoneId)
        )
        assertFalse(
            DeploymentMarket.sessionMatchesMarketFilter(session, deployment, RthMarketSessions.US.zoneId)
        )
    }

    @Test
    fun sessionMatchesMarketFilter_nullFilterMatchesAll() {
        val deployment = defaultStrategyDeployment(
            strategyType = StrategyType.TOUCH_AND_TURN_SCALPER,
            symbol = "SPY",
            maxDollars = 500
        )
        val session = StrategySession(
            id = "s1",
            date = "2026-05-22",
            pnl = 0.0,
            trades = 0,
            maxAtRisk = 500,
            status = SessionStatus.CLOSED
        )
        assertTrue(DeploymentMarket.sessionMatchesMarketFilter(session, deployment, null))
    }

    @Test
    fun sessionDateIso_usesDeploymentMarketZone() {
        val deployment = defaultStrategyDeployment(
            strategyType = StrategyType.TOUCH_AND_TURN_SCALPER,
            symbol = "AAPL",
            maxDollars = 500
        ).copy(marketZoneId = RthMarketSessions.US.zoneId)
        val hkMorningUsPreviousEvening = java.time.ZonedDateTime.of(
            2026, 6, 3, 8, 0, 0, 0,
            java.time.ZoneId.of("Asia/Hong_Kong")
        ).toInstant().toEpochMilli()
        assertEquals("2026-06-02", DeploymentMarket.sessionDateIso(deployment, hkMorningUsPreviousEvening))
    }
}
