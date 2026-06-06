package daytrader.data

import daytrader.domain.WatchlistStrategyLinks

object WatchlistStrategyLinkSync {
    fun removeDeploymentFromAllWatchlists(repository: WatchlistRepository, deploymentId: String) {
        var changed = false
        repository.watchlists.value.forEach { watchlist ->
            val updatedEntries = watchlist.entries.map { entry ->
                if (deploymentId !in entry.strategyDeploymentIds) {
                    entry
                } else {
                    changed = true
                    entry.copy(
                        strategyDeploymentIds = WatchlistStrategyLinks.removeDeploymentId(
                            entry.strategyDeploymentIds,
                            deploymentId
                        )
                    )
                }
            }
            if (updatedEntries != watchlist.entries) {
                repository.updateWatchlist(watchlist.id) { current ->
                    current.copy(entries = updatedEntries)
                }
            }
        }
        if (changed) {
            repository.flushPersistence()
        }
    }
}
