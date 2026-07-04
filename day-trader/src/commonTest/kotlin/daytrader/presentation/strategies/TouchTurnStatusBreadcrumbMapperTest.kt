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
import daytrader.domain.TouchTurnRuleConfig
import daytrader.domain.TouchTurnRuleEnables
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
import daytrader.domain.withTouchTurnCandleFailed
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
        assertEquals("Start", steps[0].label)
        assertEquals(TouchTurnBreadcrumbStepState.CURRENT, steps[0].state)
        assertEquals("Close", steps[6].label)
        assertEquals(TouchTurnBreadcrumbStepState.UPCOMING, steps[6].state)
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
        val evaluated = deployment(session).withLiquidityEvaluatedIfClosed(
            enforceCloseConfirmation = false,
            nowEpochMillis = now
        )
        val steps = TouchTurnStatusBreadcrumbMapper.steps(
            instance = evaluated,
            hasOpenPosition = false,
            nowEpochMillis = now
        )
        assertEquals(TouchTurnBreadcrumbStepState.COMPLETED, steps[2].state)
        assertEquals(TouchTurnBreadcrumbStepState.SKIPPED, steps[3].state)
        assertEquals(TouchTurnBreadcrumbStepState.SKIPPED, steps[4].state)
        assertEquals(TouchTurnBreadcrumbStepState.SKIPPED, steps[5].state)
        assertEquals(TouchTurnBreadcrumbStepState.COMPLETED, steps[6].state)
        assertTrue(steps.none { it.state == TouchTurnBreadcrumbStepState.CURRENT })
    }

    @Test
    fun volumeExhaustion_terminalAtClosingSession() {
        val barTime = "20260603  08:00:00"
        val zone = "Europe/London"
        val barEnd = TouchTurnLogic.barEndEpochMillis(barTime, zone)!!
        val now = barEnd + 1
        val highVolume = 1_000_000.0
        val candle = OhlcBar(
            open = 542.15,
            high = 544.0,
            low = 537.5,
            close = 541.1,
            volume = highVolume,
            time = barTime
        )
        val session = TouchTurnSessionContext(
            sessionDate = "2026-06-03",
            status = TouchTurnCandleStatus.READY,
            openingBarTime = barTime,
            candle = candle,
            marketZoneId = zone,
            rangeThreshold = 0.01,
            adr14 = 2.43,
            volumeSma20 = 100.0,
            decisionOutcome = TouchTurnSessionOutcome.NO_TRADE_VOLUME_EXHAUSTION,
            entryOrdersPermitted = false,
            milestones = TouchTurnMilestoneTimestamps(
                startingSessionAt = "2026-06-03T15:01:00",
                dataReadyAt = "2026-06-03T15:01:06",
                barClosedAt = "2026-06-03T15:15:01",
                liquidityEvaluatedAt = "2026-06-03T15:15:09"
            )
        )
        val instance = deployment(session)
        val steps = TouchTurnStatusBreadcrumbMapper.steps(
            instance = instance,
            hasOpenPosition = false,
            nowEpochMillis = now
        )
        assertEquals(TouchTurnBreadcrumbStepState.COMPLETED, steps[2].state)
        assertEquals(TouchTurnBreadcrumbStepState.COMPLETED, steps[6].state)
        val graph = TouchTurnStatusBreadcrumbMapper.graph(
            instance = instance,
            hasOpenPosition = false,
            nowEpochMillis = now
        )
        assertEquals(
            TouchTurnBreadcrumbStepState.COMPLETED,
            graph.nodes.first { it.id == TouchTurnPipelineNodeId.Close }.state
        )
        assertTrue(TouchTurnPipelineNodeId.Close in graph.activePath)
        assertTrue(TouchTurnPipelineNodeId.Orders !in graph.activePath)
    }

    @Test
    fun livePhase_followsEngineMilestones_notCalendarLiquidity() {
        val barTime = "20260522  09:30:00"
        val barEnd = barEnd(barTime)
        val session = TouchTurnSessionContext(
            sessionDate = "2026-05-22",
            status = TouchTurnCandleStatus.READY,
            openingBarTime = barTime,
            candle = null,
            marketZoneId = "America/New_York",
            rangeThreshold = 0.01,
            adr14 = 0.04,
            milestones = TouchTurnMilestoneTimestamps(
                startingSessionAt = "2026-05-22T09:30:05",
                dataReadyAt = "2026-05-22T09:30:12",
                barClosedAt = "2026-05-22T09:45:00"
            )
        )
        assertEquals(FirstCandleCloseStatus.CLOSED, session.candleCloseStatus(barEnd + 1))
        val steps = TouchTurnStatusBreadcrumbMapper.steps(
            instance = deployment(session),
            hasOpenPosition = false,
            nowEpochMillis = barEnd + 1
        )
        assertEquals(TouchTurnBreadcrumbStepState.CURRENT, steps[1].state)
        assertEquals("Data", steps[1].label)
        assertEquals(TouchTurnBreadcrumbStepState.UPCOMING, steps[2].state)
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
        val instance = deploymentAfterLiquidity(
            candle = bar(barTime),
            rangeThreshold = 0.01,
            now = now
        ).withOrdersPlacedForSession(null)
        val steps = TouchTurnStatusBreadcrumbMapper.steps(
            instance = instance,
            hasOpenPosition = true,
            nowEpochMillis = now
        )
        assertEquals(TouchTurnBreadcrumbStepState.CURRENT, steps[5].state)
        assertEquals(TouchTurnBreadcrumbStepState.UPCOMING, steps[6].state)
    }

    @Test
    fun openPosition_withMilestone_staysCurrentUntilFlat() {
        val barTime = "20260522  09:30:00"
        val now = barEnd(barTime) + 1
        val base = deploymentAfterLiquidity(
            candle = bar(barTime),
            rangeThreshold = 0.01,
            now = now
        ).withOrdersPlacedForSession(null)
        val instance = base.copy(
            touchTurnSession = base.touchTurnSession!!.copy(
                milestones = base.touchTurnSession!!.milestones.copy(
                    positionOpenedAt = "2026-05-22T09:46:10"
                )
            )
        )
        val steps = TouchTurnStatusBreadcrumbMapper.steps(
            instance = instance,
            hasOpenPosition = true,
            nowEpochMillis = now
        )
        assertEquals(TouchTurnBreadcrumbStepState.COMPLETED, steps[4].state)
        assertEquals(TouchTurnBreadcrumbStepState.CURRENT, steps[5].state)
        assertEquals(TouchTurnBreadcrumbStepState.UPCOMING, steps[6].state)
    }

    @Test
    fun livePhase_fiveMinuteConfirmationEnabled_currentIsFiveMinStep() {
        val barTime = "20260522  09:30:00"
        val now = barEnd(barTime) + 1
        val base = deploymentAfterLiquidity(
            candle = bar(barTime),
            rangeThreshold = 0.01,
            now = now
        )
        val instance = base.copy(
            touchTurnSession = base.touchTurnSession!!.copy(
                rules = base.touchTurnSession!!.rules.copy(
                    enables = base.touchTurnSession!!.rules.enables.copy(
                        fiveMinuteConfirmation = true
                    )
                ),
                sweepActive = true,
                fiveMinuteConfirmation = daytrader.domain.FiveMinuteConfirmationLogic.initialState(
                    candle = base.touchTurnSession!!.candle!!,
                    side = base.touchTurnSession!!.setup!!.side,
                    nowEpochMillis = now
                )
            )
        )
        val steps = TouchTurnStatusBreadcrumbMapper.steps(
            instance = instance,
            hasOpenPosition = false,
            nowEpochMillis = now
        )
        assertEquals(TouchTurnBreadcrumbStepState.COMPLETED, steps[2].state)
        assertEquals(TouchTurnBreadcrumbStepState.CURRENT, steps[3].state)
        assertEquals("5m", steps[3].label)
        val graph = TouchTurnStatusBreadcrumbMapper.graph(
            instance = instance,
            hasOpenPosition = false,
            nowEpochMillis = now
        )
        assertTrue(TouchTurnPipelineNodeId.FiveMinConfirmation in graph.activePath)
        assertTrue(graph.caption.contains("Awaiting 5m hammer"))
        assertEquals(7, graph.nodes.size)
        assertTrue(graph.nodes.any { it.id == TouchTurnPipelineNodeId.FiveMinConfirmation })
    }

    @Test
    fun livePhase_fiveMinuteConfirmed_advancesToOrdersBeforeFill() {
        val barTime = "20260522  09:30:00"
        val now = barEnd(barTime) + 1
        val base = deploymentAfterLiquidity(
            candle = bar(barTime),
            rangeThreshold = 0.01,
            now = now
        )
        val instance = base.copy(
            touchTurnSession = base.touchTurnSession!!.copy(
                rules = base.touchTurnSession!!.rules.copy(
                    enables = base.touchTurnSession!!.rules.enables.copy(
                        fiveMinuteConfirmation = true
                    )
                ),
                sweepActive = false,
                fiveMinuteConfirmation = daytrader.domain.FiveMinuteConfirmationLogic.initialState(
                    candle = base.touchTurnSession!!.candle!!,
                    side = base.touchTurnSession!!.setup!!.side,
                    nowEpochMillis = now
                ).copy(
                    status = daytrader.domain.FiveMinuteConfirmationStatus.CONFIRMED,
                    confirmedHammerBar = bar("20260522  09:35:00")
                ),
                milestones = base.touchTurnSession!!.milestones.copy(
                    fiveMinConfirmedAt = "2026-05-22T09:35:01"
                )
            )
        )
        val steps = TouchTurnStatusBreadcrumbMapper.steps(
            instance = instance,
            hasOpenPosition = false,
            nowEpochMillis = now
        )
        assertEquals(TouchTurnBreadcrumbStepState.CURRENT, steps[4].state)
        assertEquals("Orders", steps[4].label)
    }

    @Test
    fun livePhase_entryPermitted_staysOnRulesUntilOrdersPlaced() {
        val barTime = "20260522  09:30:00"
        val now = barEnd(barTime) + 1
        val instance = deploymentAfterLiquidity(
            candle = bar(barTime),
            rangeThreshold = 0.01,
            now = now
        )
        val steps = TouchTurnStatusBreadcrumbMapper.steps(
            instance = instance,
            hasOpenPosition = false,
            hasOpenOrders = false,
            nowEpochMillis = now
        )
        assertEquals(TouchTurnBreadcrumbStepState.CURRENT, steps[2].state)
        assertEquals(TouchTurnBreadcrumbStepState.SKIPPED, steps[3].state)
        assertEquals(TouchTurnBreadcrumbStepState.UPCOMING, steps[4].state)

        val graph = TouchTurnStatusBreadcrumbMapper.graph(
            instance = instance,
            hasOpenPosition = false,
            hasOpenOrders = false,
            nowEpochMillis = now
        )
        assertEquals(6, graph.nodes.size)
        assertTrue(graph.nodes.none { it.id == TouchTurnPipelineNodeId.FiveMinConfirmation })
        assertTrue(graph.edges.none {
            it.from == TouchTurnPipelineNodeId.FiveMinConfirmation ||
                it.to == TouchTurnPipelineNodeId.FiveMinConfirmation
        })
    }

    @Test
    fun ordersPlaced_waitingForEntry_ordersCurrent() {
        val barTime = "20260522  09:30:00"
        val now = barEnd(barTime) + 1
        val instance = deploymentAfterLiquidity(
            candle = bar(barTime),
            rangeThreshold = 0.01,
            now = now
        ).withOrdersPlacedForSession(null)
        val steps = TouchTurnStatusBreadcrumbMapper.steps(
            instance = instance,
            hasOpenPosition = false,
            hasOpenOrders = true,
            nowEpochMillis = now
        )
        assertEquals(TouchTurnBreadcrumbStepState.COMPLETED, steps[2].state)
        assertEquals(TouchTurnBreadcrumbStepState.SKIPPED, steps[3].state)
        assertEquals(TouchTurnBreadcrumbStepState.CURRENT, steps[4].state)
        assertEquals(TouchTurnBreadcrumbStepState.UPCOMING, steps[5].state)
    }

    @Test
    fun graph_entryNeverFilled_atDeadline_skipsPositionAndCloses() {
        val barTime = "20260522  09:30:00"
        val zone = "America/New_York"
        val open = TouchTurnLogic.marketOpenEpochMillis("2026-05-22", zone, barTime)!!
        val now = open + 90 * 60_000 + 1
        val rules = TouchTurnRuleConfig.DEFAULT.copy(
            enables = TouchTurnRuleEnables.DEFAULT.copy(openDeadline = true)
        )
        val session = readySession(
            candle = bar(barTime),
            rangeThreshold = 0.01,
            now = barEnd(barTime) + 1
        ).copy(
            ordersPlacedForSession = true,
            rules = rules,
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
        assertTrue(TouchTurnPipelineNodeId.Rules in graph.activePath)
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
        assertEquals(TouchTurnBreadcrumbStepState.COMPLETED, steps[4].state)
        assertEquals(TouchTurnBreadcrumbStepState.SKIPPED, steps[5].state)
        assertEquals(TouchTurnBreadcrumbStepState.COMPLETED, steps[6].state)
    }

    private fun deployment(touchTurnSession: TouchTurnSessionContext?) = StrategyDeployment(
        id = "tt-1",
        strategyType = StrategyType.TOUCH_AND_TURN_SCALPER,
        status = DeploymentStatus.RUNNING,
        symbol = "AAPL",
        maxDollars = 500,
        touchTurnSession = touchTurnSession
    )

    private fun liquidityRules() = TouchTurnRuleConfig.DEFAULT.copy(
        enables = TouchTurnRuleEnables.DEFAULT.copy(liquidityRangeDailyAtr = true)
    )

    private fun readySession(
        candle: OhlcBar,
        rangeThreshold: Double,
        now: Long
    ): TouchTurnSessionContext {
        val dailyAtr = rangeThreshold / TouchTurnDefaults.ATR_LIQUIDITY_RATIO
        val session = TouchTurnSessionContext(
            sessionDate = "2026-05-22",
            status = TouchTurnCandleStatus.READY,
            openingBarTime = candle.time,
            candle = candle,
            marketZoneId = "America/New_York",
            rangeThreshold = rangeThreshold,
            dailyAtr14 = dailyAtr,
            adr14 = dailyAtr,
            rules = liquidityRules(),
            milestones = TouchTurnMilestoneTimestamps(
                dataReadyAt = "2026-05-22T09:30:12",
                barClosedAt = "2026-05-22T09:45:00"
            )
        )
        assertEquals(FirstCandleCloseStatus.CLOSED, session.candleCloseStatus(now))
        return session
    }

    private fun deploymentAfterLiquidity(
        candle: OhlcBar,
        rangeThreshold: Double,
        now: Long,
        enforceCloseConfirmation: Boolean = false
    ): StrategyDeployment =
        deployment(readySession(candle, rangeThreshold, now)).withLiquidityEvaluatedIfClosed(
            enforceCloseConfirmation = enforceCloseConfirmation,
            nowEpochMillis = now
        )

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
        assertEquals(7, steps?.size)
        assertEquals("11:00", steps?.get(6)?.timestamp)
    }

    @Test
    fun stepsFromHistory_reconstructsCompletedPipeline() {
        val milestones = daytrader.domain.TouchTurnMilestoneTimestamps(
            startingSessionAt = "2026-05-22T09:30:05",
            dataReadyAt = "2026-05-22T09:30:12",
            barClosedAt = "2026-05-22T09:45:00",
            liquidityEvaluatedAt = "2026-05-22T09:45:01",
            closeConfirmedAt = "2026-05-22T09:45:02",
            fiveMinConfirmedAt = "2026-05-22T09:45:04",
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
        assertEquals("11:00", steps[6].timestamp)
    }

    @Test
    fun graph_notLiquidity_activePathSkipsOrders() {
        val barTime = "20260522  09:30:00"
        val now = barEnd(barTime) + 1
        val instance = deploymentAfterLiquidity(
            candle = bar(barTime),
            rangeThreshold = 10.0,
            now = now
        )
        val graph = TouchTurnStatusBreadcrumbMapper.graph(
            instance = instance,
            hasOpenPosition = false,
            nowEpochMillis = now
        )
        assertTrue(TouchTurnPipelineNodeId.Rules in graph.activePath)
        assertTrue(TouchTurnPipelineNodeId.Orders !in graph.activePath)
        assertTrue(graph.caption.isNotBlank())
        assertTrue(TouchTurnPipelineNodeId.Close in graph.activePath)
        val rulesToOrders = graph.edges.first {
            it.from == TouchTurnPipelineNodeId.Rules && it.to == TouchTurnPipelineNodeId.Orders
        }
        assertEquals(TouchTurnPipelineEdgeState.Dimmed, rulesToOrders.state)
    }

    @Test
    fun steps_ordersPlaced_currentOnOrdersWhenSessionDateClosedBeforeBarWallEnd() {
        val sessionDate = "2026-06-01"
        val zone = "Asia/Hong_Kong"
        val barTime = "20260601  16:27:06"
        val bar = OhlcBar(open = 384.0, high = 389.0, low = 383.0, close = 388.0, time = barTime)
        val now = TouchTurnLogic.marketOpenEpochMillis(sessionDate, zone)!! +
            TouchTurnLogic.FIRST_CANDLE_BAR_DURATION_MS + 60_000
        val instance = deployment(
            TouchTurnSessionContext(
                sessionDate = sessionDate,
                status = TouchTurnCandleStatus.READY,
                openingBarTime = bar.time,
                candle = bar,
                marketZoneId = zone,
                rangeThreshold = 1.0,
                adr14 = 7.0,
                decisionOutcome = TouchTurnSessionOutcome.TRADE_BRACKET_SUBMITTED,
                ordersPlacedForSession = true,
                entryOrdersPermitted = true,
                milestones = TouchTurnMilestoneTimestamps(
                    dataReadyAt = "2026-06-01T16:27:10",
                    barClosedAt = "2026-06-01T16:42:00",
                    liquidityEvaluatedAt = "2026-06-01T16:42:01",
                    closeConfirmedAt = "2026-06-01T16:42:02",
                    ordersPlacedAt = "2026-06-01T16:42:03"
                )
            )
        )
        val steps = TouchTurnStatusBreadcrumbMapper.steps(
            instance = instance,
            hasOpenPosition = false,
            hasOpenOrders = true,
            nowEpochMillis = now
        )
        assertEquals(TouchTurnBreadcrumbStepState.COMPLETED, steps[1].state)
        assertEquals(TouchTurnBreadcrumbStepState.CURRENT, steps[4].state)
    }

    @Test
    fun graph_ordersPlacedAfterConfirmationWindow_staysOnTradePath() {
        val barTime = "20260529  08:00:00"
        val zone = "Europe/London"
        val barEnd = TouchTurnLogic.barEndEpochMillis(barTime, zone)!!
        val atLiquidity = barEnd + 4
        // Past confirmation deadline; keep within 90m-after-open auto-stop for graph timing.
        val pastConfirmationDeadline = barEnd + 60_000L + 1
        val graphNow = barEnd + 90_000
        val bar = OhlcBar(open = 105.0, high = 110.0, low = 100.0, close = 104.0, time = barTime)
        val session = TouchTurnSessionContext(
            sessionDate = "2026-05-29",
            status = TouchTurnCandleStatus.READY,
            candle = bar,
            marketZoneId = zone,
            rangeThreshold = 0.01,
            dailyAtr14 = 0.04,
            adr14 = 0.04,
            rules = liquidityRules()
        )
        val withLiquidity = deployment(session).withLiquidityEvaluatedIfClosed(
            enforceCloseConfirmation = false,
            nowEpochMillis = atLiquidity
        )
        val withOrders = withLiquidity.withOrdersPlacedForSession(null)
        val liveSession = withOrders.touchTurnSession!!
        assertEquals(TouchTurnSessionOutcome.TRADE_BRACKET_SUBMITTED, liveSession.decisionOutcome)
        assertEquals(TouchTurnCloseConfirmation.PASSED, liveSession.closeConfirmation(pastConfirmationDeadline))

        val graph = TouchTurnStatusBreadcrumbMapper.graph(
            instance = withOrders,
            hasOpenPosition = false,
            hasOpenOrders = true,
            nowEpochMillis = graphNow
        )
        assertTrue(TouchTurnPipelineNodeId.Orders in graph.activePath)
        assertEquals(
            TouchTurnBreadcrumbStepState.COMPLETED,
            graph.nodes.first { it.id == TouchTurnPipelineNodeId.Rules }.state
        )
        val steps = TouchTurnStatusBreadcrumbMapper.steps(
            instance = withOrders,
            hasOpenPosition = false,
            hasOpenOrders = true,
            nowEpochMillis = graphNow
        )
        assertEquals(TouchTurnBreadcrumbStepState.COMPLETED, steps[2].state)
        assertEquals(TouchTurnBreadcrumbStepState.SKIPPED, steps[3].state)
        assertEquals(TouchTurnBreadcrumbStepState.CURRENT, steps[4].state)
        assertEquals(TouchTurnBreadcrumbStepState.UPCOMING, steps[5].state)
        assertEquals(TouchTurnPipelineNodeId.Orders, graph.activePath.last())
    }

    @Test
    fun stepsFromHistory_confirmationExpired_skipsOrdersAndMarksRulesFailed() {
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
        assertEquals(TouchTurnBreadcrumbStepState.FAILED, steps[2].state)
        assertEquals(TouchTurnBreadcrumbStepState.SKIPPED, steps[3].state)
        assertEquals(TouchTurnBreadcrumbStepState.SKIPPED, steps[4].state)
        assertEquals(TouchTurnBreadcrumbStepState.SKIPPED, steps[5].state)
        val graph = TouchTurnStatusBreadcrumbMapper.graphFromHistory(
            milestones = milestones,
            startedAt = "2026-05-22T09:30:05",
            stoppedAt = "2026-05-22T10:55:05",
            hadLiquidityCandle = true,
            ordersPlacedForCandle = false,
            positionOpened = false,
            decisionOutcome = TouchTurnSessionOutcome.NO_TRADE_ENTRY_WINDOW_EXPIRED
        )
        assertTrue(TouchTurnPipelineNodeId.Close in graph.activePath)
        assertTrue(TouchTurnPipelineNodeId.Orders !in graph.activePath)
    }

    @Test
    fun graphForLastClosedSession_tradeRun_matchesHistoryPath() {
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
        assertTrue(TouchTurnPipelineNodeId.Close in recapGraph.activePath)
    }

    @Test
    fun graph_openPosition_activePathIncludesPosition() {
        val barTime = "20260522  09:30:00"
        val now = barEnd(barTime) + 1
        val instance = deploymentAfterLiquidity(
            candle = bar(barTime),
            rangeThreshold = 0.01,
            now = now
        ).withOrdersPlacedForSession(null)
        val graph = TouchTurnStatusBreadcrumbMapper.graph(
            instance = instance,
            hasOpenPosition = true,
            nowEpochMillis = now
        )
        assertEquals(TouchTurnPipelineNodeId.Position, graph.activePath.last())
        assertTrue(
            graph.nodes.first { it.id == TouchTurnPipelineNodeId.Position }.state ==
                TouchTurnBreadcrumbStepState.CURRENT
        )
    }

    @Test
    fun liquidityRefetchFailed_currentIsRulesNotData() {
        val instance = deployment(
            TouchTurnSessionContext(
                sessionDate = "2026-06-03",
                status = TouchTurnCandleStatus.READY,
                openingBarTime = "20260603  09:30:00",
                marketZoneId = "Asia/Hong_Kong",
                milestones = TouchTurnMilestoneTimestamps(
                    dataReadyAt = "2026-06-03T09:30:12",
                    barClosedAt = "2026-06-03T09:45:00"
                )
            )
        ).withTouchTurnCandleFailed(
            sessionDate = "2026-06-03",
            message = "Closed 15-minute bar not final after 8 refetches"
        )
        val steps = TouchTurnStatusBreadcrumbMapper.steps(
            instance = instance,
            hasOpenPosition = false
        )
        assertEquals(TouchTurnBreadcrumbStepState.COMPLETED, steps[1].state)
        assertEquals(TouchTurnBreadcrumbStepState.FAILED, steps[2].state)
        assertEquals("Rules", steps[2].label)
        assertEquals(TouchTurnBreadcrumbStepState.SKIPPED, steps[3].state)
        assertEquals(TouchTurnBreadcrumbStepState.COMPLETED, steps[6].state)
        val graph = TouchTurnStatusBreadcrumbMapper.graph(
            instance = instance,
            hasOpenPosition = false
        )
        assertTrue(TouchTurnPipelineNodeId.Rules in graph.activePath)
        assertTrue(graph.caption.contains("closed bar"))
    }
}
