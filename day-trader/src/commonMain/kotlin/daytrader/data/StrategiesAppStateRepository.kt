package daytrader.data

import daytrader.data.persistence.JsonFileStore
import daytrader.data.persistence.StrategiesAppStateDocument
import daytrader.domain.StrategyType
import daytrader.platform.AppFileSystem
import daytrader.presentation.strategies.InstanceFilter
import daytrader.presentation.strategies.StrategyDetailTab
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
    private val _state = MutableStateFlow(loadInitial())
    override val state: StateFlow<StrategiesAppState> = _state.asStateFlow()

    private var saveJob: Job? = null

    override fun update(transform: (StrategiesAppState) -> StrategiesAppState) {
        _state.update(transform)
        scheduleSave()
    }

    private fun loadInitial(): StrategiesAppState {
        AppFileSystem.ensureAppDataDirectory()
        val document = JsonFileStore.readStrategiesAppState()
            ?: return StrategiesAppState()
        return StrategiesAppState(
            selectedInstanceId = document.selectedInstanceId,
            searchQuery = document.searchQuery,
            instanceFilter = runCatching { InstanceFilter.valueOf(document.instanceFilter) }
                .getOrDefault(InstanceFilter.ALL),
            strategyTypeFilter = document.strategyTypeFilter,
            detailTab = when (document.detailTab) {
                "ACTIVITY" -> StrategyDetailTab.LIVE
                else -> runCatching { StrategyDetailTab.valueOf(document.detailTab) }
                    .getOrDefault(StrategyDetailTab.CONFIGURATION)
            }
        )
    }

    private fun scheduleSave() {
        saveJob?.cancel()
        saveJob = scope.launch {
            delay(SAVE_DEBOUNCE_MS)
            persistImmediately(_state.value)
        }
    }

    private fun persistImmediately(state: StrategiesAppState) {
        JsonFileStore.writeStrategiesAppState(
            StrategiesAppStateDocument(
                selectedInstanceId = state.selectedInstanceId,
                searchQuery = state.searchQuery,
                instanceFilter = state.instanceFilter.name,
                strategyTypeFilter = state.strategyTypeFilter,
                detailTab = state.detailTab.name
            )
        )
    }

    companion object {
        private const val SAVE_DEBOUNCE_MS = 400L
    }
}
