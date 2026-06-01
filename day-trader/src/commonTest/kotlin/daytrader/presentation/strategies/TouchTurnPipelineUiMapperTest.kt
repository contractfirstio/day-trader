package daytrader.presentation.strategies

import daytrader.data.DeploymentSessionStopEvaluator
import daytrader.domain.DeploymentStatus
import daytrader.domain.OhlcBar
import daytrader.domain.SessionTrade
import daytrader.domain.StrategyDeployment
import daytrader.domain.StrategyType
import daytrader.domain.TouchTurnCandleStatus
import daytrader.domain.TouchTurnLogic
import daytrader.domain.TouchTurnSessionContext
import daytrader.domain.onSessionStarted
import daytrader.domain.withLiquidityEvaluatedIfClosed
import daytrader.domain.withOrdersPlacedForSession
import daytrader.gateway.AccountPosition
import daytrader.gateway.BrokerFill
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TouchTurnPipelineUiMapperTest {
    @Test
    fun graph_tradeCycleComplete_showsClosingSessionWhenLiveFillsProvided() {
        val barTime = "20260522  09:30:00"
        val barEnd = TouchTurnLogic.barEndEpochMillis(barTime, "America/New_York")!!
        val now = barEnd + 30_000
        val instance = ordersPlacedDeployment(barTime, now)
        val sessionTrades = listOf(entryTrade(), exitTrade())

        val withoutLiveTrades = TouchTurnStatusBreadcrumbMapper.graph(
            instance = instance,
            hasOpenPosition = false,
            hasOpenOrders = false,
            sessionTrades = emptyList(),
            nowEpochMillis = now
        )
        assertEquals(TouchTurnPipelineNodeId.Orders, withoutLiveTrades.activePath.last())
        assertFalse(TouchTurnPipelineNodeId.Close in withoutLiveTrades.activePath)

        val withLiveTrades = TouchTurnStatusBreadcrumbMapper.graph(
            instance = instance,
            hasOpenPosition = false,
            hasOpenOrders = false,
            sessionTrades = sessionTrades,
            nowEpochMillis = now
        )
        assertTrue(TouchTurnPipelineNodeId.Close in withLiveTrades.activePath)
        assertEquals(
            TouchTurnBreadcrumbStepState.COMPLETED,
            withLiveTrades.nodes.first { it.id == TouchTurnPipelineNodeId.Close }.state
        )
    }

    @Test
    fun liveContext_matchesStopEvaluatorBrokerSignals() {
        val barTime = "20260522  09:30:00"
        val now = TouchTurnLogic.barEndEpochMillis(barTime, "America/New_York")!! + 1
        val instance = ordersPlacedDeployment(barTime, now)
        val positions = emptyList<AccountPosition>()
        val fills = listOf(
            BrokerFill(
                execId = "entry",
                orderId = 1,
                permId = 1L,
                parentOrderId = 0,
                symbol = "AAPL",
                side = "BOT",
                quantity = 1,
                price = 100.0,
                time = "2026-05-22T09:46:00",
                currency = "USD"
            ),
            BrokerFill(
                execId = "exit",
                orderId = 2,
                permId = 2L,
                parentOrderId = 1,
                symbol = "AAPL",
                side = "SLD",
                quantity = 1,
                price = 101.0,
                time = "2026-05-22T09:50:00",
                currency = "USD"
            )
        )
        val ctx = TouchTurnPipelineUiMapper.liveContext(
            instance = instance,
            brokerPositions = positions,
            brokerOpenOrders = emptyList(),
            brokerFills = fills,
            nowEpochMillis = now
        )
        val stopCandidate = DeploymentSessionStopEvaluator.evaluate(
            deployments = listOf(instance),
            positions = positions,
            openOrders = emptyList(),
            fills = fills,
            nowEpochMillis = now
        )
        assertFalse(ctx.hasOpenPosition)
        assertFalse(ctx.hasOpenOrders)
        assertEquals(2, ctx.sessionTrades.size)
        assertTrue(stopCandidate.any { it.instanceId == instance.id })
        val graph = TouchTurnPipelineUiMapper.graphForDeployment(
            instance = instance,
            brokerPositions = positions,
            brokerOpenOrders = emptyList(),
            brokerFills = fills,
            showLastSessionRecap = false,
            nowEpochMillis = now
        )!!
        assertTrue(TouchTurnPipelineNodeId.Close in graph.activePath)
    }

    @Test
    fun graph_barForming_currentIsBar() {
        val barTime = "20260522  09:30:00"
        val barEnd = TouchTurnLogic.barEndEpochMillis(barTime, "America/New_York")!!
        val instance = runningDeployment(
            touchTurnSession = TouchTurnSessionContext(
                sessionDate = "2026-05-22",
                status = TouchTurnCandleStatus.READY,
                candle = OhlcBar(open = 105.0, high = 106.0, low = 99.0, close = 104.0, time = barTime),
                marketZoneId = "America/New_York",
                rangeThreshold = 0.01,
                adr14 = 0.04
            )
        )
        val graph = TouchTurnPipelineUiMapper.graphForDeployment(
            instance = instance,
            brokerPositions = emptyList(),
            brokerOpenOrders = emptyList(),
            brokerFills = emptyList(),
            showLastSessionRecap = false,
            nowEpochMillis = barEnd - 1
        )!!
        assertEquals(TouchTurnPipelineNodeId.Bar, graph.activePath.last())
        assertEquals(
            TouchTurnBreadcrumbStepState.CURRENT,
            graph.nodes.first { it.id == TouchTurnPipelineNodeId.Bar }.state
        )
    }

    private fun ordersPlacedDeployment(barTime: String, now: Long): StrategyDeployment {
        val bar = OhlcBar(open = 105.0, high = 106.0, low = 99.0, close = 104.0, time = barTime)
        return runningDeployment(
            touchTurnSession = TouchTurnSessionContext(
                sessionDate = "2026-05-22",
                status = TouchTurnCandleStatus.READY,
                candle = bar,
                marketZoneId = "America/New_York",
                rangeThreshold = 0.01,
                adr14 = 0.04
            )
        ).withLiquidityEvaluatedIfClosed(
            enforceCloseConfirmation = false,
            nowEpochMillis = now
        ).withOrdersPlacedForSession(null)
    }

    private fun runningDeployment(touchTurnSession: TouchTurnSessionContext): StrategyDeployment =
        StrategyDeployment(
            id = "tt-pipeline",
            strategyType = StrategyType.TOUCH_AND_TURN_SCALPER,
            status = DeploymentStatus.RUNNING,
            symbol = "AAPL",
            maxDollars = 500,
            touchTurnSession = touchTurnSession
        ).onSessionStarted("2026-05-22", startedAt = "2026-05-22T09:30:00")

    private fun entryTrade() = SessionTrade(
        execId = "entry",
        orderId = 1,
        permId = 1L,
        parentOrderId = 0,
        side = "BUY",
        quantity = 1,
        price = 100.0,
        time = "2026-05-22T09:46:00"
    )

    private fun exitTrade() = SessionTrade(
        execId = "exit",
        orderId = 2,
        permId = 2L,
        parentOrderId = 1,
        side = "SELL",
        quantity = 1,
        price = 101.0,
        time = "2026-05-22T09:50:00",
        realizedPnL = 1.0
    )
}
