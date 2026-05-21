package daytrader.presentation.strategies

import daytrader.data.StrategyCatalog
import daytrader.data.StrategyInstanceRepository
import daytrader.domain.InstanceStatus
import daytrader.domain.StrategyInstance
import daytrader.domain.StrategyType
import daytrader.domain.defaultInstanceName
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
    private val repository: StrategyInstanceRepository
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private var searchQuery = ""
    private var instanceFilter = InstanceFilter.ALL
    private var strategyTypeFilter: StrategyType? = null
    private var selectedInstanceId: String? = null
    private var detailTab = StrategyDetailTab.CONFIGURATION
    private var showAddDialog = false
    private var instances: List<StrategyInstance> = emptyList()

    private val _uiState = MutableStateFlow(StrategiesUiState())
    val uiState: StateFlow<StrategiesUiState> = _uiState.asStateFlow()

    init {
        repository.instances
            .onEach { list ->
                instances = list
                if (selectedInstanceId != null && list.none { it.id == selectedInstanceId }) {
                    selectedInstanceId = list.firstOrNull()?.id
                } else if (selectedInstanceId == null) {
                    selectedInstanceId = list.firstOrNull()?.id
                }
                emitUiState()
            }
            .launchIn(scope)
    }

    fun onSearchChange(query: String) {
        searchQuery = query
        emitUiState()
    }

    fun onInstanceFilterChange(filter: InstanceFilter) {
        instanceFilter = filter
        emitUiState()
    }

    fun onStrategyTypeFilterChange(type: StrategyType?) {
        strategyTypeFilter = type
        emitUiState()
    }

    fun onSelectInstance(id: String) {
        selectedInstanceId = id
        detailTab = StrategyDetailTab.CONFIGURATION
        emitUiState()
    }

    fun onDetailTabChange(tab: StrategyDetailTab) {
        detailTab = tab
        emitUiState()
    }

    fun onShowAddDialog() {
        showAddDialog = true
        emitUiState()
    }

    fun onDismissAddDialog() {
        showAddDialog = false
        emitUiState()
    }

    fun onCreateInstance(
        strategyType: StrategyType,
        name: String,
        symbol: String,
        timeframe: String,
        riskDollars: Int
    ) {
        val symbolUpper = symbol.uppercase()
        val instance = defaultStrategyInstance(
            strategyType = strategyType,
            name = name.ifBlank { defaultInstanceName(strategyType, symbolUpper) },
            symbol = symbolUpper,
            timeframe = timeframe,
            riskDollars = riskDollars
        )
        repository.add(instance)
        selectedInstanceId = instance.id
        detailTab = StrategyDetailTab.CONFIGURATION
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
        val selected = instances.find { it.id == selectedInstanceId } ?: return
        val copy = duplicateStrategyInstance(selected)
        repository.add(copy)
        selectedInstanceId = copy.id
        emitUiState()
    }

    fun onDeleteSelected() {
        val id = selectedInstanceId ?: return
        repository.remove(id)
        emitUiState()
    }

    fun defaultRiskFor(strategyType: StrategyType): Int =
        StrategyCatalog.defaultsFor(strategyType).defaultRiskDollars

    private fun emitUiState() {
        val filtered = instances.filter { instance ->
            val matchesSearch = searchQuery.isBlank() ||
                instance.name.contains(searchQuery, ignoreCase = true) ||
                instance.symbol.contains(searchQuery, ignoreCase = true)
            val matchesFilter = when (instanceFilter) {
                InstanceFilter.ALL -> true
                InstanceFilter.RUNNING -> instance.status == InstanceStatus.RUNNING
                InstanceFilter.STOPPED -> instance.status == InstanceStatus.STOPPED
            }
            val matchesStrategyType = strategyTypeFilter == null || instance.strategyType == strategyTypeFilter
            matchesSearch && matchesFilter && matchesStrategyType
        }

        _uiState.update {
            StrategiesUiState(
                filteredRows = filtered.map(StrategyUiMapper::toRowUi),
                filteredCount = filtered.size,
                selectedInstance = instances.find { it.id == selectedInstanceId },
                searchQuery = searchQuery,
                instanceFilter = instanceFilter,
                strategyTypeFilter = strategyTypeFilter,
                detailTab = detailTab,
                showAddDialog = showAddDialog,
                selectedInstanceId = selectedInstanceId
            )
        }
    }
}
