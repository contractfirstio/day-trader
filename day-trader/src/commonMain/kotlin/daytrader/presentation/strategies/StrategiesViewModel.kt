package daytrader.presentation.strategies

import daytrader.data.StrategiesAppState
import daytrader.data.StrategiesAppStateRepository
import daytrader.data.StrategyCatalog
import daytrader.data.StrategyDeploymentRepository
import daytrader.broker.SessionTradeMatcher
import daytrader.domain.currentSessionTimestampIso
import daytrader.gateway.AccountPosition
import daytrader.gateway.BrokerFill
import daytrader.gateway.BrokerGateway
import daytrader.gateway.GatewayConnectionState
import daytrader.gateway.LiveQuote
import daytrader.gateway.WorkingOrder
import daytrader.data.DeploymentSessionController
import daytrader.data.LiveMarketDataLifecycle
import daytrader.data.DeploymentSessionStopWatcher
import daytrader.data.SessionStopOrderCleanup
import daytrader.data.MarketOpenAutoStarter
import daytrader.data.PreMarketClosePositionWatcher
import daytrader.data.MarketOpenCountdownWatcher
import daytrader.data.TouchTurnSessionBootstrap
import daytrader.domain.StrategyDeployment
import daytrader.domain.StrategyType
import daytrader.domain.DeploymentStatus
import daytrader.domain.SessionStatus
import daytrader.domain.defaultStrategyDeployment
import daytrader.domain.duplicateStrategyDeployment
import daytrader.domain.instanceDisplayName
import daytrader.broker.SymbolMarkets
import daytrader.domain.DeploymentMarket
import daytrader.domain.MarketSource
import daytrader.domain.InstrumentIdentity
import daytrader.domain.InstrumentResolution
import daytrader.domain.InstrumentListingCandidates
import daytrader.domain.InstrumentResolveLog
import daytrader.domain.ResolvedInstrument
import daytrader.domain.RthMarketSessions
import daytrader.domain.resolveStopSnapshot
import daytrader.domain.SessionStopParams
import daytrader.domain.inferTouchTurnStopTrigger
import daytrader.domain.onSessionStopped
import daytrader.domain.withTouchTurnPositionOpenedIfNeeded
import daytrader.domain.lastClosedTouchTurnSession
import daytrader.domain.withoutSessionHistoryEntry
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
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class StrategiesViewModel(
    private val repository: StrategyDeploymentRepository,
    private val appStateRepository: StrategiesAppStateRepository,
    private val marketFilter: MarketFilterState,
    private val brokerGateway: BrokerGateway? = null,
    touchTurnSessionGateway: BrokerGateway? = null,
    ensureLiveMarketData: ((String, InstrumentIdentity?) -> Unit)? = null,
    private val releaseLiveMarketData: ((String, InstrumentIdentity?) -> Unit)? = null
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val sessionGateway = touchTurnSessionGateway ?: brokerGateway
    private val livePriceUiLogsEnabled: Boolean =
        System.getenv("DAY_TRADER_LIVE_PRICE_UI_LOGS")?.equals("true", ignoreCase = true) == true
    private val touchTurnBootstrap = sessionGateway?.let { session ->
        TouchTurnSessionBootstrap(
            sessionGateway = session,
            executionGateway = brokerGateway ?: session,
            repository = repository,
            scope = scope,
            ensureLiveMarketData = ensureLiveMarketData
        )
    }

    private var appState = StrategiesAppState()
    private var showAddDialog = false
    private var deployments: List<StrategyDeployment> = emptyList()
    private var runSortColumn = SessionHistorySortColumn.TIME
    private var runSortDirection = SortDirection.DESCENDING
    private var brokerPositions: List<AccountPosition> = emptyList()
    private var brokerQuotes: Map<String, LiveQuote> = emptyMap()
    private var brokerOpenOrders: List<WorkingOrder> = emptyList()
    private var brokerFills: List<BrokerFill> = emptyList()
    private var brokerConnection: GatewayConnectionState = GatewayConnectionState.Disconnected
    private var startBlockedAlert: StartBlockedByPositionAlert? = null
    private var selectedMarketZoneId: String? = null
    private var selectedSessionHistoryId: String? = null
    private val touchTurnPriceHistories = mutableMapOf<String, LivePriceTickHistory>()

    private val _uiState = MutableStateFlow(StrategiesUiState())
    val uiState: StateFlow<StrategiesUiState> = _uiState.asStateFlow()

    init {
        appStateRepository.state
            .onEach { state ->
                appState = state
                emitUiState()
            }
            .launchIn(scope)

        repository.deployments
            .onEach { list ->
                val previousById = deployments.associateBy { it.id }
                for (deployment in list) {
                    val was = previousById[deployment.id]
                    if (was?.status == DeploymentStatus.RUNNING &&
                        deployment.status != DeploymentStatus.RUNNING
                    ) {
                        maybeReleaseLiveMarketDataForSymbol(
                            deployment.symbol,
                            DeploymentMarket.effectiveInstrument(deployment)
                        )
                    }
                }
                deployments = list
                pruneTouchTurnPriceHistories()
                reconcileSelectedDeployment(list)
            }
            .launchIn(scope)

        marketFilter.selectedZoneId
            .onEach { zoneId ->
                selectedMarketZoneId = zoneId
                emitUiState()
            }
            .launchIn(scope)

        deployments = repository.deployments.value
        selectedMarketZoneId = marketFilter.selectedZoneId.value
        emitUiState()

        brokerGateway?.let { gateway ->
            gateway.positions
                .onEach {
                    brokerPositions = it
                    recordTouchTurnPositionMilestones(it)
                    recordTouchTurnLivePrices()
                    emitUiState()
                }
                .launchIn(scope)
            gateway.openOrders
                .onEach {
                    brokerOpenOrders = it
                    recordTouchTurnLivePrices()
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

        var previousSessionConnection: GatewayConnectionState? = null
        sessionGateway?.connectionState
            ?.onEach { state ->
                if (state == GatewayConnectionState.Connected &&
                    previousSessionConnection != GatewayConnectionState.Connected
                ) {
                    touchTurnBootstrap?.retryStuckLoadsWhenConnected()
                }
                previousSessionConnection = state
            }
            ?.launchIn(scope)

        // Live bid/ask/last come from the session gateway (IB in hybrid mode), not the execution gateway.
        sessionGateway?.quotes
            ?.onEach { quotes ->
                if (livePriceUiLogsEnabled && quotes.isNotEmpty()) {
                    println("[LIVE_PRICE_UI] quotes snapshot keys=${quotes.keys} size=${quotes.size}")
                }
                brokerQuotes = quotes
                recordTouchTurnLivePrices()
                emitUiState()
            }
            ?.launchIn(scope)

        MarketOpenCountdownWatcher(scope = scope).start()
        marketFilter.applyStartupDefaultIfNeeded()

        MarketOpenAutoStarter(
            repository = repository,
            touchTurnBootstrap = touchTurnBootstrap,
            scope = scope,
            isGlobalAutoStartEnabled = { appState.globalAutoStartEnabled },
            canStartDeployment = { instance ->
                when (brokerConnection) {
                    GatewayConnectionState.Connecting -> false
                    else -> !SymbolMarkets.hasOpenPosition(instance.symbol, brokerPositions)
                }
            },
            onDeploymentAutoStarted = { instanceId ->
                appStateRepository.update {
                    it.copy(selectedDeploymentId = instanceId, detailTab = StrategyDetailTab.LIVE)
                }
            }
        ).start()

        brokerGateway?.let { gateway ->
            DeploymentSessionStopWatcher(gateway, repository, scope).start()
            PreMarketClosePositionWatcher(gateway, repository, scope).start()
        }
    }

    fun onGlobalAutoStartEnabledChange(enabled: Boolean) {
        appStateRepository.update { it.copy(globalAutoStartEnabled = enabled) }
    }

    fun onSearchChange(query: String) {
        appStateRepository.update { it.copy(searchQuery = query) }
    }

    fun onDeploymentFilterChange(filter: DeploymentFilter) {
        appStateRepository.update { it.copy(deploymentFilter = filter) }
    }

    fun onStrategyTypeFilterChange(type: StrategyType?) {
        appStateRepository.update { it.copy(strategyTypeFilter = type) }
    }

    fun onClearFilters() {
        marketFilter.clear()
        appStateRepository.update {
            it.copy(
                searchQuery = "",
                deploymentFilter = DeploymentFilter.ALL,
                strategyTypeFilter = null
            )
        }
    }

    fun onMarketFilterToggle(zoneId: String) {
        marketFilter.toggle(zoneId)
    }

    fun onClearMarketFilter() {
        marketFilter.clear()
        emitUiState()
    }

    fun onSelectDeployment(id: String) {
        selectedSessionHistoryId = null
        appStateRepository.update {
            it.copy(
                selectedDeploymentId = id,
                detailTab = StrategyDetailTab.CONFIGURATION
            )
        }
    }

    fun onSelectSessionHistory(runId: String) {
        selectedSessionHistoryId = if (selectedSessionHistoryId == runId) null else runId
        emitUiState()
    }

    fun onDetailTabChange(tab: StrategyDetailTab) {
        appStateRepository.update { it.copy(detailTab = tab) }
        emitUiState()
    }

    fun onResetTradingPanel(deploymentId: String) {
        val instance = deployments.find { it.id == deploymentId } ?: return
        val lastClosed = instance.lastClosedTouchTurnSession() ?: return
        appStateRepository.update { state ->
            state.copy(
                tradingPanelDismissedRecapSessionId =
                    state.tradingPanelDismissedRecapSessionId + (deploymentId to lastClosed.id)
            )
        }
        emitUiState()
    }

    private fun reconcileSessionHistorySelection(instance: StrategyDeployment) {
        val selectedId = selectedSessionHistoryId ?: return
        val stillVisible = instance.sessionHistory.any { session ->
            session.id == selectedId &&
                DeploymentMarket.sessionMatchesMarketFilter(session, instance, selectedMarketZoneId)
        }
        if (!stillVisible) {
            selectedSessionHistoryId = null
        }
    }

    fun onShowAddDialog() {
        showAddDialog = true
        emitUiState()
    }

    fun onDismissAddDialog() {
        showAddDialog = false
        emitUiState()
    }

    fun resolveInstrumentForSymbol(
        symbol: String,
        onResult: (Result<InstrumentResolution>) -> Unit
    ) {
        val trimmed = symbol.trim().uppercase()
        if (trimmed.isBlank()) {
            onResult(Result.failure(IllegalArgumentException("Symbol is blank")))
            return
        }
        scope.launch {
            val resolveGateway = sessionGateway ?: brokerGateway
            val connected = resolveGateway?.connectionState?.value == GatewayConnectionState.Connected
            val source = when {
                resolveGateway == null -> "no_gateway"
                connected -> "ib"
                else -> "offline_heuristic"
            }
            InstrumentResolveLog.resolveStarted(trimmed, source)
            val resolution = if (resolveGateway != null && connected) {
                resolveGateway.resolveInstrument(trimmed).fold(
                    onSuccess = { it },
                    onFailure = { error ->
                        InstrumentResolveLog.line(
                            "gateway resolve failed symbol=$trimmed error=${error.message}; using heuristic"
                        )
                        InstrumentResolution(listOf(DeploymentMarket.fromSymbolHeuristic(trimmed)))
                    }
                )
            } else {
                InstrumentResolution(listOf(DeploymentMarket.fromSymbolHeuristic(trimmed)))
            }
            val uiCandidates = InstrumentListingCandidates.prepareForUi(resolution.candidates)
            InstrumentResolveLog.resolveFinished(
                symbol = trimmed,
                success = true,
                rawCount = resolution.candidates.size,
                uiCount = uiCandidates.size,
                listings = uiCandidates.map(InstrumentListingCandidates::listingLabel)
            )
            withContext(Dispatchers.Main) {
                onResult(Result.success(resolution))
            }
        }
    }

    fun onCreateDeployment(
        strategyType: StrategyType,
        symbol: String,
        marketZoneId: String,
        currencyCode: String,
        marketSource: MarketSource,
        companyName: String?,
        instrument: InstrumentIdentity?,
        maxDollars: Int,
        autoStartOnMarketOpen: Boolean = false
    ) {
        if (symbol.isBlank() || maxDollars <= 0) return
        val instance = defaultStrategyDeployment(
            strategyType = strategyType,
            symbol = symbol,
            maxDollars = maxDollars,
            marketZoneId = marketZoneId,
            currencyCode = currencyCode,
            marketSource = marketSource,
            companyName = companyName,
            instrument = instrument
        ).copy(autoStartOnMarketOpen = autoStartOnMarketOpen)
        repository.add(instance)
        appStateRepository.update {
            it.copy(
                selectedDeploymentId = instance.id,
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

    private fun maybeReleaseLiveMarketDataForSymbol(symbol: String, instrument: InstrumentIdentity?) {
        val release = releaseLiveMarketData ?: return
        if (LiveMarketDataLifecycle.anyRunningDeploymentNeedsQuotes(symbol, deployments)) return
        release(symbol, instrument)
    }

    fun onToggleSession(id: String) {
        val sessionDate = currentSessionDateIso()
        val existing = repository.deployments.value.find { it.id == id } ?: return
        val wasRunning = existing.status == DeploymentStatus.RUNNING
        if (!wasRunning) {
            val blockingPosition = SymbolMarkets.findOpenPosition(existing, brokerPositions)
            if (blockingPosition != null) {
                startBlockedAlert = StartBlockedAlertMapper.from(existing, blockingPosition)
                emitUiState()
                return
            }
        }
        val brokerPosition = SymbolMarkets.findOpenPosition(existing, brokerPositions)
        val hadOpenPosition = brokerPosition != null
        val hasOpenOrders = SymbolMarkets.hasOpenOrders(existing, brokerOpenOrders)
        if (wasRunning) {
            brokerGateway?.let { SessionStopOrderCleanup.flattenSymbolForSession(it, existing.symbol) }
        }
        repository.update(id) { current ->
            if (current.status == DeploymentStatus.RUNNING) {
                val stoppedAt = currentSessionTimestampIso()
                val sessionTrades = SessionTradeMatcher.captureForSessionStop(
                    instance = current,
                    fills = brokerFills,
                    stoppedAt = stoppedAt
                )
                val snapshot = current.resolveStopSnapshot(
                    hadOpenBrokerPosition = hadOpenPosition,
                    brokerUnrealizedPnL = brokerPosition?.totalUnrealizedPnL,
                    sessionTrades = sessionTrades
                )
                val stopTrigger = inferTouchTurnStopTrigger(
                    instance = current,
                    sessionTrades = sessionTrades,
                    hasOpenPosition = hadOpenPosition,
                    hasOpenOrders = hasOpenOrders
                )
                current.onSessionStopped(
                    stoppedAt = stoppedAt,
                    snapshot = snapshot,
                    stopParams = brokerGateway?.let { gateway ->
                        SessionStopParams(
                            stopTrigger = stopTrigger,
                            brokerId = gateway.brokerId,
                            stopErrorMessage = current.touchTurnSession?.errorMessage,
                            brokerUnrealizedPnLAtStop = brokerPosition?.totalUnrealizedPnL,
                            hasOpenPosition = hadOpenPosition,
                            hasOpenOrders = hasOpenOrders
                        )
                    }
                )
            } else {
                DeploymentSessionController.start(
                    instance = current,
                    sessionDate = sessionDate,
                    touchTurnBootstrap = touchTurnBootstrap,
                    markAutoStarted = false
                )
            }
        }
        repository.flushPersistence()
        syncDeploymentsFromRepository()
        if (!wasRunning) {
            appStateRepository.update {
                it.copy(selectedDeploymentId = id, detailTab = StrategyDetailTab.LIVE)
            }
        } else {
            appStateRepository.update {
                it.copy(selectedDeploymentId = id, detailTab = StrategyDetailTab.SESSION_HISTORY)
            }
        }
    }

    fun onSessionHistoryHeaderClick(column: SessionHistorySortColumn) {
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

    fun onUpdateDeployment(id: String, transform: (StrategyDeployment) -> StrategyDeployment) {
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
        val selected = deployments.find { it.id == appState.selectedDeploymentId } ?: return
        val copy = duplicateStrategyDeployment(selected)
        repository.add(copy)
        appStateRepository.update { it.copy(selectedDeploymentId = copy.id) }
    }

    fun onDeleteSelected() {
        val id = appState.selectedDeploymentId ?: return
        repository.remove(id)
    }

    fun onDeleteSessionHistory(instanceId: String, runId: String) {
        if (selectedSessionHistoryId == runId) selectedSessionHistoryId = null
        repository.update(instanceId) { it.withoutSessionHistoryEntry(runId) }
        repository.flushPersistence()
        syncDeploymentsFromRepository()
    }

    fun defaultMaxDollarsFor(strategyType: StrategyType): Int =
        StrategyCatalog.defaultMaxDollars(strategyType)

    private fun reconcileSelectedDeployment(list: List<StrategyDeployment>) {
        val current = appState.selectedDeploymentId
        val validSelection = when {
            current != null && list.any { it.id == current } -> current
            else -> list.firstOrNull()?.id
        }
        if (validSelection != current) {
            appStateRepository.update { it.copy(selectedDeploymentId = validSelection) }
        } else {
            emitUiState()
        }
    }

    private fun syncDeploymentsFromRepository() {
        deployments = repository.deployments.value
    }

    private fun recordTouchTurnPositionMilestones(positions: List<AccountPosition>) {
        for (instance in repository.deployments.value) {
            if (instance.status != DeploymentStatus.RUNNING) continue
            if (instance.strategyType != StrategyType.TOUCH_AND_TURN_SCALPER) continue
            if (!SymbolMarkets.hasOpenPosition(instance.symbol, positions)) continue
            if (instance.touchTurnSession?.milestones?.positionOpenedAt != null) continue
            repository.update(instance.id) { it.withTouchTurnPositionOpenedIfNeeded() }
        }
    }

    private fun emitUiState() {
        syncDeploymentsFromRepository()
        val state = appState
        val filtered = deployments.filter { instance ->
            val displayName = instanceDisplayName(instance.strategyType, instance.symbol)
            val matchesSearch = state.searchQuery.isBlank() ||
                displayName.contains(state.searchQuery, ignoreCase = true) ||
                instance.symbol.contains(state.searchQuery, ignoreCase = true)
            val matchesFilter = when (state.deploymentFilter) {
                DeploymentFilter.ALL -> true
                DeploymentFilter.RUNNING -> instance.status == DeploymentStatus.RUNNING
                DeploymentFilter.STOPPED -> instance.status == DeploymentStatus.STOPPED
            }
            val matchesStrategyType =
                state.strategyTypeFilter == null || instance.strategyType == state.strategyTypeFilter
            val matchesMarket = selectedMarketZoneId == null ||
                DeploymentMarket.effectiveZoneId(instance) == selectedMarketZoneId
            matchesSearch && matchesFilter && matchesStrategyType && matchesMarket
        }

        val selectedId = state.selectedDeploymentId
        if (selectedId != null && filtered.none { it.id == selectedId }) {
            appStateRepository.update { it.copy(selectedDeploymentId = filtered.firstOrNull()?.id) }
            return
        }

        val selected = selectedId?.let { id -> filtered.find { it.id == id } }
        selected?.let { reconcileSessionHistorySelection(it) }
        val sessionDate = currentSessionDateIso()
        val selectedBrokerPnL = selected?.let { instance ->
            SymbolMarkets.findOpenPosition(instance, brokerPositions)
                ?.takeIf { it.quantity != 0 }
                ?.totalUnrealizedPnL
        }
        val selectedCardPresentation = selected?.let { instance ->
            DeploymentCardStateMapper.resolve(
                instance,
                sessionDate,
                selectedBrokerPnL,
                brokerOpenOrders
            )
        }
        val sessionHistory = selected?.let { instance ->
            SessionHistoryUiMapper.build(
                instance = instance,
                sessionDate = sessionDate,
                sortColumn = runSortColumn,
                sortDirection = runSortDirection,
                selectedRunId = selectedSessionHistoryId,
                marketZoneFilter = selectedMarketZoneId,
                marketFilterLabel = selectedMarketZoneId?.let(::marketLabelForZone)
            )
        }

        val listRows = filtered.map { instance ->
            val brokerPnL = SymbolMarkets.findOpenPosition(instance, brokerPositions)
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
            state.deploymentFilter != DeploymentFilter.ALL ||
            state.strategyTypeFilter != null ||
            selectedMarketZoneId != null

        _uiState.update {
            StrategiesUiState(
                filteredRows = listRows,
                filteredCount = filtered.size,
                totalCount = deployments.size,
                hasActiveFilters = hasActiveFilters,
                selectedMarketZoneId = selectedMarketZoneId,
                selectedMarketLabel = selectedMarketZoneId?.let(::marketLabelForZone),
                selectedDeployment = selected,
                selectedCardPresentation = selectedCardPresentation,
                searchQuery = state.searchQuery,
                deploymentFilter = state.deploymentFilter,
                strategyTypeFilter = state.strategyTypeFilter,
                detailTab = state.detailTab,
                showAddDialog = showAddDialog,
                selectedDeploymentId = selected?.id,
                sessionHistory = sessionHistory,
                liveExecution = selected?.let(LiveExecutionUiMapper::toLiveState),
                liveBroker = selected?.let { instance ->
                    LiveBrokerUiMapper.forSymbol(
                        symbol = instance.symbol,
                        positions = brokerPositions,
                        quotes = brokerQuotes,
                        openOrders = brokerOpenOrders,
                        connection = brokerConnection
                    )
                },
                liveSessionTrades = selected?.let { instance ->
                    val showRecap = TradingPanelRecap.showsLastSession(
                        instance,
                        state.tradingPanelDismissedRecapSessionId,
                    )
                    if (instance.status != DeploymentStatus.RUNNING && !showRecap) {
                        null
                    } else {
                        LiveSessionTradesUiMapper.forDeployment(
                            instance = instance,
                            liveFills = brokerFills,
                            brokerPosition = SymbolMarkets.findOpenPosition(instance, brokerPositions)
                        )
                    }
                },
                touchTurnLiveOrderChart = buildTouchTurnLiveOrderChart(selected),
                startBlockedAlert = startBlockedAlert,
                globalAutoStartEnabled = state.globalAutoStartEnabled,
                tradingPanelShowsLastSessionRecap = selected?.let { instance ->
                    TradingPanelRecap.showsLastSession(
                        instance,
                        state.tradingPanelDismissedRecapSessionId,
                    )
                } == true,
            )
        }
    }

    private fun recordTouchTurnLivePrices() {
        val now = System.currentTimeMillis()
        for (deployment in deployments) {
            if (deployment.strategyType != StrategyType.TOUCH_AND_TURN_SCALPER) continue
            if (deployment.status != DeploymentStatus.RUNNING) continue
            val session = deployment.touchTurnSession ?: continue
            if (!session.ordersPlacedForSession && session.entryOrdersPermitted != true) continue
            val price = LiveMarkPriceResolver.resolve(
                deployment.symbol,
                brokerPositions,
                brokerQuotes
            ) ?: continue
            val norm = SymbolMarkets.normalizeSymbol(deployment.symbol)
            touchTurnPriceHistories.getOrPut(norm) { LivePriceTickHistory() }.record(now, price)
        }
    }

    private fun pruneTouchTurnPriceHistories() {
        val activeSymbols = deployments
            .filter {
                it.strategyType == StrategyType.TOUCH_AND_TURN_SCALPER &&
                    it.status == DeploymentStatus.RUNNING
            }
            .map { SymbolMarkets.normalizeSymbol(it.symbol) }
            .toSet()
        touchTurnPriceHistories.keys.retainAll(activeSymbols)
    }

    private fun buildTouchTurnLiveOrderChart(
        instance: StrategyDeployment?
    ): TouchTurnLiveOrderChartUiState? {
        val deployment = instance ?: return null
        if (deployment.strategyType != StrategyType.TOUCH_AND_TURN_SCALPER) return null
        if (deployment.status != DeploymentStatus.RUNNING) return null
        val session = deployment.touchTurnSession ?: return null
        val symbolOrders = brokerOpenOrders.filter {
            SymbolMarkets.symbolsMatch(deployment.symbol, it.symbol)
        }
        val hasBrokerActivity = symbolOrders.isNotEmpty() ||
            SymbolMarkets.findOpenPosition(deployment, brokerPositions) != null
        if (!session.ordersPlacedForSession && !hasBrokerActivity) return null

        val norm = SymbolMarkets.normalizeSymbol(deployment.symbol)
        val history = touchTurnPriceHistories[norm]?.snapshot().orEmpty()
        val currentPrice = LiveMarkPriceResolver.resolve(
            deployment.symbol,
            brokerPositions,
            brokerQuotes
        )
        return TouchTurnLiveOrderChartUiMapper.build(
            symbol = deployment.symbol,
            currencyCode = session.currencyCode,
            priceHistory = history,
            currentPrice = currentPrice,
            openOrders = symbolOrders,
            plannedBracket = session.plannedBracket,
            bracketSetup = session.setup
        )
    }
}
