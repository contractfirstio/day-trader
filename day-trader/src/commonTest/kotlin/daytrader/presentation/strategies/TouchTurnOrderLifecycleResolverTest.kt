package daytrader.presentation.strategies

import daytrader.domain.TouchTurnCandleStatus
import daytrader.domain.TouchTurnMilestoneTimestamps
import daytrader.domain.TouchTurnSessionContext
import daytrader.domain.TouchTurnSessionOutcome
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TouchTurnOrderLifecycleResolverTest {
    @Test
    fun notPlaced_beforeBracketSubmit() {
        val lifecycle = TouchTurnOrderLifecycleResolver.resolve(
            session = session(ordersPlaced = false),
            hasOpenPosition = false,
            hasOpenOrders = false,
            inActiveTrade = false,
            sessionEnded = false,
            hasSessionTrades = false
        )
        assertEquals(TouchTurnOrderLifecyclePhase.NOT_PLACED, lifecycle.phase)
        assertTrue(lifecycle.showOrdersPreview)
        assertFalse(lifecycle.showLiveOrdersPanel)
        assertFalse(lifecycle.showLiveOrderChart)
    }

    @Test
    fun submittedPendingVisibility_whenSessionCommittedButBrokerFeedEmpty() {
        val lifecycle = TouchTurnOrderLifecycleResolver.resolve(
            session = session(ordersPlaced = true),
            hasOpenPosition = false,
            hasOpenOrders = false,
            inActiveTrade = false,
            sessionEnded = false,
            hasSessionTrades = false
        )
        assertEquals(TouchTurnOrderLifecyclePhase.SUBMITTED_PENDING_BROKER_VISIBILITY, lifecycle.phase)
        assertTrue(lifecycle.showLiveOrdersPanel)
        assertTrue(lifecycle.showLiveOrderChart)
        assertFalse(lifecycle.showOrdersPreview)
        assertTrue(lifecycle.statusMessage!!.contains("submitted"))
    }

    @Test
    fun awaitingEntry_whenBrokerReportsOpenOrders() {
        val lifecycle = TouchTurnOrderLifecycleResolver.resolve(
            session = session(ordersPlaced = true),
            hasOpenPosition = false,
            hasOpenOrders = true,
            inActiveTrade = false,
            sessionEnded = false,
            hasSessionTrades = false
        )
        assertEquals(TouchTurnOrderLifecyclePhase.AWAITING_ENTRY, lifecycle.phase)
        assertTrue(lifecycle.showLiveOrdersPanel)
        assertNull(lifecycle.statusMessage)
    }

    @Test
    fun inPosition_whenExecutionActive() {
        val lifecycle = TouchTurnOrderLifecycleResolver.resolve(
            session = session(ordersPlaced = true),
            hasOpenPosition = false,
            hasOpenOrders = false,
            inActiveTrade = true,
            sessionEnded = false,
            hasSessionTrades = false
        )
        assertEquals(TouchTurnOrderLifecyclePhase.IN_POSITION, lifecycle.phase)
        assertTrue(lifecycle.showLiveOrdersPanel)
    }

    @Test
    fun closedNoFill_afterStopWithoutBrokerActivity() {
        val lifecycle = TouchTurnOrderLifecycleResolver.resolve(
            session = session(ordersPlaced = true),
            hasOpenPosition = false,
            hasOpenOrders = false,
            inActiveTrade = false,
            sessionEnded = true,
            hasSessionTrades = false
        )
        assertEquals(TouchTurnOrderLifecyclePhase.CLOSED_NO_FILL, lifecycle.phase)
        assertTrue(lifecycle.showOrdersPreview)
        assertFalse(lifecycle.showLiveOrdersPanel)
    }

    private fun session(ordersPlaced: Boolean): TouchTurnSessionContext =
        TouchTurnSessionContext(
            sessionDate = "2026-06-01",
            status = TouchTurnCandleStatus.READY,
            ordersPlacedForSession = ordersPlaced,
            decisionOutcome = if (ordersPlaced) {
                TouchTurnSessionOutcome.TRADE_BRACKET_SUBMITTED
            } else {
                null
            },
            milestones = TouchTurnMilestoneTimestamps(
                ordersPlacedAt = if (ordersPlaced) "2026-06-01T10:00:00" else null
            )
        )
}
