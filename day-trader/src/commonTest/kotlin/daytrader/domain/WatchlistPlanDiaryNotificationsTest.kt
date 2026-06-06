package daytrader.domain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class WatchlistPlanDiaryNotificationsTest {
    @Test
    fun isDue_whenNotifyDateReachedAndNotDismissed() {
        val entry = WatchlistPlanDiaryEntry(
            id = "d1",
            body = "Check earnings reaction",
            createdAtEpochMs = 1L,
            notifyOnDate = "2026-06-01"
        )
        assertTrue(WatchlistPlanDiaryNotifications.isDue(entry, "2026-06-01"))
        assertTrue(WatchlistPlanDiaryNotifications.isDue(entry, "2026-06-06"))
        assertFalse(WatchlistPlanDiaryNotifications.isDue(entry, "2026-05-31"))
    }

    @Test
    fun isDue_ignoredWhenDismissedOrNoDate() {
        val dismissed = WatchlistPlanDiaryEntry(
            id = "d1",
            body = "Done",
            createdAtEpochMs = 1L,
            notifyOnDate = "2026-06-01",
            notificationDismissed = true
        )
        val noDate = WatchlistPlanDiaryEntry(
            id = "d2",
            body = "Note only",
            createdAtEpochMs = 1L
        )
        assertFalse(WatchlistPlanDiaryNotifications.isDue(dismissed, "2026-06-06"))
        assertFalse(WatchlistPlanDiaryNotifications.isDue(noDate, "2026-06-06"))
    }

    @Test
    fun findDue_collectsAcrossWatchlists() {
        val plan = WatchlistTradePlan(
            id = "plan-a",
            label = "Plan A",
            diaryEntries = listOf(
                WatchlistPlanDiaryEntry(
                    id = "d1",
                    body = "Reminder",
                    createdAtEpochMs = 1L,
                    notifyOnDate = "2026-06-01"
                )
            )
        )
        val entry = newWatchlistEntry(
            symbol = "AAPL",
            marketZoneId = "America/New_York",
            currencyCode = "USD",
            companyName = "Apple",
            instrument = null
        ).copy(tradePlans = listOf(plan))
        val watchlists = listOf(defaultWatchlist().copy(entries = listOf(entry)))

        val due = WatchlistPlanDiaryNotifications.findDue(watchlists, "2026-06-06")
        assertEquals(1, due.size)
        assertEquals("d1", due.single().diaryEntry.id)
    }
}
