package daytrader.data

import daytrader.data.persistence.DebouncedFileWriter
import daytrader.data.persistence.InstancePersistence
import daytrader.data.persistence.InstancesDocument
import daytrader.data.persistence.JsonFileStore
import daytrader.data.persistence.LegacyDataCleanup
import daytrader.data.persistence.LegacyInstancePersistence
import daytrader.domain.StrategyInstance
import daytrader.platform.AppFileSystem
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

class FileStrategyInstanceRepository(
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
) : StrategyInstanceRepository {
    private val writer = DebouncedFileWriter<List<StrategyInstance>>(scope) { instances ->
        persistInstances(instances)
    }

    private val _instances = MutableStateFlow(loadInitial())
    override val instances: StateFlow<List<StrategyInstance>> = _instances.asStateFlow()

    override fun add(instance: StrategyInstance) {
        _instances.update { it + instance }
        writer.schedule(_instances.value)
    }

    override fun update(id: String, transform: (StrategyInstance) -> StrategyInstance) {
        _instances.update { list ->
            list.map { if (it.id == id) transform(it) else it }
        }
        writer.schedule(_instances.value)
    }

    override fun remove(id: String) {
        _instances.update { it.filterNot { instance -> instance.id == id } }
        writer.schedule(_instances.value)
    }

    override fun flushPersistence() {
        writer.persistNow(_instances.value)
    }

    private fun loadInitial(): List<StrategyInstance> {
        AppFileSystem.ensureAppDataDirectory()
        val fromNew = JsonFileStore.readInstances()
            ?.instances
            ?.map(InstancePersistence::toDomain)
            ?.takeIf { it.isNotEmpty() }
        if (fromNew != null) {
            LegacyDataCleanup.removeOrphanedLegacyFiles()
            return fromNew
        }

        val fromLegacy = LegacyInstancePersistence.load()?.takeIf { it.isNotEmpty() }
        if (fromLegacy != null) {
            writer.persistNow(fromLegacy)
            return fromLegacy
        }

        val seed = mockStrategyInstances()
        writer.persistNow(seed)
        return seed
    }

    private fun persistInstances(instances: List<StrategyInstance>) {
        val document = InstancesDocument(instances.map(InstancePersistence::toRecord))
        JsonFileStore.writeInstances(document)
        LegacyDataCleanup.removeOrphanedLegacyFiles()
    }
}
