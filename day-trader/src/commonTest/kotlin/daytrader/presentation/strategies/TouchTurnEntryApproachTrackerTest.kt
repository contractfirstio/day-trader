package daytrader.presentation.strategies

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class TouchTurnEntryApproachTrackerTest {
    @Test
    fun record_keepsMinimumFillGapForSession() {
        val tracker = TouchTurnEntryApproachTracker()
        tracker.bindSession("session-a")
        tracker.record(fillGap = 0.12, fillPrice = 99.76)
        tracker.record(fillGap = 0.06, fillPrice = 99.70)
        tracker.record(fillGap = 0.10, fillPrice = 99.74)

        val snapshot = tracker.snapshot()
        assertEquals(0.06, snapshot?.gap)
        assertEquals(99.70, snapshot?.fillPrice)
    }

    @Test
    fun bindSession_resetsWhenSessionChanges() {
        val tracker = TouchTurnEntryApproachTracker()
        tracker.bindSession("session-a")
        tracker.record(fillGap = 0.04, fillPrice = 99.68)
        tracker.bindSession("session-b")

        assertNull(tracker.snapshot())
    }

    @Test
    fun record_updatesWhenPriceGetsCloser() {
        val tracker = TouchTurnEntryApproachTracker()
        tracker.bindSession("session-a")
        tracker.record(fillGap = 0.02, fillPrice = 99.66)
        tracker.record(fillGap = 0.0, fillPrice = 99.64)

        val snapshot = tracker.snapshot()
        assertEquals(0.0, snapshot?.gap)
        assertEquals(99.64, snapshot?.fillPrice)
    }
}
