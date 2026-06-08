package daytrader.domain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class SessionStopSnapshotTest {
    @Test
    fun touchTurnSnapshot_capturesLiquidityOrdersPositionAndPnl() {
        val instance = defaultStrategyDeployment(
            strategyType = StrategyType.TOUCH_AND_TURN_SCALPER,
            symbol = "700",
            maxDollars = 500
        ).onSessionStarted("2026-05-22").copy(
            touchTurnSession = TouchTurnSessionContext(
                sessionDate = "2026-05-22",
                status = TouchTurnCandleStatus.READY,
                setup = TouchTurnBracketSetup(
                    range = 10.0,
                    rangeThreshold = 5.0,
                    isLiquidityCandle = true,
                    candleColor = FirstCandleColor.GREEN,
                    side = TouchTurnTradeSide.SHORT,
                    entry = 400.0,
                    stopLoss = 405.0,
                    takeProfit = 395.0
                ),
                entryOrdersPermitted = true,
                ordersPlacedForSession = true
            )
        )

        val snapshot = instance.resolveStopSnapshot(
            hadOpenBrokerPosition = true,
            brokerUnrealizedPnL = 42.5
        )

        assertEquals(true, snapshot.hadLiquidityCandle)
        assertEquals(true, snapshot.ordersPlacedForCandle)
        assertEquals(true, snapshot.positionOpened)
        assertEquals(42.5, snapshot.sessionPnL)
    }

    @Test
    fun touchTurnSnapshot_noLiquidity_noOrders() {
        val instance = defaultStrategyDeployment(
            strategyType = StrategyType.TOUCH_AND_TURN_SCALPER,
            symbol = "700",
            maxDollars = 500
        ).copy(
            touchTurnSession = TouchTurnSessionContext(
                sessionDate = "2026-05-22",
                status = TouchTurnCandleStatus.READY,
                setup = TouchTurnBracketSetup(
                    range = 2.0,
                    rangeThreshold = 5.0,
                    isLiquidityCandle = false,
                    candleColor = FirstCandleColor.RED,
                    side = TouchTurnTradeSide.LONG,
                    entry = 400.0,
                    stopLoss = 395.0,
                    takeProfit = 405.0
                ),
                entryOrdersPermitted = false
            )
        )

        val snapshot = instance.resolveStopSnapshot(
            hadOpenBrokerPosition = false,
            brokerUnrealizedPnL = null
        )

        assertEquals(false, snapshot.hadLiquidityCandle)
        assertEquals(false, snapshot.ordersPlacedForCandle)
        assertEquals(false, snapshot.positionOpened)
        assertEquals(0.0, snapshot.sessionPnL)
    }

    @Test
    fun onSessionStopped_persistsTouchTurnMilestonesOnSessionHistory() {
        val milestones = TouchTurnMilestoneTimestamps(
            startingSessionAt = "2026-05-22T09:30:00",
            dataReadyAt = "2026-05-22T09:30:08",
            barClosedAt = "2026-05-22T09:45:01",
            liquidityEvaluatedAt = "2026-05-22T09:45:02",
            ordersPlacedAt = "2026-05-22T09:45:03",
            closingSessionAt = "2026-05-22T11:00:00"
        )
        val instance = defaultStrategyDeployment(
            strategyType = StrategyType.TOUCH_AND_TURN_SCALPER,
            symbol = "700",
            maxDollars = 500
        ).onSessionStarted("2026-05-22").copy(
            touchTurnSession = TouchTurnSessionContext(
                sessionDate = "2026-05-22",
                status = TouchTurnCandleStatus.READY,
                milestones = milestones
            )
        )

        val stopped = instance.onSessionStopped()

        assertEquals(milestones, stopped.sessionHistory.single().touchTurnMilestones)
        assertNull(stopped.touchTurnSession)
    }

    @Test
    fun onSessionStopped_persistsSnapshotOnPerformanceRow() {
        val instance = defaultStrategyDeployment(
            strategyType = StrategyType.TOUCH_AND_TURN_SCALPER,
            symbol = "700",
            maxDollars = 500
        ).onSessionStarted("2026-05-22")

        val stopped = instance.onSessionStopped(
            snapshot = SessionStopSnapshot(
                hadLiquidityCandle = true,
                ordersPlacedForCandle = true,
                positionOpened = false,
                sessionPnL = 0.0,
                trades = 0
            )
        )

        val closed = stopped.sessionHistory.single()
        assertEquals(true, closed.hadLiquidityCandle)
        assertEquals(true, closed.ordersPlacedForCandle)
        assertEquals(false, closed.positionOpened)
        assertEquals(0.0, closed.pnl)
    }

    @Test
    fun quickFlipSnapshot_hasNoTouchTurnFields() {
        val instance = defaultStrategyDeployment(
            strategyType = StrategyType.QUICK_FLIP_SCALPER,
            symbol = "SPY",
            maxDollars = 250
        )

        val snapshot = instance.resolveStopSnapshot(
            hadOpenBrokerPosition = false,
            brokerUnrealizedPnL = null
        )

        assertNull(snapshot.hadLiquidityCandle)
        assertNull(snapshot.ordersPlacedForCandle)
    }
}
