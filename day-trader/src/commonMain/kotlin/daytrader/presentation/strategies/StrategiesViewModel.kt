package daytrader.presentation.strategies

import daytrader.data.StrategiesAppState
import daytrader.data.StrategiesAppStateRepository
import daytrader.data.StrategyCatalog
import daytrader.data.StrategyInstanceRepository
import daytrader.broker.SessionTradeMatcher
import daytrader.domain.currentRunTimestampIso
import daytrader.gateway.AccountPosition
import daytrader.gateway.BrokerFill
import daytrader.gateway.BrokerGateway
import daytrader.gateway.GatewayConnectionState
import daytrader.gateway.WorkingOrder
import daytrader.data.InstanceRunController
import daytrader.data.InstanceRunStopWatcher
import daytrader.data.MarketOpenAutoStarter
import daytrader.data.PreMarketClosePositionWatcher
import daytrader.data.MarketOpenCountdownWatcher
import daytrader.data.TouchTurnSessionBootstrap
import daytrader.domain.StrategyInstance
import daytrader.domain.StrategyType
import daytrader.domain.InstanceStatus
import daytrader.domain.defaultStrategyInstance
import daytrader.domain.duplicateStrategyInstance
import daytrader.domain.instanceDisplayName
import daytrader.broker.SymbolMarkets
import daytrader.domain.resolveStopSnapshot
import daytrader.domain.onRunStopped
import daytrader.domain.withoutPerformanceRun
import daytrader.domain.withClosedPosition
import daytrader.domain.withStopPrice
import daytrader.platform.currentSessionDateIso
import daytrader.presentation.markets.MarketFilterState
import daytrader.presentation.markets.marketLabelForZone
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
    private val appStateRepository: StrategiesAppStateRepository,
    private val marketFilter: MarketFilterState,
    touchTurnMarketData: BrokerGateway? = null
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val touchTurnBootstrap = touchTurnMarketData?.let { gateway ->
        TouchTurnSessionBootstrap(gateway, repository, scope)
    }

    private var appState = StrategiesAppState()
    private var showAddDialog = false
    private var instances: List<StrategyInstance> = emptyList()
    private var runSortColumn = RunSortColumn.START
    private var runSortDirection = SortDirection.DESCENDING
    private var brokerPositions: List<AccountPosition> = emptyList()
    private var brokerOpenOrders: List<WorkingOrder> = emptyList()
    private var brokerFills: List<BrokerFill> = emptyList()
    private var brokerConnection: GatewayConnectionState = GatewayConnectionState.Disconnected
    private var startBlockedAlert: StartBlockedByPositionAlert? = null
    private var selectedMarketZoneId: String? = null
    private var selectedPerformanceRunId: String? = null

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

        marketFilter.selectedZoneId
            .onEach { zoneId ->
                selectedMarketZoneId = zoneId
                emitUiState()
            }
            .launchIn(scope)

        instances = repository.instances.value
        selectedMarketZoneId = marketFilter.selectedZoneId.value
        emitUiState()

        touchTurnMarketData?.let { gateway ->
            gateway.positions
                .onEach {
                    brokerPositions = it
                    emitUiState()
                }
                .launchIn(scope)
            gateway.openOrders
                .onEach {
                    brokerOpenOrders = it
                    emitUiState()
                }
                .launchIn(scope)
            gateway.fills
                .onEach {
                    brokerFills = it
                    emitUiState()
                }
                .launchIn(scope)
            gateway.connectionState
                .onEach {
                    brokerConnection = it
                    emitUiState()
                }
                .launchIn(scope)
        }

        MarketOpenCountdownWatcher(scope = scope).start()
        marketFilter.applyStartupDefaultIfNeeded()

        MarketOpenAutoStarter(
            repository = repository,
            touchTurnBootstrap = touchTurnBootstrap,
            scope = scope,
            isGlobalAutoStartEnabled = { appState.globalAutoStartEnabled },
            canStartInstance = { instance ->
                when (brokerConnection) {
                    GatewayConnectionState.Connecting -> false
                    else -> !SymbolMarkets.hasOpenPosition(instance.symbol, brokerPositions)
                }
            },
            onInstanceAutoStarted = { instanceId ->
                appStateRepository.update {
                    it.copy(selectedInstanceId = instanceId, detailTab = StrategyDetailTab.LIVE)
                }
            }
        ).start()

        touchTurnMarketData?.let { gateway ->
            InstanceRunStopWatcher(gateway, repository, scope).start()
            PreMarketClosePositionWatcher(gateway, repository, scope).start()
        }
    }

    fun onGlobalAutoStartEnabledChange(enabled: Boolean) {
        appStateRepository.update { it.copy(globalAutoStartEnabled = enabled) }
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

    fun onClearFilters() {
        marketFilter.clear()
        appStateRepository.update {
            it.copy(
                searchQuery = "",
                instanceFilter = InstanceFilter.ALL,
                strategyTypeFilter = null
            )
        }
    }

    fun onSelectInstance(id: String) {
        selectedPerformanceRunId = null
        appStateRepository.update {
            it.copy(
                selectedInstanceId = id,
                detailTab = StrategyDetailTab.CONFIGURATION
            )
        }
    }

    fun onSelectPerformanceRun(runId: String) {
        selectedPerformanceRunId = if (selectedPerformanceRunId == runId) null else runId
        emitUiState()
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

    fun onCreateInstance(
        strategyType: StrategyType,
        symbol: String,
        maxDollars: Int,
        autoStartOnMarketOpen: Boolean = false
    ) {
        if (symbol.isBlank() || maxDollars <= 0) return
        val instance = defaultStrategyInstance(
            strategyType = strategyType,
            symbol = symbol,
            maxDollars = maxDollars
        ).copy(autoStartOnMarketOpen = autoStartOnMarketOpen)
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

    fun onDismissStartBlockedAlert() {
        startBlockedAlert = null
        emitUiState()
    }

    fun onToggleRun(id: String) {
        val sessionDate = currentSessionDateIso()
        val existing = repository.instances.value.find { it.id == id } ?: return
        val wasRunning = existing.status == InstanceStatus.RUNNING
        if (!wasRunning) {
            val blockingPosition = SymbolMarkets.findOpenPosition(existing.symbol, brokerPositions)
            if (blockingPosition != null) {
                startBlockedAlert = StartBlockedAlertMapper.from(existing, blockingPosition)
                emitUiState()
                return
            }
        }
        val brokerPosition = SymbolMarkets.findOpenPosition(existing.symbol, brokerPositions)
        val hadOpenPosition = brokerPosition != null
        repository.update(id) { current ->
            if (current.status == InstanceStatus.RUNNING) {
                val stoppedAt = currentRunTimestampIso()
                val sessionTrades = SessionTradeMatcher.captureForRunStop(
                    instance = current,
                    fills = brokerFills,
                    stoppedAt = stoppedAt
                )
                val snapshot = current.resolveStopSnapshot(
                    hadOpenBrokerPosition = hadOpenPosition,
                    brokerUnrealizedPnL = brokerPosition?.totalUnrealizedPnL,
                    sessionTrades = sessionTrades
                )
                current.onRunStopped(stoppedAt = stoppedAt, snapshot = snapshot)
            } else {
                InstanceRunController.start(
                    instance = current,
                    sessionDate = sessionDate,
                    touchTurnBootstrap = touchTurnBootstrap,
                    markAutoStarted = false
                )
            }
        }
        repository.flushPersistence()
        syncInstancesFromRepository()
        if (!wasRunning) {
            appStateRepository.update {
                it.copy(selectedInstanceId = id, detailTab = StrategyDetailTab.LIVE)
            }
        } else {
            appStateRepository.update {
                it.copy(selectedInstanceId = id, detailTab = StrategyDetailTab.PERFORMANCE)
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

    fun onAdjustStop(instanceId: String, stopText: String) {
        val newStop = stopText.toDoubleOrNull() ?: return
        repository.update(instanceId) { instance ->
            val updated = instance.live.withStopPrice(
                newStop = newStop,
                rewardMultiple = StrategyCatalog.rewardMultiple(instance.strategyType)
            ) ?: return@update instance
            instance.copy(live = updated)
        }
    }

    fun onClosePosition(instanceId: String) {
        val sessionDate = currentSessionDateIso()
        repository.update(instanceId) { instance ->
            instance.withClosedPosition(sessionDate)
        }
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

    fun onDeletePerformanceRun(instanceId: String, runId: String) {
        if (selectedPerformanceRunId == runId) selectedPerformanceRunId = null
        repository.update(instanceId) { it.withoutPerformanceRun(runId) }
        repository.flushPersistence()
        syncInstancesFromRepository()
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

    private fun syncInstancesFromRepository() {
        instances = repository.instances.value
    }

    private fun emitUiState() {
        syncInstancesFromRepository()
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
            val matchesMarket = selectedMarketZoneId == null ||
                SymbolMarkets.zoneId(instance.symbol) == selectedMarketZoneId
            matchesSearch && matchesFilter && matchesStrategyType && matchesMarket
        }

        val selectedId = state.selectedInstanceId
        if (selectedId != null && filtered.none { it.id == selectedId }) {
            appStateRepository.update { it.copy(selectedInstanceId = filtered.firstOrNull()?.id) }
            return
        }

        val selected = selectedId?.let { id -> filtered.find { it.id == id } }
        val sessionDate = currentSessionDateIso()
        val selectedBrokerPnL = selected?.let { instance ->
            SymbolMarkets.findOpenPosition(instance.symbol, brokerPositions)
                ?.takeIf { it.quantity != 0 }
                ?.totalUnrealizedPnL
        }
        val selectedCardPresentation = selected?.let { instance ->
            InstanceCardStateMapper.resolve(
                instance,
                sessionDate,
                selectedBrokerPnL,
                brokerOpenOrders
            )
        }
        val performance = selected?.let { instance ->
            PerformanceUiMapper.build(
                instance = instance,
                sessionDate = sessionDate,
                sortColumn = runSortColumn,
                sortDirection = runSortDirection,
                selectedRunId = selectedPerformanceRunId
            )
        }

        val listRows = filtered.map { instance ->
            val brokerPnL = SymbolMarkets.findOpenPosition(instance.symbol, brokerPositions)
                ?.takeIf { it.quantity != 0 }
                ?.totalUnrealizedPnL
            StrategyUiMapper.toRowUi(
                instance,
                sessionDate,
                brokerUnrealizedPnL = brokerPnL,
                brokerOpenOrders = brokerOpenOrders
            )
        }
        val hasActiveFilters = state.searchQuery.isNotBlank() ||
            state.instanceFilter != InstanceFilter.ALL ||
            state.strategyTypeFilter != null ||
            selectedMarketZoneId != null

        _uiState.update {
            StrategiesUiState(
                filteredRows = listRows,
                filteredCount = filtered.size,
                totalCount = instances.size,
                hasActiveFilters = hasActiveFilters,
                selectedMarketZoneId = selectedMarketZoneId,
                selectedMarketLabel = selectedMarketZoneId?.let(::marketLabelForZone),
                selectedInstance = selected,
                selectedCardPresentation = selectedCardPresentation,
                searchQuery = state.searchQuery,
                instanceFilter = state.instanceFilter,
                strategyTypeFilter = state.strategyTypeFilter,
                detailTab = state.detailTab,
                showAddDialog = showAddDialog,
                selectedInstanceId = selected?.id,
                performance = performance,
                liveExecution = selected?.let(LiveExecutionUiMapper::toLiveState),
                liveBroker = selected?.let { instance ->
                    LiveBrokerUiMapper.forSymbol(
                        symbol = instance.symbol,
                        positions = brokerPositions,
                        openOrders = brokerOpenOrders,
                        connection = brokerConnection
                    )
                },
                liveSessionTrades = selected?.let { instance ->
                    LiveSessionTradesUiMapper.forInstance(
                        instance = instance,
                        liveFills = brokerFills,
                        brokerPosition = SymbolMarkets.findOpenPosition(instance.symbol, brokerPositions)
                    )
                },
                startBlockedAlert = startBlockedAlert,
                globalAutoStartEnabled = state.globalAutoStartEnabled
            )
        }
    }
}
