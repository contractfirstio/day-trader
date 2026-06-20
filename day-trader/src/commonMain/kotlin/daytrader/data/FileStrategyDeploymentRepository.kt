package daytrader.data

import daytrader.data.persistence.DebouncedFileWriter
import daytrader.data.persistence.DeferredFileHydration
import daytrader.data.persistence.DeploymentPersistence
import daytrader.data.persistence.DeploymentsDocument
import daytrader.data.persistence.JsonFileStore
import daytrader.data.persistence.LegacyDataCleanup
import daytrader.data.persistence.LegacyDeploymentPersistence
import daytrader.data.persistence.LegacyInstancesJsonPersistence
import daytrader.data.persistence.launchDeferredFileHydration
import daytrader.domain.StrategyDeployment
import daytrader.platform.AppFileSystem
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

class FileStrategyDeploymentRepository(
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
) : StrategyDeploymentRepository {
    private val writer = DebouncedFileWriter<List<StrategyDeployment>>(scope) { deployments ->
        persistDeployments(deployments)
    }
    private val hydration = DeferredFileHydration()

    private val _deployments = MutableStateFlow<List<StrategyDeployment>>(emptyList())
    override val deployments: StateFlow<List<StrategyDeployment>> = _deployments.asStateFlow()

    init {
        scope.launchDeferredFileHydration(hydration) {
            _deployments.value = loadInitial()
        }
    }

    suspend fun awaitHydrated() {
        hydration.awaitComplete()
    }

    override fun add(deployment: StrategyDeployment) {
        _deployments.update { it + deployment }
        writer.schedule(_deployments.value)
    }

    override fun update(id: String, transform: (StrategyDeployment) -> StrategyDeployment) {
        _deployments.update { list ->
            list.map { if (it.id == id) transform(it) else it }
        }
        writer.schedule(_deployments.value)
    }

    override fun remove(id: String) {
        _deployments.update { it.filterNot { deployment -> deployment.id == id } }
        writer.schedule(_deployments.value)
    }

    override fun flushPersistence() {
        writer.persistNow(_deployments.value)
    }

    private fun loadInitial(): List<StrategyDeployment> {
        AppFileSystem.ensureAppDataDirectory()
        val document = JsonFileStore.readDeployments()
        if (document != null) {
            if (document.deployments.isNotEmpty()) {
                LegacyDataCleanup.removeOrphanedLegacyFiles()
            }
            val brokerKind = AppFileSystem.currentDataScope()
            val loaded = document.deployments.map(DeploymentPersistence::toDomain)
            val normalized = document.deployments.map { record ->
                DeploymentLoadNormalizer.normalize(
                    deployment = DeploymentPersistence.toDomain(record),
                    hadPersistedTouchTurnRules = record.configuration.touchTurnRules != null,
                    brokerKind = brokerKind
                )
            }
            if (normalized != loaded) {
                writer.persistNow(normalized)
            }
            return normalized
        }

        val fromLegacy = LegacyDeploymentPersistence.load()
            ?: LegacyInstancesJsonPersistence.load()
        if (fromLegacy != null) {
            val brokerKind = AppFileSystem.currentDataScope()
            val normalized = DeploymentLoadNormalizer.normalizeLegacy(fromLegacy, brokerKind)
            writer.persistNow(normalized)
            LegacyDataCleanup.removeOrphanedLegacyFiles()
            return normalized
        }

        return emptyList()
    }

    private fun persistDeployments(deployments: List<StrategyDeployment>) {
        val document = DeploymentsDocument(deployments.map(DeploymentPersistence::toRecord))
        JsonFileStore.writeDeployments(document)
        LegacyDataCleanup.removeOrphanedLegacyFiles()
    }
}
