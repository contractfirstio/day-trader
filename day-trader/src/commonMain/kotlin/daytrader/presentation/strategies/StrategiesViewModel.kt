package daytrader.presentation.strategies

import daytrader.data.StrategiesAppState
import daytrader.data.StrategiesAppStateRepository
import daytrader.data.StrategyInstanceRepository
import daytrader.domain.InstanceStatus
import daytrader.domain.StrategyInstance
import daytrader.domain.StrategyType
import daytrader.domain.defaultStrategyInstance
import daytrader.domain.duplicateStrategyInstance
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.update

class StrategiesViewModel(
    private val repository: StrategyInstanceRepository,
    private val appStateRepository: StrategiesAppStateRepository
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private var appState = StrategiesAppState()
    private var showAddDialog = false
    private var instances: List<StrategyInstance> = emptyList()

    private val _uiState = MutableStateFlow(StrategiesUiState())
    val uiState: StateFlow<StrategiesUiState> = _uiState.asStateFlow()

    init {
        appStateRepository.state
            .onEach { state ->
                appState = state
                emitUiState()
            }
            .launchIn(scope)

        repository.instances
            .onEach { list ->
                instances = list
                reconcileSelectedInstance(list)
            }
            .launchIn(scope)
    }

    fun onSearchChange(query: String) {
        appStateRepository.update { it.copy(searchQuery = query) }
    }

    fun onInstanceFilterChange(filter: InstanceFilter) {
        appStateRepository.update { it.copy(instanceFilter = filter) }
    }

    fun onStrategyTypeFilterChange(type: StrategyType?) {
        appStateRepository.update { it.copy(strategyTypeFilter = type) }
    }

    fun onSelectInstance(id: String) {
        appStateRepository.update {
            it.copy(
                selectedInstanceId = id,
                detailTab = StrategyDetailTab.CONFIGURATION
            )
        }
    }

    fun onDetailTabChange(tab: StrategyDetailTab) {
        appStateRepository.update { it.copy(detailTab = tab) }
    }

    fun onShowAddDialog() {
        showAddDialog = true
        emitUiState()
    }

    fun onDismissAddDialog() {
        showAddDialog = false
        emitUiState()
    }

    fun onCreateInstance(strategyType: StrategyType, symbol: String) {
        if (symbol.isBlank()) return
        val instance = defaultStrategyInstance(strategyType = strategyType, symbol = symbol)
        repository.add(instance)
        appStateRepository.update {
            it.copy(
                selectedInstanceId = instance.id,
                detailTab = StrategyDetailTab.CONFIGURATION
            )
        }
        showAddDialog = false
        emitUiState()
    }

    fun onToggleRun(id: String) {
        repository.update(id) { instance ->
            val nextStatus = if (instance.status == InstanceStatus.RUNNING) {
                InstanceStatus.STOPPED
            } else {
                InstanceStatus.RUNNING
            }
            instance.copy(status = nextStatus)
        }
    }

    fun onUpdateInstance(id: String, transform: (StrategyInstance) -> StrategyInstance) {
        repository.update(id, transform)
    }

    fun onDuplicateSelected() {
        val selected = instances.find { it.id == appState.selectedInstanceId } ?: return
        val copy = duplicateStrategyInstance(selected)
        repository.add(copy)
        appStateRepository.update { it.copy(selectedInstanceId = copy.id) }
    }

    fun onDeleteSelected() {
        val id = appState.selectedInstanceId ?: return
        repository.remove(id)
    }

    private fun reconcileSelectedInstance(list: List<StrategyInstance>) {
        val current = appState.selectedInstanceId
        val validSelection = when {
            current != null && list.any { it.id == current } -> current
            else -> list.firstOrNull()?.id
        }
        if (validSelection != current) {
            appStateRepository.update { it.copy(selectedInstanceId = validSelection) }
        } else {
            emitUiState()
        }
    }

    private fun emitUiState() {
        val state = appState
        val filtered = instances.filter { instance ->
            val matchesSearch = state.searchQuery.isBlank() ||
                instance.name.contains(state.searchQuery, ignoreCase = true) ||
                instance.symbol.contains(state.searchQuery, ignoreCase = true)
            val matchesFilter = when (state.instanceFilter) {
                InstanceFilter.ALL -> true
                InstanceFilter.RUNNING -> instance.status == InstanceStatus.RUNNING
                InstanceFilter.STOPPED -> instance.status == InstanceStatus.STOPPED
            }
            val matchesStrategyType =
                state.strategyTypeFilter == null || instance.strategyType == state.strategyTypeFilter
            matchesSearch && matchesFilter && matchesStrategyType
        }

        _uiState.update {
            StrategiesUiState(
                filteredRows = filtered.map(StrategyUiMapper::toRowUi),
                filteredCount = filtered.size,
                selectedInstance = instances.find { it.id == state.selectedInstanceId },
                searchQuery = state.searchQuery,
                instanceFilter = state.instanceFilter,
                strategyTypeFilter = state.strategyTypeFilter,
                detailTab = state.detailTab,
                showAddDialog = showAddDialog,
                selectedInstanceId = state.selectedInstanceId
            )
        }
    }
}
