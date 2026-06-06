package daytrader.domain

import kotlinx.serialization.Serializable

@Serializable
data class WatchlistPlanDiaryEntry(
    val id: String,
    val body: String,
    val createdAtEpochMs: Long,
    /** ISO local date (YYYY-MM-DD) when a reminder should start appearing. */
    val notifyOnDate: String? = null,
    val notificationDismissed: Boolean = false
) {
    val hasReminder: Boolean get() = !notifyOnDate.isNullOrBlank()
}

fun newWatchlistPlanDiaryEntryId(): String =
    "wpde-${kotlin.random.Random.nextLong().toULong().toString(16)}"

object WatchlistPlanDiaryNotifications {
    data class DueNotification(
        val watchlistId: String,
        val entryId: String,
        val symbol: String,
        val companyName: String,
        val planId: String,
        val planLabel: String,
        val diaryEntry: WatchlistPlanDiaryEntry
    )

    fun findDue(watchlists: List<Watchlist>, todayIso: String): List<DueNotification> =
        watchlists.flatMap { watchlist ->
            watchlist.entries.flatMap { entry ->
                entry.tradePlans.flatMap { plan ->
                    plan.diaryEntries
                        .filter { isDue(it, todayIso) }
                        .map { diaryEntry ->
                            DueNotification(
                                watchlistId = watchlist.id,
                                entryId = entry.id,
                                symbol = entry.symbol,
                                companyName = entry.companyName?.takeIf { it.isNotBlank() } ?: entry.symbol,
                                planId = plan.id,
                                planLabel = plan.label,
                                diaryEntry = diaryEntry
                            )
                        }
                }
            }
        }.sortedWith(
            compareBy<DueNotification> { it.diaryEntry.notifyOnDate.orEmpty() }
                .thenBy { it.diaryEntry.createdAtEpochMs }
        )

    fun isDue(entry: WatchlistPlanDiaryEntry, todayIso: String): Boolean {
        if (entry.notificationDismissed) return false
        val notifyDate = entry.notifyOnDate?.takeIf { it.isNotBlank() } ?: return false
        return notifyDate <= todayIso
    }

    fun pendingReminderCount(plan: WatchlistTradePlan, todayIso: String): Int =
        plan.diaryEntries.count { isDue(it, todayIso) }
}
