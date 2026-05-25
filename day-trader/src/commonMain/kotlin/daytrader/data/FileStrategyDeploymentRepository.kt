package daytrader.data

import daytrader.data.persistence.DebouncedFileWriter
import daytrader.data.persistence.DeploymentPersistence
import daytrader.data.persistence.DeploymentsDocument
import daytrader.data.persistence.JsonFileStore
import daytrader.data.persistence.LegacyDataCleanup
import daytrader.data.persistence.LegacyDeploymentPersistence
import daytrader.data.persistence.LegacyInstancesJsonPersistence
import daytrader.domain.StrategyDeployment
import daytrader.gateway.BrokerKind
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

    private val _deployments = MutableStateFlow(loadInitial())
    override val deployments: StateFlow<List<StrategyDeployment>> = _deployments.asStateFlow()

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
        _deployments.update { it.filterNot { deployment -> deployment.id != id } }
        writer.schedule(_deployments.value)
    }

    override fun flushPersistence() {
        writer.persistNow(_deployments.value)
    }

    private fun loadInitial(): List<StrategyDeployment> {
        AppFileSystem.ensureAppDataDirectory()
        val fromNew = JsonFileStore.readDeployments()
            ?.deployments
            ?.map(DeploymentPersistence::toDomain)
            ?.takeIf { it.isNotEmpty() }
        if (fromNew != null) {
            LegacyDataCleanup.removeOrphanedLegacyFiles()
            return fromNew
        }

        val fromInstancesJson = LegacyInstancesJsonPersistence.load()?.takeIf { it.isNotEmpty() }
        if (fromInstancesJson != null) {
            writer.persistNow(fromInstancesJson)
            return fromInstancesJson
        }

        val fromLegacy = LegacyDeploymentPersistence.load()?.takeIf { it.isNotEmpty() }
        if (fromLegacy != null) {
            writer.persistNow(fromLegacy)
            return fromLegacy
        }

        if (AppFileSystem.currentDataScope() == BrokerKind.EMULATOR_LIVE_IB_MARKET_DATA) {
            return emptyList()
        }

        val seed = mockStrategyDeployments()
        writer.persistNow(seed)
        return seed
    }

    private fun persistDeployments(deployments: List<StrategyDeployment>) {
        val document = DeploymentsDocument(deployments.map(DeploymentPersistence::toRecord))
        JsonFileStore.writeDeployments(document)
        LegacyDataCleanup.removeOrphanedLegacyFiles()
    }
}
