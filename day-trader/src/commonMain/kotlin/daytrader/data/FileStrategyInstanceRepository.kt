package daytrader.data

import daytrader.data.persistence.JsonFileStore
import daytrader.data.persistence.StrategyInstancesDocument
import daytrader.domain.StrategyInstance
import daytrader.platform.AppFileSystem
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

class FileStrategyInstanceRepository(
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
) : StrategyInstanceRepository {
    private val _instances = MutableStateFlow(loadInitial())
    override val instances: StateFlow<List<StrategyInstance>> = _instances.asStateFlow()

    private var saveJob: Job? = null

    override fun add(instance: StrategyInstance) {
        _instances.update { it + instance }
        scheduleSave()
    }

    override fun update(id: String, transform: (StrategyInstance) -> StrategyInstance) {
        _instances.update { list ->
            list.map { if (it.id == id) transform(it) else it }
        }
        scheduleSave()
    }

    override fun remove(id: String) {
        _instances.update { it.filterNot { instance -> instance.id == id } }
        scheduleSave()
    }

    private fun loadInitial(): List<StrategyInstance> {
        AppFileSystem.ensureAppDataDirectory()
        val document = JsonFileStore.readStrategyInstances()
        if (document != null && document.instances.isNotEmpty()) {
            return document.instances
        }
        val seed = mockStrategyInstances()
        persistImmediately(seed)
        return seed
    }

    private fun scheduleSave() {
        saveJob?.cancel()
        saveJob = scope.launch {
            delay(SAVE_DEBOUNCE_MS)
            persistImmediately(_instances.value)
        }
    }

    private fun persistImmediately(instances: List<StrategyInstance>) {
        JsonFileStore.writeStrategyInstances(StrategyInstancesDocument(instances = instances))
    }

    companion object {
        private const val SAVE_DEBOUNCE_MS = 400L
    }
}
