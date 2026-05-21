package daytrader.data

import daytrader.data.persistence.DebouncedFileWriter
import daytrader.data.persistence.JsonFileStore
import daytrader.data.persistence.LegacyDataCleanup
import daytrader.data.persistence.LegacyStrategiesScreenPersistence
import daytrader.data.persistence.StrategiesAppStatePersistence
import daytrader.domain.StrategyType
import daytrader.platform.AppFileSystem
import daytrader.presentation.strategies.InstanceFilter
import daytrader.presentation.strategies.StrategyDetailTab
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

data class StrategiesAppState(
    val selectedInstanceId: String? = null,
    val searchQuery: String = "",
    val instanceFilter: InstanceFilter = InstanceFilter.ALL,
    val strategyTypeFilter: StrategyType? = null,
    val detailTab: StrategyDetailTab = StrategyDetailTab.CONFIGURATION
)

interface StrategiesAppStateRepository {
    val state: StateFlow<StrategiesAppState>
    fun update(transform: (StrategiesAppState) -> StrategiesAppState)
}

class FileStrategiesAppStateRepository(
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
) : StrategiesAppStateRepository {
    private val writer = DebouncedFileWriter<StrategiesAppState>(scope) { state ->
        JsonFileStore.writeStrategiesScreen(StrategiesAppStatePersistence.toDocument(state))
        LegacyDataCleanup.removeOrphanedLegacyFiles()
    }

    private val _state = MutableStateFlow(loadInitial())
    override val state: StateFlow<StrategiesAppState> = _state.asStateFlow()

    override fun update(transform: (StrategiesAppState) -> StrategiesAppState) {
        _state.update(transform)
        writer.schedule(_state.value)
    }

    private fun loadInitial(): StrategiesAppState {
        AppFileSystem.ensureAppDataDirectory()
        val fromNew = JsonFileStore.readStrategiesScreen()?.let(StrategiesAppStatePersistence::fromDocument)
        if (fromNew != null) return fromNew

        val fromLegacyDoc = LegacyStrategiesScreenPersistence.load()
        if (fromLegacyDoc != null) {
            val state = StrategiesAppStatePersistence.fromDocument(fromLegacyDoc)
            writer.persistNow(state)
            return state
        }

        return StrategiesAppState()
    }
}
