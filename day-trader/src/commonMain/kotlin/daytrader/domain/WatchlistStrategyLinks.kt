package daytrader.domain

object WatchlistStrategyLinks {
    fun displayName(deployment: StrategyDeployment): String =
        instanceDisplayName(deployment.strategyType, deployment.symbol)

    fun resolve(deploymentIds: List<String>, deployments: List<StrategyDeployment>): List<StrategyDeployment> {
        if (deploymentIds.isEmpty() || deployments.isEmpty()) return emptyList()
        val byId = deployments.associateBy { it.id }
        return deploymentIds.mapNotNull { byId[it] }
    }

    fun available(deployments: List<StrategyDeployment>, assignedIds: List<String>): List<StrategyDeployment> =
        deployments
            .filterNot { assignedIds.contains(it.id) }
            .sortedBy { displayName(it).lowercase() }

    fun mergeDeploymentId(existing: List<String>, deploymentId: String): List<String> =
        if (existing.contains(deploymentId)) existing else existing + deploymentId

    fun removeDeploymentId(existing: List<String>, deploymentId: String): List<String> =
        existing.filterNot { it == deploymentId }

    fun remapAssignedIds(
        assignedIds: List<String>,
        deployments: List<StrategyDeployment>
    ): List<String> {
        if (assignedIds.isEmpty()) return emptyList()
        val validIds = deployments.map { it.id }.toSet()
        return assignedIds.filter { it in validIds }.distinct()
    }

    fun entryHasStrategy(entry: WatchlistEntry, deploymentId: String): Boolean =
        entry.strategyDeploymentIds.contains(deploymentId)

    fun entryHasStrategyType(
        entry: WatchlistEntry,
        strategyType: StrategyType,
        deployments: List<StrategyDeployment>
    ): Boolean = resolve(entry.strategyDeploymentIds, deployments).any { it.strategyType == strategyType }

    fun countForStrategy(entries: List<WatchlistEntry>, deploymentId: String): Int =
        entries.count { entryHasStrategy(it, deploymentId) }

    fun countForStrategyType(
        entries: List<WatchlistEntry>,
        strategyType: StrategyType,
        deployments: List<StrategyDeployment>
    ): Int = entries.count { entryHasStrategyType(it, strategyType, deployments) }

    fun linkedStrategyTypes(
        entries: List<WatchlistEntry>,
        deployments: List<StrategyDeployment>
    ): List<StrategyType> = entries
        .flatMap { entry -> resolve(entry.strategyDeploymentIds, deployments).map { it.strategyType } }
        .distinct()

    fun countUnassigned(entries: List<WatchlistEntry>): Int =
        entries.count { it.strategyDeploymentIds.isEmpty() }
}
