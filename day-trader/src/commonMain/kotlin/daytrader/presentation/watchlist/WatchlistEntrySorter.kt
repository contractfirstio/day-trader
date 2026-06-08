package daytrader.presentation.watchlist

import daytrader.domain.StrategyDeployment
import daytrader.domain.Watchlist
import daytrader.domain.WatchlistEntry
import daytrader.domain.WatchlistEntryProximityEvaluator
import daytrader.domain.WatchlistLabel
import daytrader.domain.WatchlistLabels
import daytrader.domain.WatchlistProximityStatus
import daytrader.domain.WatchlistStrategyLinks
import daytrader.presentation.markets.marketLabelForZone

object WatchlistEntrySorter {
    fun sortedEntries(
        entries: List<WatchlistEntry>,
        column: WatchlistSortColumn,
        direction: WatchlistSortDirection,
        watchlist: Watchlist?,
        deployments: List<StrategyDeployment>,
        nearSummaries: Map<String, String> = emptyMap()
    ): List<WatchlistEntry> {
        val labels = watchlist?.labels.orEmpty()
        val comparator = comparator(column, labels, deployments, nearSummaries)
        return if (direction == WatchlistSortDirection.DESCENDING) {
            entries.sortedWith(comparator.reversed())
        } else {
            entries.sortedWith(comparator)
        }
    }

    private fun comparator(
        column: WatchlistSortColumn,
        labels: List<WatchlistLabel>,
        deployments: List<StrategyDeployment>,
        nearSummaries: Map<String, String>
    ): Comparator<WatchlistEntry> = when (column) {
        WatchlistSortColumn.COMPANY -> compareBy<WatchlistEntry> { entry ->
            (entry.companyName?.takeIf { name -> name.isNotBlank() } ?: entry.symbol).lowercase()
        }.thenBy { it.symbol.lowercase() }

        WatchlistSortColumn.SYMBOL -> compareBy { it.symbol.lowercase() }

        WatchlistSortColumn.MARKET -> compareBy<WatchlistEntry> { entry ->
            marketLabelForZone(entry.marketZoneId).lowercase()
        }.thenBy { it.symbol.lowercase() }

        WatchlistSortColumn.GROUPS -> compareBy<WatchlistEntry> { entry ->
            groupsSortKey(entry, labels)
        }.thenBy { it.symbol.lowercase() }

        WatchlistSortColumn.STRATEGIES -> compareBy<WatchlistEntry> { entry ->
            strategiesSortKey(entry, deployments)
        }.thenBy { it.symbol.lowercase() }

        WatchlistSortColumn.LAST -> compareBy<WatchlistEntry> { entry ->
            entry.lastScannedPrice ?: Double.NEGATIVE_INFINITY
        }.thenBy { it.symbol.lowercase() }

        WatchlistSortColumn.REVERSAL_SCORE -> compareBy<WatchlistEntry> { entry ->
            entry.reversalScore ?: Int.MIN_VALUE
        }.thenBy { it.symbol.lowercase() }

        WatchlistSortColumn.STATUS -> compareBy<WatchlistEntry> { entry ->
            statusRank(WatchlistEntryProximityEvaluator.entryStatus(entry, entry.lastScannedPrice))
        }.thenBy { it.symbol.lowercase() }

        WatchlistSortColumn.PLANS -> compareBy<WatchlistEntry> { entry ->
            WatchlistUiMapper.plansSortKey(entry, nearSummaries[entry.id]).lowercase()
        }.thenBy { it.symbol.lowercase() }
    }

    private fun groupsSortKey(entry: WatchlistEntry, labels: List<WatchlistLabel>): String =
        WatchlistLabels.resolveLabels(entry.labelIds, labels)
            .joinToString(separator = ", ") { it.name }
            .lowercase()
            .ifBlank { "\uFFFF" }

    private fun strategiesSortKey(entry: WatchlistEntry, deployments: List<StrategyDeployment>): String =
        WatchlistStrategyLinks.resolve(entry.strategyDeploymentIds, deployments)
            .joinToString(separator = ", ") { WatchlistStrategyLinks.displayName(it) }
            .lowercase()
            .ifBlank { "\uFFFF" }

    private fun statusRank(status: WatchlistProximityStatus): Int = when (status) {
        WatchlistProximityStatus.NEAR -> 0
        WatchlistProximityStatus.CLEAR -> 1
        WatchlistProximityStatus.NO_DATA -> 2
        WatchlistProximityStatus.NOT_SCANNED -> 3
    }
}
