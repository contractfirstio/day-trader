package daytrader.presentation.strategies

import daytrader.domain.DeploymentStatus
import daytrader.domain.SessionStatus
import daytrader.domain.StrategySession
import daytrader.domain.FirstCandleCloseStatus
import daytrader.domain.OhlcBar
import daytrader.domain.StrategyDeployment
import daytrader.domain.StrategyType
import daytrader.domain.TouchTurnCandleStatus
import daytrader.domain.TouchTurnCloseConfirmation
import daytrader.domain.TouchTurnDefaults
import daytrader.domain.TouchTurnLogic
import daytrader.domain.TouchTurnMilestoneTimestamps
import daytrader.domain.TouchTurnRunContext
import daytrader.domain.TouchTurnRunMarketInputs
import daytrader.domain.TouchTurnRunRecord
import daytrader.domain.TouchTurnSessionContext
import daytrader.domain.TouchTurnSessionDecision
import daytrader.domain.TouchTurnSessionOutcome
import daytrader.domain.TouchTurnSessionStartedBy
import daytrader.domain.TouchTurnSessionStopTrigger
import daytrader.domain.TouchTurnStopEvent
import daytrader.domain.withLiquidityEvaluatedIfClosed
import daytrader.domain.withOrdersPlacedForSession
import daytrader.gateway.BrokerId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class TouchTurnStatusBreadcrumbMapperTest {
    @Test
    fun nullSession_currentIsStartingSession() {
        val steps = TouchTurnStatusBreadcrumbMapper.steps(
            instance = deployment(touchTurnSession = null),
            hasOpenPosition = false
        )
        assertEquals("Starting session", steps[0].label)
        assertEquals(TouchTurnBreadcrumbStepState.CURRENT, steps[0].state)
        assertEquals("Closing session", steps[7].label)
        assertEquals(TouchTurnBreadcrumbStepState.UPCOMING, steps[7].state)
    }

    @Test
    fun loadingSession_currentIsData() {
        val session = TouchTurnSessionContext(
            sessionDate = "2026-05-22",
            status = TouchTurnCandleStatus.LOADING
        )
        val steps = TouchTurnStatusBreadcrumbMapper.steps(
            instance = deployment(session),
            hasOpenPosition = false
        )
        assertEquals(TouchTurnBreadcrumbStepState.COMPLETED, steps[0].state)
        assertEquals(TouchTurnBreadcrumbStepState.CURRENT, steps[1].state)
    }

    @Test
    fun failedSession_marksDataFailed() {
        val session = TouchTurnSessionContext(
            sessionDate = "2026-05-22",
            status = TouchTurnCandleStatus.FAILED,
            errorMessage = "ADR error"
        )
        val steps = TouchTurnStatusBreadcrumbMapper.steps(
            instance = deployment(session),
            hasOpenPosition = false
        )
        assertEquals(TouchTurnBreadcrumbStepState.COMPLETED, steps[0].state)
        assertEquals(TouchTurnBreadcrumbStepState.FAILED, steps[1].state)
    }

    @Test
    fun notLiquidity_skipsOrdersAndPosition_closingStillUpcoming() {
        val barTime = "20260522  09:30:00"
        val now = barEnd(barTime) + 1
        val session = readySession(
            candle = bar(barTime),
            rangeThreshold = 10.0,
            now = now
        )
        val steps = TouchTurnStatusBreadcrumbMapper.steps(
            instance = deployment(session),
            hasOpenPosition = false,
            nowEpochMillis = now
        )
        assertEquals(TouchTurnBreadcrumbStepState.COMPLETED, steps[3].state)
        assertEquals(TouchTurnBreadcrumbStepState.UPCOMING, steps[4].state)
        assertEquals(TouchTurnBreadcrumbStepState.SKIPPED, steps[5].state)
        assertEquals(TouchTurnBreadcrumbStepState.SKIPPED, steps[6].state)
        assertEquals(TouchTurnBreadcrumbStepState.UPCOMING, steps[7].state)
        assertTrue(steps.none { it.state == TouchTurnBreadcrumbStepState.CURRENT })
    }

    @Test
    fun completedSteps_showFormattedTimestamps() {
        val session = TouchTurnSessionContext(
            sessionDate = "2026-05-22",
            status = TouchTurnCandleStatus.LOADING,
            milestones = TouchTurnMilestoneTimestamps(
                startingSessionAt = "2026-05-22T09:30:05",
                dataReadyAt = "2026-05-22T09:30:12"
            )
        )
        val steps = TouchTurnStatusBreadcrumbMapper.steps(
            instance = deployment(session),
            hasOpenPosition = false
        )
        assertEquals("09:30", steps[0].timestamp)
        assertEquals("09:30", steps[1].timestamp)
        assertEquals(null, steps[2].timestamp)
    }

    @Test
    fun openPosition_currentIsPosition() {
        val barTime = "20260522  09:30:00"
        val now = barEnd(barTime) + 1
        val session = readySession(
            candle = bar(barTime),
            rangeThreshold = 0.01,
            now = now
        ).copy(ordersPlacedForSession = true)
        val steps = TouchTurnStatusBreadcrumbMapper.steps(
            instance = deployment(session),
            hasOpenPosition = true,
            nowEpochMillis = now
        )
        assertEquals(TouchTurnBreadcrumbStepState.CURRENT, steps[6].state)
        assertEquals(TouchTurnBreadcrumbStepState.UPCOMING, steps[7].state)
    }

    @Test
    fun openPosition_withMilestone_positionCompleted() {
        val barTime = "20260522  09:30:00"
        val now = barEnd(barTime) + 1
        val session = readySession(
            candle = bar(barTime),
            rangeThreshold = 0.01,
            now = now
        ).copy(
            ordersPlacedForSession = true,
            milestones = TouchTurnMilestoneTimestamps(positionOpenedAt = "2026-05-22T09:46:10")
        )
        val steps = TouchTurnStatusBreadcrumbMapper.steps(
            instance = deployment(session),
            hasOpenPosition = true,
            nowEpochMillis = now
        )
        assertEquals(TouchTurnBreadcrumbStepState.COMPLETED, steps[5].state)
        assertEquals(TouchTurnBreadcrumbStepState.COMPLETED, steps[6].state)
        assertEquals(TouchTurnBreadcrumbStepState.UPCOMING, steps[7].state)
    }

    @Test
    fun ordersPlaced_waitingForEntry_ordersCurrent() {
        val barTime = "20260522  09:30:00"
        val now = barEnd(barTime) + 1
        val session = readySession(
            candle = bar(barTime),
            rangeThreshold = 0.01,
            now = now
        ).copy(ordersPlacedForSession = true)
        val steps = TouchTurnStatusBreadcrumbMapper.steps(
            instance = deployment(session),
            hasOpenPosition = false,
            hasOpenOrders = true,
            nowEpochMillis = now
        )
        assertEquals(TouchTurnBreadcrumbStepState.COMPLETED, steps[4].state)
        assertEquals(TouchTurnBreadcrumbStepState.CURRENT, steps[5].state)
        assertEquals(TouchTurnBreadcrumbStepState.UPCOMING, steps[6].state)
    }

    @Test
    fun graph_entryNeverFilled_atDeadline_skipsPositionAndCloses() {
        val barTime = "20260522  09:30:00"
        val zone = "America/New_York"
        val open = TouchTurnLogic.marketOpenEpochMillis("2026-05-22", zone, barTime)!!
        val now = open + 90 * 60_000 + 1
        val session = readySession(
            candle = bar(barTime),
            rangeThreshold = 0.01,
            now = barEnd(barTime) + 1
        ).copy(
            ordersPlacedForSession = true,
            milestones = TouchTurnMilestoneTimestamps(
                startingSessionAt = "2026-05-22T09:30:05",
                dataReadyAt = "2026-05-22T09:30:12",
                barClosedAt = "2026-05-22T09:45:00",
                liquidityEvaluatedAt = "2026-05-22T09:45:01",
                closeConfirmedAt = "2026-05-22T09:45:02",
                ordersPlacedAt = "2026-05-22T09:45:03"
            )
        )
        val graph = TouchTurnStatusBreadcrumbMapper.graph(
            instance = deployment(session),
            hasOpenPosition = false,
            hasOpenOrders = false,
            nowEpochMillis = now
        )
        assertEquals(
            TouchTurnBreadcrumbStepState.SKIPPED,
            graph.nodes.first { it.id == TouchTurnPipelineNodeId.Position }.state
        )
        assertEquals(
            TouchTurnBreadcrumbStepState.COMPLETED,
            graph.nodes.first { it.id == TouchTurnPipelineNodeId.Orders }.state
        )
        assertEquals(
            TouchTurnBreadcrumbStepState.COMPLETED,
            graph.nodes.first { it.id == TouchTurnPipelineNodeId.Close }.state
        )
        assertTrue(TouchTurnPipelineNodeId.Orders in graph.activePath)
        assertTrue(TouchTurnPipelineNodeId.Close in graph.activePath)
        assertTrue(TouchTurnPipelineNodeId.Position !in graph.activePath)
        assertTrue(TouchTurnPipelineNodeId.NoTrade !in graph.activePath)
    }

    @Test
    fun stepsFromHistory_ordersPlacedWithoutFill_skipsPosition() {
        val milestones = TouchTurnMilestoneTimestamps(
            startingSessionAt = "2026-05-22T09:30:05",
            dataReadyAt = "2026-05-22T09:30:12",
            barClosedAt = "2026-05-22T09:45:00",
            liquidityEvaluatedAt = "2026-05-22T09:45:01",
            closeConfirmedAt = "2026-05-22T09:45:02",
            ordersPlacedAt = "2026-05-22T09:45:03",
            closingSessionAt = "2026-05-22T11:00:00"
        )
        val steps = TouchTurnStatusBreadcrumbMapper.stepsFromHistory(
            milestones = milestones,
            startedAt = "2026-05-22T09:30:05",
            stoppedAt = "2026-05-22T11:00:00",
            hadLiquidityCandle = true,
            ordersPlacedForCandle = true,
            positionOpened = false
        )
        assertEquals(TouchTurnBreadcrumbStepState.COMPLETED, steps[5].state)
        assertEquals(TouchTurnBreadcrumbStepState.SKIPPED, steps[6].state)
        assertEquals(TouchTurnBreadcrumbStepState.COMPLETED, steps[7].state)
    }

    private fun deployment(touchTurnSession: TouchTurnSessionContext?) = StrategyDeployment(
        id = "tt-1",
        strategyType = StrategyType.TOUCH_AND_TURN_SCALPER,
        status = DeploymentStatus.RUNNING,
        symbol = "AAPL",
        maxDollars = 500,
        touchTurnSession = touchTurnSession
    )

    private fun readySession(
        candle: OhlcBar,
        rangeThreshold: Double,
        now: Long
    ): TouchTurnSessionContext {
        val session = TouchTurnSessionContext(
            sessionDate = "2026-05-22",
            status = TouchTurnCandleStatus.READY,
            candle = candle,
            marketZoneId = "America/New_York",
            rangeThreshold = rangeThreshold,
            adr14 = rangeThreshold / 0.25
        )
        assertEquals(FirstCandleCloseStatus.CLOSED, session.candleCloseStatus(now))
        return session
    }

    private fun bar(time: String) = OhlcBar(
        open = 105.0,
        high = 106.0,
        low = 99.0,
        close = 104.0,
        time = time
    )

    private fun barEnd(time: String): Long =
        TouchTurnLogic.barEndEpochMillis(time, "America/New_York")!!

    @Test
    fun pipelineForLastClosedSession_usesMostRecentClosedRun() {
        val milestones = TouchTurnMilestoneTimestamps(
            startingSessionAt = "2026-05-22T09:30:00",
            dataReadyAt = "2026-05-22T09:30:10",
            closingSessionAt = "2026-05-22T11:00:00"
        )
        val instance = deployment(
            touchTurnSession = null
        ).copy(
            status = DeploymentStatus.STOPPED,
            sessionHistory = listOf(
                StrategySession(
                    id = "old",
                    date = "2026-05-21",
                    startedAt = "2026-05-21T09:30:00",
                    stoppedAt = "2026-05-21T10:00:00",
                    pnl = 0.0,
                    trades = 0,
                    maxAtRisk = 500,
                    status = SessionStatus.CLOSED,
                    touchTurnMilestones = milestones.copy(dataReadyAt = "2026-05-21T09:31:00")
                ),
                StrategySession(
                    id = "latest",
                    date = "2026-05-22",
                    startedAt = "2026-05-22T09:30:00",
                    stoppedAt = "2026-05-22T11:00:00",
                    pnl = 1.0,
                    trades = 1,
                    maxAtRisk = 500,
                    status = SessionStatus.CLOSED,
                    touchTurnMilestones = milestones
                )
            )
        )
        val steps = TouchTurnStatusBreadcrumbMapper.pipelineForLastClosedSession(instance)
        assertEquals(8, steps?.size)
        assertEquals("11:00", steps?.get(7)?.timestamp)
    }

    @Test
    fun stepsFromHistory_reconstructsCompletedPipeline() {
        val milestones = daytrader.domain.TouchTurnMilestoneTimestamps(
            startingSessionAt = "2026-05-22T09:30:05",
            dataReadyAt = "2026-05-22T09:30:12",
            barClosedAt = "2026-05-22T09:45:00",
            liquidityEvaluatedAt = "2026-05-22T09:45:01",
            closeConfirmedAt = "2026-05-22T09:45:02",
            ordersPlacedAt = "2026-05-22T09:45:05",
            positionOpenedAt = "2026-05-22T09:46:10",
            closingSessionAt = "2026-05-22T11:00:00"
        )
        val steps = TouchTurnStatusBreadcrumbMapper.stepsFromHistory(
            milestones = milestones,
            startedAt = "2026-05-22T09:30:05",
            stoppedAt = "2026-05-22T11:00:01",
            hadLiquidityCandle = true,
            ordersPlacedForCandle = true,
            positionOpened = true
        )
        assertTrue(steps.all { it.state == TouchTurnBreadcrumbStepState.COMPLETED })
        assertEquals("09:30", steps[0].timestamp)
        assertEquals("11:00", steps[7].timestamp)
    }

    @Test
    fun graph_notLiquidity_activePathUsesNoTradeBranch() {
        val barTime = "20260522  09:30:00"
        val now = barEnd(barTime) + 1
        val session = readySession(
            candle = bar(barTime),
            rangeThreshold = 10.0,
            now = now
        )
        val graph = TouchTurnStatusBreadcrumbMapper.graph(
            instance = deployment(session),
            hasOpenPosition = false,
            nowEpochMillis = now
        )
        assertTrue(TouchTurnPipelineNodeId.NoTrade in graph.activePath)
        assertTrue(TouchTurnPipelineNodeId.Orders !in graph.activePath)
        assertTrue(graph.caption.isNotBlank())
        val liqToConfirm = graph.edges.first {
            it.from == TouchTurnPipelineNodeId.Liquidity && it.to == TouchTurnPipelineNodeId.Confirmation
        }
        assertEquals(TouchTurnPipelineEdgeState.Dimmed, liqToConfirm.state)
    }

    @Test
    fun graph_ordersPlacedAfterConfirmationWindow_staysOnTradePathNotNoTrade() {
        val barTime = "20260529  08:00:00"
        val zone = "Europe/London"
        val barEnd = TouchTurnLogic.barEndEpochMillis(barTime, zone)!!
        val atLiquidity = barEnd + 4
        // Past confirmation deadline; keep within 90m-after-open auto-stop for graph timing.
        val pastConfirmationDeadline = barEnd + TouchTurnDefaults.CLOSE_CONFIRMATION_AFTER_CLOSE_MS + 1
        val graphNow = barEnd + 90_000
        val bar = OhlcBar(open = 105.0, high = 110.0, low = 100.0, close = 104.0, time = barTime)
        val session = TouchTurnSessionContext(
            sessionDate = "2026-05-29",
            status = TouchTurnCandleStatus.READY,
            candle = bar,
            marketZoneId = zone,
            rangeThreshold = 0.01,
            adr14 = 0.04
        )
        val withLiquidity = deployment(session).withLiquidityEvaluatedIfClosed(
            enforceCloseConfirmation = false,
            nowEpochMillis = atLiquidity
        )
        val withOrders = withLiquidity.withOrdersPlacedForSession(null)
        val liveSession = withOrders.touchTurnSession!!
        assertEquals(TouchTurnSessionOutcome.TRADE_BRACKET_SUBMITTED, liveSession.decisionOutcome)
        assertEquals(TouchTurnCloseConfirmation.EXPIRED, liveSession.closeConfirmation(pastConfirmationDeadline))

        val graph = TouchTurnStatusBreadcrumbMapper.graph(
            instance = withOrders,
            hasOpenPosition = false,
            hasOpenOrders = true,
            nowEpochMillis = graphNow
        )
        assertTrue(TouchTurnPipelineNodeId.NoTrade !in graph.activePath)
        assertTrue(
            TouchTurnPipelineNodeId.Orders in graph.activePath ||
                TouchTurnPipelineNodeId.Position in graph.activePath
        )
        assertEquals(
            TouchTurnBreadcrumbStepState.COMPLETED,
            graph.nodes.first { it.id == TouchTurnPipelineNodeId.Confirmation }.state
        )
        val steps = TouchTurnStatusBreadcrumbMapper.steps(
            instance = withOrders,
            hasOpenPosition = false,
            hasOpenOrders = true,
            nowEpochMillis = graphNow
        )
        assertEquals(TouchTurnBreadcrumbStepState.COMPLETED, steps[4].state)
        assertEquals(TouchTurnBreadcrumbStepState.CURRENT, steps[5].state)
        assertEquals(TouchTurnBreadcrumbStepState.UPCOMING, steps[6].state)
        assertEquals(TouchTurnPipelineNodeId.Orders, graph.activePath.last())
    }

    @Test
    fun graph_confirmationExpired_activePathUsesNoTradeNotOrders() {
        val barTime = "20260522  09:30:00"
        val barEnd = barEnd(barTime)
        val now = barEnd + TouchTurnDefaults.CLOSE_CONFIRMATION_AFTER_CLOSE_MS + 1
        val bar = OhlcBar(open = 105.0, high = 106.0, low = 99.0, close = 104.0, time = barTime)
        val base = deployment(
            readySession(candle = bar, rangeThreshold = 0.01, now = barEnd + 1)
        )
        val evaluated = base.withLiquidityEvaluatedIfClosed(
            enforceCloseConfirmation = true,
            nowEpochMillis = now
        )
        val session = evaluated.touchTurnSession!!
        assertEquals(TouchTurnSessionOutcome.NO_TRADE_ENTRY_WINDOW_EXPIRED, session.decisionOutcome)
        assertEquals(TouchTurnCloseConfirmation.EXPIRED, session.closeConfirmation(now))

        val graph = TouchTurnStatusBreadcrumbMapper.graph(
            instance = evaluated,
            hasOpenPosition = false,
            nowEpochMillis = now
        )
        assertTrue(TouchTurnPipelineNodeId.NoTrade in graph.activePath)
        assertTrue(TouchTurnPipelineNodeId.Close in graph.activePath)
        assertTrue(TouchTurnPipelineNodeId.Orders !in graph.activePath)
        assertTrue(TouchTurnPipelineNodeId.Position !in graph.activePath)
        assertEquals(
            TouchTurnBreadcrumbStepState.FAILED,
            graph.nodes.first { it.id == TouchTurnPipelineNodeId.Confirmation }.state
        )
    }

    @Test
    fun stepsFromHistory_confirmationExpired_skipsOrdersAndMarksConfirmFailed() {
        val milestones = TouchTurnMilestoneTimestamps(
            startingSessionAt = "2026-05-22T09:30:05",
            dataReadyAt = "2026-05-22T09:30:12",
            barClosedAt = "2026-05-22T09:45:00",
            liquidityEvaluatedAt = "2026-05-22T10:55:00",
            closingSessionAt = "2026-05-22T10:55:05"
        )
        val steps = TouchTurnStatusBreadcrumbMapper.stepsFromHistory(
            milestones = milestones,
            startedAt = "2026-05-22T09:30:05",
            stoppedAt = "2026-05-22T10:55:05",
            hadLiquidityCandle = true,
            ordersPlacedForCandle = false,
            positionOpened = false,
            decisionOutcome = TouchTurnSessionOutcome.NO_TRADE_ENTRY_WINDOW_EXPIRED
        )
        assertEquals(TouchTurnBreadcrumbStepState.FAILED, steps[4].state)
        assertEquals(TouchTurnBreadcrumbStepState.SKIPPED, steps[5].state)
        assertEquals(TouchTurnBreadcrumbStepState.SKIPPED, steps[6].state)
        val graph = TouchTurnStatusBreadcrumbMapper.graphFromHistory(
            milestones = milestones,
            startedAt = "2026-05-22T09:30:05",
            stoppedAt = "2026-05-22T10:55:05",
            hadLiquidityCandle = true,
            ordersPlacedForCandle = false,
            positionOpened = false,
            decisionOutcome = TouchTurnSessionOutcome.NO_TRADE_ENTRY_WINDOW_EXPIRED
        )
        assertTrue(TouchTurnPipelineNodeId.NoTrade in graph.activePath)
        assertTrue(TouchTurnPipelineNodeId.Orders !in graph.activePath)
    }

    @Test
    fun graph_confirmationExpired_afterSessionStop_activePathUsesNoTradeNotOrders() {
        val barTime = "20260522  09:30:00"
        val barEnd = barEnd(barTime)
        val now = barEnd + TouchTurnDefaults.CLOSE_CONFIRMATION_AFTER_CLOSE_MS + 1
        val bar = OhlcBar(open = 105.0, high = 106.0, low = 99.0, close = 104.0, time = barTime)
        val afterEval = deployment(
            readySession(candle = bar, rangeThreshold = 0.01, now = barEnd + 1)
        ).withLiquidityEvaluatedIfClosed(
            enforceCloseConfirmation = true,
            nowEpochMillis = now
        )
        val stopped = afterEval.copy(
            status = DeploymentStatus.STOPPED,
            touchTurnSession = afterEval.touchTurnSession?.copy(
                milestones = afterEval.touchTurnSession!!.milestones.copy(
                    closingSessionAt = "2026-05-22T10:55:05"
                )
            )
        )

        val graph = TouchTurnStatusBreadcrumbMapper.graph(
            instance = stopped,
            hasOpenPosition = false,
            nowEpochMillis = now
        )
        assertTrue(TouchTurnPipelineNodeId.NoTrade in graph.activePath)
        assertTrue(TouchTurnPipelineNodeId.Close in graph.activePath)
        assertTrue(TouchTurnPipelineNodeId.Orders !in graph.activePath)
        assertTrue(TouchTurnPipelineNodeId.Position !in graph.activePath)
        val confirmToOrders = graph.edges.first {
            it.from == TouchTurnPipelineNodeId.Confirmation &&
                it.to == TouchTurnPipelineNodeId.Orders
        }
        assertEquals(TouchTurnPipelineEdgeState.Unreachable, confirmToOrders.state)
    }

    @Test
    fun graphForLastClosedSession_tradeRun_matchesHistoryPathNotNoTrade() {
        val bar = OhlcBar(open = 123.2, high = 123.4, low = 121.9, close = 122.7, time = "20260529  09:30:00")
        val milestones = TouchTurnMilestoneTimestamps(
            startingSessionAt = "2026-05-29T09:31:00",
            dataReadyAt = "2026-05-29T09:31:02",
            barClosedAt = "2026-05-29T09:45:02",
            liquidityEvaluatedAt = "2026-05-29T09:45:02",
            closeConfirmedAt = "2026-05-29T09:45:02",
            ordersPlacedAt = "2026-05-29T09:45:02",
            positionOpenedAt = "2026-05-29T09:45:02",
            closingSessionAt = "2026-05-29T09:45:13"
        )
        val closedRun = StrategySession(
            id = "session-aaf7b8170f74267a",
            date = "2026-05-29",
            startedAt = "2026-05-29T09:31:00",
            stoppedAt = "2026-05-29T09:45:13",
            pnl = -23.49,
            trades = 1,
            maxAtRisk = 10_000,
            status = SessionStatus.CLOSED,
            positionOpened = true,
            hadLiquidityCandle = true,
            ordersPlacedForCandle = true,
            touchTurnMilestones = milestones,
            touchTurnRunRecord = TouchTurnRunRecord(
                runContext = TouchTurnRunContext(
                    maxDollars = 10_000,
                    startedBy = TouchTurnSessionStartedBy.AUTO_MARKET_OPEN,
                    brokerId = BrokerId.EMULATOR
                ),
                marketInputs = TouchTurnRunMarketInputs(
                    openingBar = bar,
                    adr14 = 4.62,
                    currencyCode = "HKD",
                    marketZoneId = "Asia/Hong_Kong"
                ),
                decision = TouchTurnSessionDecision(
                    outcome = TouchTurnSessionOutcome.TRADE_BRACKET_SUBMITTED,
                    plannedQuantity = 82
                ),
                stopEvent = TouchTurnStopEvent(stopTrigger = TouchTurnSessionStopTrigger.TRADE_OUTCOME_KNOWN),
                milestones = milestones
            )
        )
        val instance = deployment(touchTurnSession = null).copy(
            status = DeploymentStatus.STOPPED,
            sessionHistory = listOf(closedRun)
        )
        val recapGraph = TouchTurnStatusBreadcrumbMapper.graphForLastClosedSession(instance)!!
        val historyGraph = TouchTurnStatusBreadcrumbMapper.graphFromHistory(
            milestones = milestones,
            startedAt = closedRun.startedAt,
            stoppedAt = closedRun.stoppedAt,
            hadLiquidityCandle = true,
            ordersPlacedForCandle = true,
            positionOpened = true,
            decisionOutcome = TouchTurnSessionOutcome.TRADE_BRACKET_SUBMITTED
        )
        assertEquals(historyGraph.activePath, recapGraph.activePath)
        assertTrue(TouchTurnPipelineNodeId.Orders in recapGraph.activePath)
        assertTrue(TouchTurnPipelineNodeId.Position in recapGraph.activePath)
        assertTrue(TouchTurnPipelineNodeId.NoTrade !in recapGraph.activePath)
    }

    @Test
    fun graph_openPosition_activePathIncludesPosition() {
        val barTime = "20260522  09:30:00"
        val now = barEnd(barTime) + 1
        val session = readySession(
            candle = bar(barTime),
            rangeThreshold = 0.01,
            now = now
        ).copy(ordersPlacedForSession = true)
        val graph = TouchTurnStatusBreadcrumbMapper.graph(
            instance = deployment(session),
            hasOpenPosition = true,
            nowEpochMillis = now
        )
        assertEquals(TouchTurnPipelineNodeId.Position, graph.activePath.last())
        assertTrue(
            graph.nodes.first { it.id == TouchTurnPipelineNodeId.Position }.state ==
                TouchTurnBreadcrumbStepState.CURRENT
        )
    }
}
