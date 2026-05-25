package daytrader.domain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TouchTurnSessionOrdersTest {
    @Test
    fun sessionOrdersPlaced_trueWhenOrdersPlacedForSession() {
        val session = TouchTurnSessionContext(
            sessionDate = "2026-05-25",
            status = TouchTurnCandleStatus.READY,
            ordersPlacedForSession = true
        )
        assertTrue(session.sessionOrdersPlaced())
    }

    @Test
    fun sessionOrdersPlaced_trueWhenLegacyEntryOrdersPermitted() {
        val session = TouchTurnSessionContext(
            sessionDate = "2026-05-25",
            status = TouchTurnCandleStatus.READY,
            entryOrdersPermitted = true
        )
        assertTrue(session.sessionOrdersPlaced())
    }

    @Test
    fun sessionOrdersPlaced_falseWhenNoOrders() {
        val session = TouchTurnSessionContext(
            sessionDate = "2026-05-25",
            status = TouchTurnCandleStatus.READY,
            entryOrdersPermitted = false
        )
        assertFalse(session.sessionOrdersPlaced())
    }

    @Test
    fun withOrdersPlacedForSession_setsFlag() {
        val instance = defaultStrategyInstance(
            strategyType = StrategyType.TOUCH_AND_TURN_SCALPER,
            symbol = "AAPL",
            maxDollars = 500
        ).beginTouchTurnSession("2026-05-25")
        val updated = instance.withOrdersPlacedForSession()
        assertTrue(updated.touchTurnSession?.ordersPlacedForSession == true)
    }
}
