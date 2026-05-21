package daytrader.presentation.strategies

import daytrader.data.StrategiesAppState
import daytrader.data.StrategiesAppStateRepository
import daytrader.data.StrategyCatalog
import daytrader.data.StrategyInstanceRepository
import daytrader.data.withDemoLiveExecutionOnStart
import daytrader.domain.StrategyInstance
import daytrader.domain.StrategyType
import daytrader.domain.InstanceStatus
import daytrader.domain.defaultStrategyInstance
import daytrader.domain.duplicateStrategyInstance
import daytrader.domain.instanceDisplayName
import daytrader.domain.onRunStarted
import daytrader.domain.onRunStopped
import daytrader.domain.syncInProgressRun
import daytrader.platform.currentSessionDateIso
import daytrader.presentation.positions.SortDirection
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
    private var runSortColumn = RunSortColumn.DATE
    private var runSortDirection = SortDirection.DESCENDING

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

    fun onCreateInstance(strategyType: StrategyType, symbol: String, maxDollars: Int) {
        if (symbol.isBlank() || maxDollars <= 0) return
        val instance = defaultStrategyInstance(
            strategyType = strategyType,
            symbol = symbol,
            maxDollars = maxDollars
        )
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
        val sessionDate = currentSessionDateIso()
        val wasRunning = instances.find { it.id == id }?.status == InstanceStatus.RUNNING
        repository.update(id) { instance ->
            if (instance.status == InstanceStatus.RUNNING) {
                instance.onRunStopped(sessionDate)
            } else {
                instance.onRunStarted(sessionDate).withDemoLiveExecutionOnStart()
            }
        }
        if (!wasRunning) {
            appStateRepository.update {
                it.copy(selectedInstanceId = id, detailTab = StrategyDetailTab.LIVE)
            }
        }
    }

    fun onRunHeaderClick(column: RunSortColumn) {
        if (runSortColumn == column) {
            runSortDirection = if (runSortDirection == SortDirection.ASCENDING) {
                SortDirection.DESCENDING
            } else {
                SortDirection.ASCENDING
            }
        } else {
            runSortColumn = column
            runSortDirection = SortDirection.DESCENDING
        }
        emitUiState()
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

    fun defaultMaxDollarsFor(strategyType: StrategyType): Int =
        StrategyCatalog.defaultMaxDollars(strategyType)

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
            val displayName = instanceDisplayName(instance.strategyType, instance.symbol)
            val matchesSearch = state.searchQuery.isBlank() ||
                displayName.contains(state.searchQuery, ignoreCase = true) ||
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

        val selected = instances.find { it.id == state.selectedInstanceId }
        val sessionDate = currentSessionDateIso()
        val performance = selected?.let { instance ->
            PerformanceUiMapper.build(
                instance = instance.syncInProgressRun(sessionDate),
                sessionDate = sessionDate,
                sortColumn = runSortColumn,
                sortDirection = runSortDirection
            )
        }

        _uiState.update {
            StrategiesUiState(
                filteredRows = filtered.map(StrategyUiMapper::toRowUi),
                filteredCount = filtered.size,
                selectedInstance = selected,
                searchQuery = state.searchQuery,
                instanceFilter = state.instanceFilter,
                strategyTypeFilter = state.strategyTypeFilter,
                detailTab = state.detailTab,
                showAddDialog = showAddDialog,
                selectedInstanceId = state.selectedInstanceId,
                performance = performance,
                liveExecution = selected?.let(LiveExecutionUiMapper::toLiveState)
            )
        }
    }
}
