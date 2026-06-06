package daytrader.ui

import daytrader.presentation.strategies.StrategyDeploymentAddPrefill
import daytrader.presentation.watchlist.WatchlistStrategyCreateRequest

class WatchlistStrategyCreateBridge {
    var pendingLinkEntryId: String? = null
    var navigateToStrategies: () -> Unit = {}
    var showStrategyAddDialog: (StrategyDeploymentAddPrefill) -> Unit = {}
    var linkDeploymentToWatchlistEntry: (String, String) -> Unit = { _, _ -> }

    fun requestCreate(request: WatchlistStrategyCreateRequest) {
        pendingLinkEntryId = request.entryId
        navigateToStrategies()
        showStrategyAddDialog(request.toAddPrefill())
    }

    fun onDeploymentCreated(deploymentId: String) {
        val entryId = pendingLinkEntryId ?: return
        linkDeploymentToWatchlistEntry(entryId, deploymentId)
        pendingLinkEntryId = null
    }
}
