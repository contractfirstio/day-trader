package daytrader.presentation.strategies

import daytrader.data.StrategiesAppState
import daytrader.data.StrategiesAppStateRepository
import daytrader.data.StrategyCatalog
import daytrader.data.StrategyDeploymentRepository
import daytrader.domain.lastClosedTouchTurnSession
import daytrader.domain.withStopPrice
import daytrader.gateway.AccountPosition
import daytrader.gateway.BrokerFill
import daytrader.gateway.BrokerGateway
import daytrader.gateway.BrokerKind
import daytrader.gateway.GatewayConnectionState
import daytrader.gateway.LiveQuote
import daytrader.gateway.WorkingOrder
import daytrader.data.LiveMarketDataLifecycle
import daytrader.data.MarketOpenCountdownWatcher
import daytrader.data.PreMarketClosePositionWatcher
import daytrader.data.RunningSessionShutdown
import daytrader.engine.TouchTurnCommand
import daytrader.engine.TouchTurnEngineConfig
import daytrader.engine.TouchTurnEnginePort
import daytrader.engine.TouchTurnEvent
import daytrader.domain.TouchTurnSessionStartedBy
import daytrader.domain.TouchTurnSessionStopTrigger
import daytrader.domain.StrategyDeployment
import daytrader.domain.StrategyType
import daytrader.domain.DeploymentStatus
import daytrader.domain.SessionStatus
import daytrader.domain.defaultStrategyDeployment
import daytrader.domain.duplicateStrategyDeployment
import daytrader.domain.inProgressSession
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
import daytrader.domain.withClosedPosition
import daytrader.domain.withoutSessionHistoryEntry
import daytrader.diagnostics.TimestampedConsoleLog
import daytrader.diagnostics.SessionTrace
import daytrader.diagnostics.TouchTurnStateSyncLog
import daytrader.diagnostics.UiActionLog
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
    private val brokerKind: BrokerKind = BrokerKind.EMULATOR,
    private val touchTurnEngine: TouchTurnEnginePort? = null,
    ensureLiveMarketData: ((String, InstrumentIdentity?) -> Unit)? = null,
    private val releaseLiveMarketData: ((String, InstrumentIdentity?) -> Unit)? = null
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val sessionGateway = touchTurnSessionGateway ?: brokerGateway
    private val requiresBidAskForFills = brokerKind.usesLiveIbMarketData
    private val useTouchTurnEngine = touchTurnEngine != null && TouchTurnEngineConfig.useEngine()
    private val livePriceUiLogsEnabled: Boolean =
        System.getenv("DAY_TRADER_LIVE_PRICE_UI_LOGS")?.equals("true", ignoreCase = true) == true

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
    private var marketDataConnection: GatewayConnectionState = GatewayConnectionState.Disconnected
    private var startBlockedAlert: StartBlockedByPositionAlert? = null
    private var selectedMarketZoneId: String? = null
    private var selectedSessionHistoryId: String? = null
    private var pipelineRefreshTick: Int = 0
    private val touchTurnPriceHistories = mutableMapOf<String, LivePriceTickHistory>()

    private fun touchTurnPriceHistoryFor(symbol: String): LivePriceTickHistory =
        touchTurnPriceHistories.getOrPut(SymbolMarkets.normalizeSymbol(symbol)) {
            // ~15 minutes at one sample every 2s
            LivePriceTickHistory(maxPoints = 450, minIntervalMillis = 2_000L)
        }

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
                // Engine auto-start/stop updates the repository off the manual toggle path;
                // refresh the left-rail cards immediately so status chips don't stay on Stopped.
                emitUiState()
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

        sessionGateway?.quotes
            ?.onEach { quotes ->
                if (livePriceUiLogsEnabled && quotes.isNotEmpty()) {
                    TimestampedConsoleLog.line(
                        "LIVE_PRICE_UI",
                        "quotes snapshot keys=${quotes.keys} size=${quotes.size}"
                    )
                }
                brokerQuotes = quotes
                recordTouchTurnLivePrices()
                emitUiState()
            }
            ?.launchIn(scope)

        if (sessionGateway != null && sessionGateway !== brokerGateway) {
            sessionGateway.connectionState
                .onEach {
                    marketDataConnection = it
                    emitUiState()
                }
                .launchIn(scope)
        }

        touchTurnEngine?.events
            ?.onEach { event -> handleTouchTurnEvent(event) }
            ?.launchIn(scope)

        MarketOpenCountdownWatcher(scope = scope).start()
        marketFilter.applyStartupDefaultIfNeeded()

        brokerGateway?.let { gateway ->
            PreMarketClosePositionWatcher(gateway, repository, scope).start()
        }

        scope.launch {
            while (true) {
                kotlinx.coroutines.delay(1_000)
                val selected = deployments.find { it.id == appState.selectedDeploymentId }
                val needsPipelineRefresh = selected?.strategyType == StrategyType.TOUCH_AND_TURN_SCALPER &&
                    selected.status == DeploymentStatus.RUNNING
                if (needsPipelineRefresh) {
                    pipelineRefreshTick++
                    emitUiState()
                }
            }
        }
    }

    private fun handleTouchTurnEvent(event: TouchTurnEvent) {
        when (event) {
            is TouchTurnEvent.StartBlocked -> {
                UiActionLog.log(
                    action = "engine_start_blocked",
                    symbol = event.alert.instanceSymbol,
                    details = mapOf(
                        "displayName" to event.alert.instanceDisplayName,
                        "reason" to event.alert.reason
                    )
                )
                startBlockedAlert = event.alert
                emitUiState()
            }
            is TouchTurnEvent.UiNavigate -> {
                UiActionLog.log(
                    action = "engine_ui_navigate",
                    deploymentId = event.instanceId,
                    details = mapOf("tab" to event.tab.name)
                )
                appStateRepository.update {
                    it.copy(selectedDeploymentId = event.instanceId, detailTab = event.tab)
                }
            }
            is TouchTurnEvent.SessionStopped -> {
                UiActionLog.log(
                    action = "engine_session_stopped",
                    deploymentId = event.instanceId,
                    sessionId = event.sessionId,
                    details = mapOf("trigger" to event.trigger.name)
                )
                TouchTurnStateSyncLog.clearDeployment(event.instanceId)
                appStateRepository.update {
                    it.copy(selectedDeploymentId = event.instanceId, detailTab = StrategyDetailTab.SESSION_HISTORY)
                }
                emitUiState()
            }
            is TouchTurnEvent.SessionStarted -> {
                UiActionLog.log(
                    action = "engine_session_started",
                    deploymentId = event.instanceId,
                    sessionId = event.sessionId,
                    details = mapOf(
                        "sessionDate" to event.sessionDate,
                        "startedBy" to event.startedBy.name
                    )
                )
                recordTouchTurnEngineSync(
                    deploymentId = event.instanceId,
                    trigger = "engine_session_started",
                    triggerDetails = mapOf(
                        "sessionDate" to event.sessionDate,
                        "startedBy" to event.startedBy.name
                    )
                )
                appStateRepository.update {
                    it.copy(selectedDeploymentId = event.instanceId, detailTab = StrategyDetailTab.LIVE)
                }
                emitUiState()
            }
            is TouchTurnEvent.NoTradeDecision -> {
                val sessionId = activeSessionId(event.instanceId)
                UiActionLog.log(
                    action = "engine_no_trade_decision",
                    deploymentId = event.instanceId,
                    sessionId = sessionId,
                    details = mapOf("outcome" to event.outcome.name)
                )
                recordTouchTurnEngineSync(
                    deploymentId = event.instanceId,
                    trigger = "engine_no_trade_decision",
                    triggerDetails = mapOf("outcome" to event.outcome.name)
                )
            }
            is TouchTurnEvent.BracketSubmitted -> {
                val sessionId = activeSessionId(event.instanceId)
                UiActionLog.log(
                    action = "engine_bracket_submitted",
                    deploymentId = event.instanceId,
                    sessionId = sessionId,
                    symbol = event.plan.symbol,
                    details = mapOf("orderCount" to event.plan.orders.size.toString())
                )
                recordTouchTurnEngineSync(
                    deploymentId = event.instanceId,
                    trigger = "engine_bracket_submitted",
                    triggerDetails = mapOf("orderCount" to event.plan.orders.size.toString())
                )
            }
            is TouchTurnEvent.PositionOpened -> {
                val sessionId = activeSessionId(event.instanceId)
                UiActionLog.log(
                    action = "engine_position_opened",
                    deploymentId = event.instanceId,
                    sessionId = sessionId,
                    details = mapOf("milestoneAt" to event.milestoneAt)
                )
                recordTouchTurnEngineSync(
                    deploymentId = event.instanceId,
                    trigger = "engine_position_opened",
                    triggerDetails = mapOf("milestoneAt" to event.milestoneAt)
                )
            }
            is TouchTurnEvent.OrchestratorError -> {
                UiActionLog.log(
                    action = "engine_orchestrator_error",
                    deploymentId = event.instanceId,
                    details = mapOf("message" to event.message)
                )
                SessionTrace.log(
                    type = "orchestrator_error",
                    deploymentId = event.instanceId,
                    details = mapOf("message" to event.message)
                )
            }
        }
    }

    fun hasRunningSessions(): Boolean =
        repository.deployments.value.any { it.status == DeploymentStatus.RUNNING }

    fun runningSessionSymbols(): List<String> =
        repository.deployments.value
            .filter { it.status == DeploymentStatus.RUNNING }
            .map { it.symbol }
            .sorted()

    /** Stop all running deployments and persist — call before broker/runtime teardown. */
    fun shutdownRunningSessions(
        trigger: TouchTurnSessionStopTrigger = TouchTurnSessionStopTrigger.APPLICATION_SHUTDOWN
    ) {
        val gateway = brokerGateway ?: sessionGateway ?: return
        RunningSessionShutdown.stopAllRunning(
            repository = repository,
            gateway = gateway,
            brokerKind = brokerKind,
            brokerPositions = brokerPositions,
            brokerOpenOrders = brokerOpenOrders,
            brokerFills = brokerFills,
            trigger = trigger
        )
        syncDeploymentsFromRepository()
        emitUiState()
    }

    fun onGlobalAutoStartEnabledChange(enabled: Boolean) {
        UiActionLog.log(action = "toggle_global_auto_start", details = mapOf("enabled" to enabled.toString()))
        appStateRepository.update { it.copy(globalAutoStartEnabled = enabled) }
        (touchTurnEngine)?.updateGlobalAutoStartEnabled(enabled)
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
        UiActionLog.log(
            action = "create_deployment",
            deploymentId = instance.id,
            symbol = instance.symbol,
            details = mapOf(
                "strategy" to strategyType.name,
                "maxDollars" to maxDollars.toString(),
                "autoStartOnMarketOpen" to autoStartOnMarketOpen.toString(),
                "marketZoneId" to marketZoneId
            )
        )
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
        UiActionLog.forDeployment(
            deployment = existing,
            action = if (wasRunning) "stop_session" else "start_session",
            details = mapOf("sessionDate" to sessionDate)
        )
        if (useTouchTurnEngine && touchTurnEngine != null) {
            if (!wasRunning) {
                touchTurnEngine.dispatch(
                    TouchTurnCommand.StartSession(
                        instanceId = id,
                        sessionDate = sessionDate,
                        startedBy = TouchTurnSessionStartedBy.MANUAL
                    )
                )
            } else {
                touchTurnEngine.dispatch(
                    TouchTurnCommand.StopSession(
                        instanceId = id,
                        trigger = TouchTurnSessionStopTrigger.MANUAL
                    )
                )
            }
            syncDeploymentsFromRepository()
            emitUiState()
            return
        }
        syncDeploymentsFromRepository()
        emitUiState()
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
        UiActionLog.forDeployment(
            deployment = repository.deployments.value.find { it.id == instanceId },
            action = "adjust_stop",
            details = mapOf("stopPrice" to newStop.toString())
        )
        if (useTouchTurnEngine && touchTurnEngine != null) {
            touchTurnEngine.dispatch(TouchTurnCommand.AdjustStop(instanceId, newStop))
            return
        }
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
        UiActionLog.forDeployment(
            deployment = repository.deployments.value.find { it.id == instanceId },
            action = "close_position",
            details = mapOf("sessionDate" to sessionDate)
        )
        if (useTouchTurnEngine && touchTurnEngine != null) {
            touchTurnEngine.dispatch(TouchTurnCommand.ClosePosition(instanceId, sessionDate))
            return
        }
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
        UiActionLog.forDeployment(
            deployment = deployments.find { it.id == id },
            action = "delete_deployment"
        )
        repository.remove(id)
    }

    fun onDeleteSessionHistory(instanceId: String, runId: String) {
        UiActionLog.forDeployment(
            deployment = repository.deployments.value.find { it.id == instanceId },
            action = "delete_session_history",
            details = mapOf("runId" to runId)
        )
        if (selectedSessionHistoryId == runId) selectedSessionHistoryId = null
        if (useTouchTurnEngine && touchTurnEngine != null) {
            touchTurnEngine.dispatch(TouchTurnCommand.DeleteSessionHistory(instanceId, runId))
        } else {
            repository.update(instanceId) { it.withoutSessionHistoryEntry(runId) }
            repository.flushPersistence()
        }
        syncDeploymentsFromRepository()
        emitUiState()
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

        val touchTurnPipelineGraph = selected?.let { instance ->
            if (instance.strategyType == StrategyType.TOUCH_AND_TURN_SCALPER &&
                instance.status == DeploymentStatus.RUNNING
            ) {
                pipelineRefreshTick
            }
            TouchTurnPipelineUiMapper.graphForDeployment(
                instance = instance,
                brokerPositions = brokerPositions,
                brokerOpenOrders = brokerOpenOrders,
                brokerFills = brokerFills,
                showLastSessionRecap = TradingPanelRecap.showsLastSession(
                    instance,
                    state.tradingPanelDismissedRecapSessionId,
                ),
                nowEpochMillis = System.currentTimeMillis()
            )
        }

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
                        connection = if (requiresBidAskForFills) marketDataConnection else brokerConnection,
                        includeMarketQuotes = TradingPanelRecap.showsLiveMarketQuotes(
                            instance,
                            state.tradingPanelDismissedRecapSessionId,
                        ),
                        requireBidAskForFills = requiresBidAskForFills &&
                            instance.status == DeploymentStatus.RUNNING &&
                            (
                                instance.touchTurnSession?.ordersPlacedForSession == true ||
                                    instance.touchTurnSession?.entryOrdersPermitted == true
                                )
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
                touchTurnFormingBarPriceChart = buildTouchTurnFormingBarPriceChart(selected),
                startBlockedAlert = startBlockedAlert,
                globalAutoStartEnabled = state.globalAutoStartEnabled,
                tradingPanelShowsLastSessionRecap = selected?.let { instance ->
                    TradingPanelRecap.showsLastSession(
                        instance,
                        state.tradingPanelDismissedRecapSessionId,
                    )
                } == true,
                tradingPanelShowsLiveMarketQuotes = selected?.let { instance ->
                    TradingPanelRecap.showsLiveMarketQuotes(
                        instance,
                        state.tradingPanelDismissedRecapSessionId,
                    )
                } == true,
                touchTurnPipelineGraph = touchTurnPipelineGraph,
            )
        }
    }

    private fun recordTouchTurnLivePrices() {
        val now = System.currentTimeMillis()
        for (deployment in deployments) {
            if (deployment.strategyType != StrategyType.TOUCH_AND_TURN_SCALPER) continue
            if (deployment.status != DeploymentStatus.RUNNING) continue
            val session = deployment.touchTurnSession ?: continue
            val recordForForming = TouchTurnFormingBarPriceChartUiMapper.shouldRecordPrices(session)
            val recordForOrders =
                session.ordersPlacedForSession || session.entryOrdersPermitted == true
            if (!recordForForming && !recordForOrders) continue
            val price = LiveMarkPriceResolver.resolve(
                deployment.symbol,
                brokerPositions,
                brokerQuotes
            ) ?: continue
            touchTurnPriceHistoryFor(deployment.symbol).record(now, price)
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

    private fun buildTouchTurnFormingBarPriceChart(
        instance: StrategyDeployment?
    ): TouchTurnLiveOrderChartUiState? {
        val deployment = instance ?: return null
        if (deployment.status != DeploymentStatus.RUNNING) return null
        val session = deployment.touchTurnSession ?: return null
        val norm = SymbolMarkets.normalizeSymbol(deployment.symbol)
        val history = touchTurnPriceHistories[norm]?.snapshot().orEmpty()
        val currentPrice = LiveMarkPriceResolver.resolve(
            deployment.symbol,
            brokerPositions,
            brokerQuotes
        )
        return TouchTurnFormingBarPriceChartUiMapper.build(
            deployment = deployment,
            session = session,
            priceHistory = history,
            currentPrice = currentPrice,
            statusHint = fillReadinessHint(deployment.symbol)
        )
    }

    private fun fillReadinessHint(symbol: String): String? =
        LiveMarkPriceResolver.fillReadinessHint(
            quote = LiveMarkPriceResolver.quoteForSymbol(symbol, brokerQuotes),
            requiresBidAskForFills = requiresBidAskForFills
        )

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
            bracketSetup = session.setup,
            statusHint = fillReadinessHint(deployment.symbol)
        )
    }

    private fun recordTouchTurnEngineSync(
        deploymentId: String,
        trigger: String,
        triggerDetails: Map<String, String> = emptyMap()
    ) {
        val instance = deployments.find { it.id == deploymentId } ?: return
        if (instance.strategyType != StrategyType.TOUCH_AND_TURN_SCALPER) return
        val ctx = TouchTurnPipelineUiMapper.liveContext(
            instance = instance,
            brokerPositions = brokerPositions,
            brokerOpenOrders = brokerOpenOrders,
            brokerFills = brokerFills
        )
        TouchTurnStatusBreadcrumbMapper.graph(
            instance = instance,
            hasOpenPosition = ctx.hasOpenPosition,
            hasOpenOrders = ctx.hasOpenOrders,
            sessionTrades = ctx.sessionTrades,
            nowEpochMillis = ctx.nowEpochMillis,
            syncTrigger = trigger,
            syncTriggerDetails = triggerDetails
        )
    }

    private fun activeSessionId(deploymentId: String): String? =
        deployments.find { it.id == deploymentId }?.inProgressSession()?.id
}
