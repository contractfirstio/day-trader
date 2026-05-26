package daytrader.presentation.strategies

import daytrader.domain.RthMarketSessions
import daytrader.domain.SessionStatus
import daytrader.domain.StrategySession
import daytrader.domain.StrategyType
import daytrader.domain.TouchTurnRunContext
import daytrader.domain.TouchTurnRunMarketInputs
import daytrader.domain.TouchTurnRunRecord
import daytrader.domain.TouchTurnSessionDecision
import daytrader.domain.TouchTurnSessionOutcome
import daytrader.domain.TouchTurnSessionStartedBy
import daytrader.domain.TouchTurnSessionStopTrigger
import daytrader.domain.TouchTurnStopEvent
import daytrader.domain.TouchTurnMilestoneTimestamps
import daytrader.domain.defaultStrategyDeployment
import daytrader.gateway.BrokerId
import daytrader.presentation.positions.SortDirection
import kotlin.test.Test
import kotlin.test.assertEquals

class SessionHistoryUiMapperTest {
    @Test
    fun build_filtersRowsByMarketZone() {
        val usSession = closedSession("us", RthMarketSessions.US.zoneId)
        val hkSession = closedSession("hk", RthMarketSessions.HK.zoneId)
        val deployment = defaultStrategyDeployment(
            strategyType = StrategyType.TOUCH_AND_TURN_SCALPER,
            symbol = "SPY",
            maxDollars = 500
        ).copy(
            marketZoneId = RthMarketSessions.US.zoneId,
            sessionHistory = listOf(usSession, hkSession)
        )

        val filtered = SessionHistoryUiMapper.build(
            instance = deployment,
            sessionDate = "2026-05-22",
            sortColumn = SessionHistorySortColumn.TIME,
            sortDirection = SortDirection.DESCENDING,
            marketZoneFilter = RthMarketSessions.HK.zoneId,
            marketFilterLabel = "HK"
        )

        assertEquals(1, filtered.rows.size)
        val row = filtered.rows.single()
        assertEquals("hk", row.id)
        assertEquals("HK", filtered.marketFilterLabel)
    }

    private fun closedSession(id: String, marketZoneId: String): StrategySession =
        StrategySession(
            id = id,
            date = "2026-05-21",
            startedAt = "2026-05-21T09:31:00",
            stoppedAt = "2026-05-21T16:00:00",
            pnl = 1.0,
            trades = 1,
            maxAtRisk = 500,
            status = SessionStatus.CLOSED,
            touchTurnRunRecord = TouchTurnRunRecord(
                runContext = TouchTurnRunContext(
                    maxDollars = 500,
                    startedBy = TouchTurnSessionStartedBy.MANUAL,
                    brokerId = BrokerId.EMULATOR
                ),
                marketInputs = TouchTurnRunMarketInputs(marketZoneId = marketZoneId),
                decision = TouchTurnSessionDecision(outcome = TouchTurnSessionOutcome.NO_TRADE_NOT_LIQUIDITY),
                stopEvent = TouchTurnStopEvent(stopTrigger = TouchTurnSessionStopTrigger.MANUAL),
                milestones = TouchTurnMilestoneTimestamps()
            )
        )
}
