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
    fun build_aggregatesNetPnLAcrossFilteredDeployments() {
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
        assertEquals("67%", summary.formattedWinRate)
    }

    @Test
    fun build_aggregatesRealizedPnLForCurrentSessionDateAcrossFilteredDeployments() {
        val a = deployment(
            id = "a",
            sessions = listOf(
                closedSession(
                    id = "prior",
                    date = "2026-06-13",
                    pnl = 50.0,
                    stoppedAt = "2026-06-13T10:00:00",
                ),
                closedSession(
                    id = "today-a",
                    date = sessionDate,
                    pnl = -10.0,
                    stoppedAt = "2026-06-14T10:00:00",
                ),
            )
        )
        val b = deployment(
            id = "b",
            symbol = "AAPL",
            sessions = listOf(
                closedSession(
                    id = "today-b",
                    date = sessionDate,
                    pnl = 20.0,
                    stoppedAt = "2026-06-14T11:00:00",
                ),
            )
        )
        val summary = FilteredDeploymentsSummaryMapper.build(
            instances = listOf(a, b),
            sessionDate = sessionDate,
            brokerPositions = emptyList(),
            brokerOpenOrders = emptyList(),
        )
        assertNotNull(summary)
        // Prior-day +$50 excluded; today = -10 + 20
        assertEquals("+$10.00", summary.formattedSessionPnL)
        assertEquals(true, summary.isPositiveSessionPnL)
        assertEquals("+$60.00", summary.formattedNetPnL)
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
        assertEquals("$1,000.00", summary.formattedInvested)
    }

    @Test
    fun build_aggregatesLiveInvestedFromBrokerAvgCostWhenLiveFlat() {
        val running = deployment(
            id = "a",
            status = DeploymentStatus.RUNNING,
            live = ActiveExecution.flat(),
        )
        val positions = listOf(
            AccountPosition(
                account = "DU1",
                symbol = "TSLA",
                companyName = "Tesla",
                quantity = -80,
                avgPrice = 12.5,
                marketPrice = 12.0,
                priorClose = 13.0,
                totalUnrealizedPnL = 40.0,
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
        // abs(80) * 12.5 = 1,000 — emulator/IB Touch Turn leave live FLAT
        assertEquals("$1,000.00", summary.formattedInvested)
    }

    @Test
    fun build_aggregatesLiveInvestedFromSessionEntryFills() {
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
            sessions = listOf(
                StrategySession(
                    id = "live",
                    date = sessionDate,
                    startedAt = "${sessionDate}T09:30:00",
                    stoppedAt = "",
                    pnl = 0.0,
                    trades = 0,
                    maxAtRisk = 1000,
                    status = SessionStatus.IN_PROGRESS,
                    positionOpened = true,
                    sessionTrades = listOf(
                        daytrader.domain.SessionTrade(
                            execId = "e1",
                            orderId = 1,
                            permId = 1L,
                            parentOrderId = 0,
                            side = "BUY",
                            quantity = 40,
                            price = 10.0,
                            time = "${sessionDate}T09:35:00",
                            currency = "USD",
                            commission = 0.35,
                            realizedPnL = 0.0,
                        ),
                        daytrader.domain.SessionTrade(
                            execId = "e2",
                            orderId = 1,
                            permId = 1L,
                            parentOrderId = 0,
                            side = "BUY",
                            quantity = 60,
                            price = 12.0,
                            time = "${sessionDate}T09:36:00",
                            currency = "USD",
                            commission = 0.35,
                            realizedPnL = 0.0,
                        ),
                    ),
                ),
            ),
        )
        val positions = listOf(
            AccountPosition(
                account = "DU1",
                symbol = "TSLA",
                companyName = "Tesla",
                quantity = 100,
                avgPrice = 11.2,
                marketPrice = 12.0,
                priorClose = 11.0,
                totalUnrealizedPnL = 80.0,
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
        // 40*10 + 60*12 = 1,120 (session fills preferred over live qty*entry)
        assertEquals("$1,120.00", summary.formattedInvested)
    }

    @Test
    fun build_netPnLUsesIbNetRealizedWithoutDoubleCountingCommission() {
        val a = deployment(
            id = "a",
            sessions = listOf(
                closedSessionWithTrades(
                    id = "s1",
                    pnl = 499.30,
                    stoppedAt = "2026-06-14T10:00:00",
                    sessionTrades = listOf(
                        sessionTrade(commission = 0.35, realizedPnL = 0.0, parentOrderId = 0),
                        sessionTrade(commission = 0.35, realizedPnL = 499.30, parentOrderId = 1),
                    ),
                ),
            ),
        )
        val summary = FilteredDeploymentsSummaryMapper.build(
            instances = listOf(a),
            sessionDate = sessionDate,
            brokerPositions = emptyList(),
            brokerOpenOrders = emptyList(),
        )
        assertNotNull(summary)
        assertEquals("+$499.30", summary.formattedNetPnL)
    }

    private fun closedSessionWithTrades(
        id: String,
        pnl: Double,
        stoppedAt: String,
        sessionTrades: List<daytrader.domain.SessionTrade>,
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
        sessionTrades = sessionTrades,
    )

    private fun sessionTrade(commission: Double, realizedPnL: Double, parentOrderId: Int = 0) =
        daytrader.domain.SessionTrade(
            execId = "exec-$parentOrderId-$commission-$realizedPnL",
            orderId = if (parentOrderId == 0) 1 else 2,
            permId = if (parentOrderId == 0) 1L else 2L,
            parentOrderId = parentOrderId,
            side = "BUY",
            quantity = 10,
            price = 100.0,
            time = "${sessionDate}T10:00:00",
            currency = "USD",
            commission = commission,
            realizedPnL = realizedPnL,
        )

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
        date: String = sessionDate,
    ) = StrategySession(
        id = id,
        date = date,
        startedAt = "${date}T09:30:00",
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
