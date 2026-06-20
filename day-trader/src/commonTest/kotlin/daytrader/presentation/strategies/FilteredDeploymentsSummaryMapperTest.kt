package daytrader.presentation.strategies

import daytrader.domain.ActiveExecution
import daytrader.domain.DeploymentStatus
import daytrader.domain.ExecutionState
import daytrader.domain.SessionStatus
import daytrader.domain.StrategyDeployment
import daytrader.domain.StrategySession
import daytrader.domain.StrategyType
import daytrader.domain.TradeSide
import daytrader.gateway.AccountPosition
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class FilteredDeploymentsSummaryMapperTest {
    private val sessionDate = "2026-06-14"

    @Test
    fun build_nullWhenEmpty() {
        assertNull(
            FilteredDeploymentsSummaryMapper.build(
                instances = emptyList(),
                sessionDate = sessionDate,
                brokerPositions = emptyList(),
                brokerOpenOrders = emptyList(),
            )
        )
    }

    @Test
    fun build_winRateIgnoresNoTradeSessions() {
        val a = deployment(
            id = "a",
            sessions = listOf(
                closedSession(id = "s1", pnl = 50.0, stoppedAt = "2026-06-13T10:00:00"),
                closedSession(id = "s2", pnl = -10.0, stoppedAt = "2026-06-14T09:00:00"),
                noTradeSession(id = "s0", stoppedAt = "2026-06-14T08:00:00"),
            )
        )
        val summary = FilteredDeploymentsSummaryMapper.build(
            instances = listOf(a),
            sessionDate = sessionDate,
            brokerPositions = emptyList(),
            brokerOpenOrders = emptyList(),
        )
        assertNotNull(summary)
        assertEquals("50%", summary.formattedWinRate)
        assertEquals("33%", summary.formattedNoTradeRate)
    }

    @Test
    fun build_aggregatesNetPnLAndLastSessionAcrossFilteredDeployments() {
        val a = deployment(
            id = "a",
            sessions = listOf(
                closedSession(id = "s1", pnl = 50.0, stoppedAt = "2026-06-13T10:00:00"),
                closedSession(id = "s2", pnl = -10.0, stoppedAt = "2026-06-14T10:00:00"),
            )
        )
        val b = deployment(
            id = "b",
            symbol = "AAPL",
            sessions = listOf(
                closedSession(id = "s3", pnl = 20.0, stoppedAt = "2026-06-14T11:00:00"),
            )
        )
        val summary = FilteredDeploymentsSummaryMapper.build(
            instances = listOf(a, b),
            sessionDate = sessionDate,
            brokerPositions = emptyList(),
            brokerOpenOrders = emptyList(),
        )
        assertNotNull(summary)
        assertEquals("+$60.00", summary.formattedNetPnL)
        assertEquals("+$10.00", summary.formattedLastSessionPnL)
        assertEquals("67%", summary.formattedWinRate)
    }

    @Test
    fun build_aggregatesLiveUnrealizedForOpenPositions() {
        val running = deployment(
            id = "a",
            status = DeploymentStatus.RUNNING,
            live = ActiveExecution(
                state = ExecutionState.FILLED,
                side = TradeSide.LONG,
                quantity = 10,
                entryPrice = 100.0,
                stopPrice = 95.0,
                targetPrice = 110.0,
            ),
        )
        val positions = listOf(
            AccountPosition(
                account = "DU1",
                symbol = "TSLA",
                companyName = "Tesla",
                quantity = 10,
                avgPrice = 100.0,
                marketPrice = 105.0,
                priorClose = 98.0,
                totalUnrealizedPnL = 50.0,
                currency = "USD",
            )
        )
        val summary = FilteredDeploymentsSummaryMapper.build(
            instances = listOf(running),
            sessionDate = sessionDate,
            brokerPositions = positions,
            brokerOpenOrders = emptyList(),
        )
        assertNotNull(summary)
        assertTrue(summary.showLiveBand)
        assertEquals(1, summary.openPositionCount)
        assertEquals("+$50.00", summary.formattedUnrealized)
    }

    private fun deployment(
        id: String = "d1",
        symbol: String = "TSLA",
        status: DeploymentStatus = DeploymentStatus.STOPPED,
        live: ActiveExecution = ActiveExecution.flat(),
        sessions: List<StrategySession> = emptyList(),
    ) = StrategyDeployment(
        id = id,
        strategyType = StrategyType.TOUCH_AND_TURN_SCALPER,
        status = status,
        symbol = symbol,
        maxDollars = 1000,
        live = live,
        sessionHistory = sessions,
    )

    private fun closedSession(
        id: String,
        pnl: Double,
        stoppedAt: String,
    ) = StrategySession(
        id = id,
        date = sessionDate,
        startedAt = "${sessionDate}T09:30:00",
        stoppedAt = stoppedAt,
        pnl = pnl,
        trades = 1,
        maxAtRisk = 1000,
        status = SessionStatus.CLOSED,
        positionOpened = true,
    )

    private fun noTradeSession(
        id: String,
        stoppedAt: String,
    ) = StrategySession(
        id = id,
        date = sessionDate,
        startedAt = "${sessionDate}T09:30:00",
        stoppedAt = stoppedAt,
        pnl = 0.0,
        trades = 0,
        maxAtRisk = 1000,
        status = SessionStatus.CLOSED,
        positionOpened = false,
    )
}
