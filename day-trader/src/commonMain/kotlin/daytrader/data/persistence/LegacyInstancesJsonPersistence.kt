package daytrader.data.persistence

import daytrader.domain.StrategyDeployment

internal object LegacyInstancesJsonPersistence {
    fun load(): List<StrategyDeployment>? {
        val document = JsonFileStore.readLegacyInstancesJson() ?: return null
        if (document.instances.isEmpty()) return null
        return document.instances.map(::migrateDeployment)
    }

    private fun migrateDeployment(legacy: LegacyInstancesJsonRecord): StrategyDeployment =
        DeploymentPersistence.toDomain(
            DeploymentRecord(
                id = legacy.id,
                strategy = legacy.strategy,
                status = legacy.status,
                configuration = legacy.configuration,
                live = legacy.live,
                sessionHistory = legacy.performance,
                touchTurnSession = legacy.touchTurnSession
            )
        )
}
