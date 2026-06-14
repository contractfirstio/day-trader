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
import daytrader.platform.TradingClock
import daytrader.platform.WallClock
import daytrader.data.LiveMarketDataLifecycle
import daytrader.data.MarketOpenCountdownWatcher
import daytrader.data.PreMarketClosePositionWatcher
import daytrader.data.RunningSessionShutdown
import daytrader.data.SessionMarketDataCapture
import daytrader.data.TouchTurnManualStopHandler
import daytrader.data.WatchlistRepository
import daytrader.data.WatchlistStrategyLinkSync
import daytrader.engine.TouchTurnCommand
import daytrader.engine.TouchTurnEngineConfig
import daytrader.engine.TouchTurnEnginePort
import daytrader.engine.TouchTurnEvent
import daytrader.domain.TouchTurnSessionContext
import daytrader.domain.TouchTurnSessionStartedBy
import daytrader.domain.TouchTurnSessionStopTrigger
import daytrader.domain.TouchTurnTrailingStopWarnings
import daytrader.domain.StrategyDeployment
import daytrader.domain.isTouchTurn
import daytrader.domain.StrategyType
import daytrader.domain.DeploymentStatus
import daytrader.domain.ExecutionState
import daytrader.domain.SessionStatus
import daytrader.domain.touchTurnAnalysisSessionForRun
import daytrader.domain.touchTurnRecapRun
import daytrader.domain.DEFAULT_WATCHLIST_ID
import daytrader.domain.defaultStrategyDeployment
import daytrader.domain.newWatchlistEntry
import daytrader.domain.duplicateStrategyDeployment
import daytrader.domain.inProgressSession
import daytrader.domain.sessionRealizedPnL
import daytrader.domain.instanceDisplayName
import daytrader.broker.SymbolMarkets
import daytrader.domain.DeploymentMarket
import daytrader.domain.clearTouchTurnPrepareIfInstrumentChanged
import daytrader.domain.clearTouchTurnPrepareIfRulesChanged
import daytrader.domain.TouchTurnLogic
import daytrader.domain.MarketSource
import daytrader.domain.InstrumentIdentity
import daytrader.domain.InstrumentResolution
import daytrader.domain.DeploymentSymbolResolver
import daytrader.domain.InstrumentListingCandidates
import daytrader.domain.InstrumentResolveLog
import daytrader.domain.ResolvedInstrument
import daytrader.domain.RthMarketSessions
import daytrader.domain.SymbolImportCsvParser
import daytrader.domain.SymbolImportExchange
import daytrader.platform.PlatformFilePicker
import daytrader.domain.withClosedPosition
import daytrader.domain.withoutClosedSessionHistory
import daytrader.domain.withoutSessionHistoryEntry
import daytrader.diagnostics.TimestampedConsoleLog
import daytrader.diagnostics.SessionTrace
import daytrader.diagnostics.TouchTurnStateSyncLog
import daytrader.diagnostics.UiActionLog
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
    private val releaseLiveMarketData: ((String, InstrumentIdentity?) -> Unit)? = null,
    private val onDeploymentCreated: ((String) -> Unit)? = null,
    private val watchlistRepository: WatchlistRepository? = null,
    private val tradingClock: TradingClock = WallClock
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val sessionGateway = touchTurnSessionGateway ?: brokerGateway
    private val requiresBidAskForFills = brokerKind.usesLiveIbMarketData
    private val useTouchTurnEngine = touchTurnEngine != null && TouchTurnEngineConfig.useEngine()
    private val livePriceUiLogsEnabled: Boolean =
        System.getenv("DAY_TRADER_LIVE_PRICE_UI_LOGS")?.equals("true", ignoreCase = true) == true

    private var appState = StrategiesAppState()
    private var showAddDialog = false
    private var addDialogPrefill: StrategyDeploymentAddPrefill? = null
    private var showImportDialog = false
    private var symbolImport: DeploymentSymbolImportUiState? = null
    private var importJobActive = false
    private var deployments: List<StrategyDeployment> = emptyList()
    private var runSortColumn = SessionHistorySortColumn.TIME
    private var runSortDirection = SortDirection.DESCENDING
    private var brokerPositions: List<AccountPosition> = emptyList()
    private var brokerQuotes: Map<String, LiveQuote> = emptyMap()
    private var brokerOpenOrders: List<WorkingOrder> = emptyList()
    private val lastBrokerOpenOrdersFingerprintByDeployment = mutableMapOf<String, String>()
    private var brokerFills: List<BrokerFill> = emptyList()
    private var brokerConnection: GatewayConnectionState = GatewayConnectionState.Disconnected
    private var marketDataConnection: GatewayConnectionState = GatewayConnectionState.Disconnected
    private var startBlockedAlert: StartBlockedByPositionAlert? = null
    private var selectedMarketZoneId: String? = null
    private var selectedSessionHistoryId: String? = null
    private var pipelineRefreshTick: Int = 0
    private val prepareInProgressIds = mutableSetOf<String>()
    private val touchTurnPriceHistories = mutableMapOf<String, LivePriceTickHistory>()
    private val touchTurnEntryApproachTrackers = mutableMapOf<String, TouchTurnEntryApproachTracker>()

    private fun touchTurnPriceHistoryFor(symbol: String): LivePriceTickHistory =
        touchTurnPriceHistories.getOrPut(SymbolMarkets.normalizeSymbol(symbol)) {
            // ~15 minutes at one sample every 2s
            LivePriceTickHistory(maxPoints = 450, minIntervalMillis = 2_000L)
        }

    private fun touchTurnEntryApproachTrackerFor(deploymentId: String): TouchTurnEntryApproachTracker =
        touchTurnEntryApproachTrackers.getOrPut(deploymentId) { TouchTurnEntryApproachTracker() }

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
                    if (was?.status != DeploymentStatus.RUNNING &&
                        deployment.status == DeploymentStatus.RUNNING &&
                        deployment.id == appState.selectedDeploymentId
                    ) {
                        selectedSessionHistoryId = null
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
                    recordTouchTurnLiveChartSamples()
                    emitUiState()
                }
                .launchIn(scope)
            gateway.openOrders
                .onEach { orders ->
                    brokerOpenOrders = orders
                    logBrokerOpenOrdersForRunningTouchTurn(orders, "gateway_open_orders")
                    recordTouchTurnLiveChartSamples()
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
                recordTouchTurnLiveChartSamples()
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
        // Emulator testing often spans US/HK symbols; do not hide deployments behind a live-market filter.
        if (brokerKind != BrokerKind.EMULATOR) {
            marketFilter.applyStartupDefaultIfNeeded()
        }

        brokerGateway?.let { gateway ->
            PreMarketClosePositionWatcher(gateway, repository, scope).start()
        }

        scope.launch {
            while (true) {
                kotlinx.coroutines.delay(1_000)
                val selected = deployments.find { it.id == appState.selectedDeploymentId }
                val needsPipelineRefresh = selected?.isTouchTurn == true &&
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
                val instance = deployments.find { it.id == event.instanceId }
                UiActionLog.log(
                    action = "engine_no_trade_decision",
                    deploymentId = event.instanceId,
                    sessionId = sessionId,
                    details = buildMap {
                        put("outcome", event.outcome.name)
                    }
                )
                recordTouchTurnEngineSync(
                    deploymentId = event.instanceId,
                    trigger = "engine_no_trade_decision",
                    triggerDetails = buildMap {
                        put("outcome", event.outcome.name)
                    }
                )
            }
            is TouchTurnEvent.BracketSubmitted -> {
                val sessionId = activeSessionId(event.instanceId)
                val instance = deployments.find { it.id == event.instanceId }
                val symbolOrders = instance?.let {
                    SymbolMarkets.openOrdersForDeployment(it, brokerOpenOrders)
                }.orEmpty()
                UiActionLog.log(
                    action = "engine_bracket_submitted",
                    deploymentId = event.instanceId,
                    sessionId = sessionId,
                    symbol = event.plan.symbol,
                    details = buildMap {
                        put("orderCount", event.plan.orders.size.toString())
                        put("brokerOpenOrdersForSymbol", symbolOrders.size.toString())
                        put(
                            "brokerOrderIds",
                            symbolOrders.joinToString(",") { "${it.orderId}:${it.status}" }
                                .ifEmpty { "none" }
                        )
                    }
                )
                recordTouchTurnEngineSync(
                    deploymentId = event.instanceId,
                    trigger = "engine_bracket_submitted",
                    triggerDetails = buildMap {
                        put("orderCount", event.plan.orders.size.toString())
                        put("brokerOpenOrdersForSymbol", symbolOrders.size.toString())
                    }
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
            is TouchTurnEvent.PrepareStarted -> {
                prepareInProgressIds.add(event.instanceId)
                UiActionLog.log(
                    action = "engine_prepare_started",
                    deploymentId = event.instanceId
                )
                emitUiState()
            }
            is TouchTurnEvent.PrepareFinished -> {
                prepareInProgressIds.remove(event.instanceId)
                UiActionLog.log(
                    action = "engine_prepare_finished",
                    deploymentId = event.instanceId,
                    details = mapOf("overallStatus" to event.overallStatus.name)
                )
                emitUiState()
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
        stopAllSessionMarketDataCaptures(trigger = trigger.name.lowercase())
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
        val deploymentId = appState.selectedDeploymentId ?: return
        val instance = deployments.find { it.id == deploymentId } ?: return
        if (instance.sessionHistory.none { it.id == runId }) return
        selectedSessionHistoryId = if (selectedSessionHistoryId == runId) null else runId
        appStateRepository.update { it.copy(detailTab = StrategyDetailTab.LIVE) }
        emitUiState()
    }

    fun onDetailTabChange(tab: StrategyDetailTab) {
        appStateRepository.update { it.copy(detailTab = tab) }
        emitUiState()
    }

    fun onResetTradingPanel(deploymentId: String) {
        val instance = deployments.find { it.id == deploymentId } ?: return
        val recapRun = instance.touchTurnRecapRun(selectedSessionHistoryId) ?: return
        if (deploymentId == appState.selectedDeploymentId) {
            selectedSessionHistoryId = null
        }
        appStateRepository.update { state ->
            state.copy(
                tradingPanelDismissedRecapSessionId =
                    state.tradingPanelDismissedRecapSessionId + (deploymentId to recapRun.id)
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

    fun onShowAddDialog(prefill: StrategyDeploymentAddPrefill? = null) {
        showAddDialog = true
        addDialogPrefill = prefill
        emitUiState()
    }

    fun onDismissAddDialog() {
        showAddDialog = false
        addDialogPrefill = null
        emitUiState()
    }

    fun onShowImportDialog() {
        if (importJobActive) return
        showImportDialog = true
        symbolImport = DeploymentSymbolImportUiState(
            maxDollarsText = defaultMaxDollarsFor(StrategyType.TOUCH_AND_TURN_SCALPER).toString(),
            brokerConnected = isBrokerConnectedForResolve(),
            watchlistImportEnabled = watchlistRepository != null
        )
        emitUiState()
    }

    fun onDismissImportDialog() {
        if (importJobActive) return
        showImportDialog = false
        symbolImport = null
        emitUiState()
    }

    fun onImportStrategyTypeChange(strategyType: StrategyType) {
        val import = symbolImport ?: return
        symbolImport = import.copy(
            strategyType = strategyType,
            maxDollarsText = defaultMaxDollarsFor(strategyType).toString()
        )
        emitUiState()
    }

    fun onImportMaxDollarsChange(text: String) {
        val import = symbolImport ?: return
        symbolImport = import.copy(maxDollarsText = text)
        emitUiState()
    }

    fun onImportTargetChange(target: SymbolImportTarget) {
        val import = symbolImport ?: return
        if (target == SymbolImportTarget.WATCHLIST && watchlistRepository == null) return
        symbolImport = import.copy(
            target = target,
            rows = reannotateImportRows(import.rows, target)
        )
        emitUiState()
    }

    fun onPickImportCsvFile() {
        if (importJobActive) return
        val path = PlatformFilePicker.pickCsvFile("Select symbol CSV")
        if (path == null) return
        val text = PlatformFilePicker.readText(path)
        if (text == null) {
            symbolImport = symbolImport?.copy(
                filePath = path,
                parseErrors = listOf(
                    daytrader.domain.SymbolImportParseError(0, path, "Could not read file")
                ),
                rows = emptyList()
            )
            emitUiState()
            return
        }
        val parsed = SymbolImportCsvParser.parse(text)
        val target = symbolImport?.target ?: SymbolImportTarget.DEPLOYMENT
        symbolImport = symbolImport?.copy(
            filePath = path,
            parseErrors = parsed.errors,
            rows = reannotateImportRows(parsed.rows.map { it.toImportRowUi() }, target)
        )
        emitUiState()
    }

    fun onStartSymbolImport() {
        val import = symbolImport ?: return
        if (!import.canStartImport || importJobActive) return
        val maxDollars = when (import.target) {
            SymbolImportTarget.DEPLOYMENT -> import.maxDollarsText.toIntOrNull() ?: return
            SymbolImportTarget.WATCHLIST -> 0
        }
        scope.launch {
            importJobActive = true
            val rows = import.rows
            symbolImport = import.copy(
                phase = DeploymentImportPhase.IMPORTING,
                total = rows.size,
                completed = 0,
                succeeded = 0,
                failed = 0,
                skipped = 0
            )
            emitUiState()
            var succeeded = 0
            var failed = 0
            var skipped = 0
            val resolveGateway = sessionGateway ?: brokerGateway
            val connected = isBrokerConnectedForResolve()
            for ((index, _) in rows.withIndex()) {
                val currentRow = symbolImport?.rows?.getOrNull(index) ?: rows[index]
                if (currentRow.status == DeploymentImportRowStatus.SKIPPED) {
                    skipped++
                    symbolImport = symbolImport?.copy(
                        completed = index + 1,
                        succeeded = succeeded,
                        failed = failed,
                        skipped = skipped
                    )
                    emitUiState()
                    continue
                }
                updateImportRow(index) {
                    it.copy(status = DeploymentImportRowStatus.RESOLVING, detail = "Resolving via IB…")
                }
                emitUiState()
                val outcome = importSingleRow(
                    row = rows[index],
                    target = import.target,
                    strategyType = import.strategyType,
                    maxDollars = maxDollars,
                    resolveGateway = resolveGateway,
                    connected = connected
                )
                when (outcome) {
                    is ImportRowOutcome.Success -> {
                        succeeded++
                        val successDetail = when (import.target) {
                            SymbolImportTarget.DEPLOYMENT -> "Deployment created"
                            SymbolImportTarget.WATCHLIST -> "Added to watchlist"
                        }
                        updateImportRow(index) {
                            it.copy(
                                status = DeploymentImportRowStatus.SUCCESS,
                                detail = successDetail,
                                companyName = outcome.companyName
                            )
                        }
                    }
                    is ImportRowOutcome.Skipped -> {
                        skipped++
                        updateImportRow(index) {
                            it.copy(
                                status = DeploymentImportRowStatus.SKIPPED,
                                detail = outcome.reason
                            )
                        }
                    }
                    is ImportRowOutcome.Failed -> {
                        failed++
                        updateImportRow(index) {
                            it.copy(
                                status = DeploymentImportRowStatus.FAILED,
                                detail = outcome.message
                            )
                        }
                    }
                }
                symbolImport = symbolImport?.copy(
                    completed = index + 1,
                    succeeded = succeeded,
                    failed = failed,
                    skipped = skipped
                )
                emitUiState()
            }
            symbolImport = symbolImport?.copy(phase = DeploymentImportPhase.COMPLETE)
            importJobActive = false
            emitUiState()
        }
    }

    private fun isBrokerConnectedForResolve(): Boolean {
        val resolveGateway = sessionGateway ?: brokerGateway
        return resolveGateway?.connectionState?.value == GatewayConnectionState.Connected
    }

    private fun updateImportRow(index: Int, transform: (DeploymentImportRowUi) -> DeploymentImportRowUi) {
        val import = symbolImport ?: return
        if (index !in import.rows.indices) return
        val updated = import.rows.toMutableList()
        updated[index] = transform(updated[index])
        symbolImport = import.copy(rows = updated)
    }

    private sealed class ImportRowOutcome {
        data class Success(val companyName: String?) : ImportRowOutcome()
        data class Skipped(val reason: String) : ImportRowOutcome()
        data class Failed(val message: String) : ImportRowOutcome()
    }

    private suspend fun importSingleRow(
        row: DeploymentImportRowUi,
        target: SymbolImportTarget,
        strategyType: StrategyType,
        maxDollars: Int,
        resolveGateway: BrokerGateway?,
        connected: Boolean
    ): ImportRowOutcome {
        val zoneId = SymbolImportExchange.toMarketZoneId(row.exchangeCode)
            ?: return ImportRowOutcome.Failed("Unknown exchange ${row.exchangeCode}")
        if (symbolExistsForImportTarget(row.symbol, target)) {
            return ImportRowOutcome.Skipped(skipReasonForImportTarget(target))
        }
        val resolved = DeploymentSymbolResolver.resolveForImport(
            symbol = row.symbol,
            expectedZoneId = zoneId,
            gateway = resolveGateway,
            connected = connected
        ).getOrElse { error ->
            return ImportRowOutcome.Failed(error.message ?: "Resolve failed")
        }
        return when (target) {
            SymbolImportTarget.DEPLOYMENT -> {
                val instance = defaultStrategyDeployment(
                    strategyType = strategyType,
                    symbol = row.symbol,
                    maxDollars = maxDollars,
                    marketZoneId = resolved.marketZoneId,
                    currencyCode = resolved.currencyCode,
                    marketSource = resolved.source,
                    companyName = resolved.companyName,
                    instrument = resolved.identity,
                    brokerKind = brokerKind
                )
                repository.add(instance)
                onDeploymentCreated?.invoke(instance.id)
                ImportRowOutcome.Success(resolved.companyName)
            }
            SymbolImportTarget.WATCHLIST -> {
                val watchlistRepo = watchlistRepository
                    ?: return ImportRowOutcome.Failed("Watchlist not available")
                val watchlistId = defaultWatchlistId(watchlistRepo)
                    ?: return ImportRowOutcome.Failed("No watchlist available")
                val entry = newWatchlistEntry(
                    symbol = row.symbol,
                    marketZoneId = resolved.marketZoneId,
                    currencyCode = resolved.currencyCode,
                    companyName = resolved.companyName,
                    instrument = resolved.identity
                )
                watchlistRepo.addEntry(watchlistId, entry)
                ImportRowOutcome.Success(resolved.companyName)
            }
        }
    }

    private fun reannotateImportRows(
        rows: List<DeploymentImportRowUi>,
        target: SymbolImportTarget
    ): List<DeploymentImportRowUi> = rows.map { row ->
        if (symbolExistsForImportTarget(row.symbol, target)) {
            row.copy(
                status = DeploymentImportRowStatus.SKIPPED,
                detail = skipReasonForImportTarget(target)
            )
        } else {
            row.copy(status = DeploymentImportRowStatus.PENDING, detail = null, companyName = null)
        }
    }

    private fun symbolExistsForImportTarget(symbol: String, target: SymbolImportTarget): Boolean =
        when (target) {
            SymbolImportTarget.DEPLOYMENT -> deploymentExistsForSymbol(symbol)
            SymbolImportTarget.WATCHLIST -> watchlistEntryExistsForSymbol(symbol)
        }

    private fun skipReasonForImportTarget(target: SymbolImportTarget): String =
        when (target) {
            SymbolImportTarget.DEPLOYMENT -> "Already exists in deployments"
            SymbolImportTarget.WATCHLIST -> "Already in watchlist"
        }

    private fun deploymentExistsForSymbol(symbol: String): Boolean =
        repository.deployments.value.any { deployment ->
            SymbolMarkets.symbolsMatch(deployment.symbol, symbol)
        }

    private fun watchlistEntryExistsForSymbol(symbol: String): Boolean {
        val repo = watchlistRepository ?: return false
        return repo.watchlists.value.any { watchlist ->
            watchlist.entries.any { entry ->
                SymbolMarkets.symbolsMatch(entry.symbol, symbol)
            }
        }
    }

    private fun defaultWatchlistId(repo: WatchlistRepository): String? {
        val lists = repo.watchlists.value
        return lists.find { it.id == DEFAULT_WATCHLIST_ID }?.id ?: lists.firstOrNull()?.id
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
            instrument = instrument,
            brokerKind = brokerKind
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
        onDeploymentCreated?.invoke(instance.id)
        appStateRepository.update {
            it.copy(
                selectedDeploymentId = instance.id,
                detailTab = StrategyDetailTab.CONFIGURATION
            )
        }
        showAddDialog = false
        addDialogPrefill = null
        emitUiState()
    }

    fun onDismissStartBlockedAlert() {
        startBlockedAlert = null
        emitUiState()
    }

    private fun maybeReleaseLiveMarketDataForSymbol(symbol: String, instrument: InstrumentIdentity?) {
        val release = releaseLiveMarketData ?: return
        if (LiveMarketDataLifecycle.anyDeploymentNeedsQuotes(symbol, deployments)) return
        release(symbol, instrument)
    }

    fun onStopSessionMarketDataCapture(deploymentId: String) {
        if (!brokerKind.capturesSessionMarketData) return
        val deployment = repository.deployments.value.find { it.id == deploymentId } ?: return
        val target = SessionMarketDataCapture.stop(deploymentId) ?: return
        UiActionLog.log(
            action = "stop_market_data_capture",
            deploymentId = deploymentId,
            sessionId = target.sessionId,
            symbol = target.symbol,
            details = mapOf("trigger" to "manual")
        )
        SessionTrace.log(
            type = "market_data_capture_stopped",
            deploymentId = deploymentId,
            sessionId = target.sessionId,
            symbol = target.symbol,
            details = mapOf("trigger" to "manual")
        )
        maybeReleaseLiveMarketDataForSymbol(target.symbol, target.instrument)
        emitUiState()
    }

    fun stopAllSessionMarketDataCaptures(trigger: String = "application_shutdown") {
        if (!brokerKind.capturesSessionMarketData) return
        val stopped = SessionMarketDataCapture.stopAll()
        for (target in stopped) {
            SessionTrace.log(
                type = "market_data_capture_stopped",
                deploymentId = target.deploymentId,
                sessionId = target.sessionId,
                symbol = target.symbol,
                details = mapOf("trigger" to trigger)
            )
            maybeReleaseLiveMarketDataForSymbol(target.symbol, target.instrument)
        }
    }

    fun hasActiveMarketDataCaptures(): Boolean =
        brokerKind.capturesSessionMarketData && SessionMarketDataCapture.activeTargets().isNotEmpty()

    fun onToggleSession(id: String) {
        val existing = repository.deployments.value.find { it.id == id } ?: return
        val sessionDate = DeploymentMarket.sessionDateIso(existing)
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
        val before = repository.deployments.value.find { it.id == id }
        val previousInstrumentKey = before?.let {
            DeploymentMarket.effectiveInstrument(it).dedupeKey()
        }
        val previousRules = before?.touchTurnRules
        repository.update(id) { current ->
            transform(current)
                .clearTouchTurnPrepareIfInstrumentChanged(previousInstrumentKey)
                .let { updated ->
                    if (previousRules != null) {
                        updated.clearTouchTurnPrepareIfRulesChanged(previousRules)
                    } else {
                        updated
                    }
                }
        }
        if (before != null && previousRules != null) {
            val after = repository.deployments.value.find { it.id == id }
            if (after != null && after.touchTurnRules != previousRules) {
                UiActionLog.forDeployment(
                    deployment = after,
                    action = "update_touch_turn_rules",
                    details = mapOf(
                        "openDeadline" to after.touchTurnRules.enables.openDeadline.toString(),
                        "liquidityRangeDailyAtr" to after.touchTurnRules.enables.liquidityRangeDailyAtr.toString(),
                        "adjustableTrailingStop" to after.touchTurnRules.enables.adjustableTrailingStop.toString(),
                        "bounceRejection" to after.touchTurnRules.enables.bounceRejection.toString()
                    )
                )
            }
        }
        // User edits must hit disk immediately: hybrid/replay engines keep updating running
        // deployments and would otherwise starve the shared debounced deployments writer.
        repository.flushPersistence()
    }

    fun onCopyTouchTurnRulesToAllOther(sourceId: String) {
        val source = repository.deployments.value.find { it.id == sourceId } ?: return
        if (source.status == DeploymentStatus.RUNNING) return
        val rules = source.touchTurnRules
        val targets = repository.deployments.value.filter { it.id != sourceId }
        if (targets.isEmpty()) return
        UiActionLog.forDeployment(
            deployment = source,
            action = "copy_touch_turn_rules_to_all_other",
            details = mapOf("targetCount" to targets.size.toString())
        )
        for (target in targets) {
            repository.update(target.id) { it.copy(touchTurnRules = rules) }
        }
        repository.flushPersistence()
    }

    fun onPrepareSession(id: String) {
        val existing = repository.deployments.value.find { it.id == id } ?: return
        if (existing.status == DeploymentStatus.RUNNING) return
        if (!existing.isTouchTurn) return
        if (!useTouchTurnEngine || touchTurnEngine == null) return
        UiActionLog.forDeployment(
            deployment = existing,
            action = "prepare_session",
            details = mapOf("sessionDate" to DeploymentMarket.sessionDateIso(existing))
        )
        touchTurnEngine.dispatch(TouchTurnCommand.PrepareSession(instanceId = id))
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
        val instance = repository.deployments.value.find { it.id == instanceId } ?: return
        val sessionDate = DeploymentMarket.sessionDateIso(instance)
        UiActionLog.forDeployment(
            deployment = instance,
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
        deleteDeploymentById(id)
    }

    fun deleteDeploymentById(id: String) {
        val existing = repository.deployments.value.find { it.id == id } ?: return
        UiActionLog.forDeployment(
            deployment = existing,
            action = "delete_deployment"
        )
        if (existing.status == DeploymentStatus.RUNNING) {
            val gateway = sessionGateway
            val stopped = TouchTurnManualStopHandler.stop(
                input = TouchTurnManualStopHandler.Input(
                    instance = existing,
                    brokerPositions = brokerPositions,
                    brokerOpenOrders = brokerOpenOrders,
                    brokerFills = brokerFills,
                    brokerKind = brokerKind
                ),
                gateway = gateway,
                explicitTrigger = TouchTurnSessionStopTrigger.MANUAL
            ).stoppedDeployment
            repository.update(id) { stopped }
        }
        prepareInProgressIds.remove(id)
        SessionMarketDataCapture.stop(id)?.let { target ->
            maybeReleaseLiveMarketDataForSymbol(target.symbol, target.instrument)
        }
        repository.remove(id)
        watchlistRepository?.let { WatchlistStrategyLinkSync.removeDeploymentFromAllWatchlists(it, id) }
        repository.flushPersistence()
        if (appState.selectedDeploymentId == id) {
            val nextId = repository.deployments.value.firstOrNull()?.id
            appStateRepository.update { it.copy(selectedDeploymentId = nextId) }
        }
        syncDeploymentsFromRepository()
        emitUiState()
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

    fun onDeleteAllSessionHistory(instanceId: String) {
        val deployment = repository.deployments.value.find { it.id == instanceId } ?: return
        val closedRunIds = deployment.sessionHistory
            .filter { it.status != SessionStatus.IN_PROGRESS }
            .map { it.id }
        if (closedRunIds.isEmpty()) return
        UiActionLog.forDeployment(
            deployment = deployment,
            action = "delete_all_session_history",
            details = mapOf("count" to closedRunIds.size.toString())
        )
        if (selectedSessionHistoryId in closedRunIds) selectedSessionHistoryId = null
        if (useTouchTurnEngine && touchTurnEngine != null) {
            touchTurnEngine.dispatch(TouchTurnCommand.DeleteAllSessionHistory(instanceId))
        } else {
            repository.update(instanceId) { it.withoutClosedSessionHistory() }
            repository.flushPersistence()
        }
        syncDeploymentsFromRepository()
        emitUiState()
    }

    fun onDeleteAllSessionHistoryForAllDeployments() {
        val allDeployments = repository.deployments.value
        val closedRunIds = allDeployments.flatMap { deployment ->
            deployment.sessionHistory
                .filter { it.status != SessionStatus.IN_PROGRESS }
                .map { it.id }
        }.toSet()
        if (closedRunIds.isEmpty()) return
        val deploymentsWithClosedHistory = allDeployments.filter { deployment ->
            deployment.sessionHistory.any { it.status != SessionStatus.IN_PROGRESS }
        }
        UiActionLog.log(
            action = "delete_all_session_history_all_deployments",
            details = mapOf(
                "deploymentCount" to deploymentsWithClosedHistory.size.toString(),
                "sessionCount" to closedRunIds.size.toString()
            )
        )
        if (selectedSessionHistoryId in closedRunIds) selectedSessionHistoryId = null
        if (useTouchTurnEngine && touchTurnEngine != null) {
            for (deployment in deploymentsWithClosedHistory) {
                touchTurnEngine.dispatch(TouchTurnCommand.DeleteAllSessionHistory(deployment.id))
            }
        } else {
            for (deployment in deploymentsWithClosedHistory) {
                repository.update(deployment.id) { it.withoutClosedSessionHistory() }
            }
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
        val sessionDate = selected?.let { DeploymentMarket.sessionDateIso(it) }
            ?: selectedMarketZoneId?.let { TouchTurnLogic.sessionDateIsoInMarketZone(it) }
            ?: deployments.firstOrNull()?.let { DeploymentMarket.sessionDateIso(it) }
            ?: TouchTurnLogic.sessionDateIsoInMarketZone(RthMarketSessions.US.zoneId)
        val selectedBrokerPnL = selected?.let { instance ->
            SymbolMarkets.findOpenPosition(instance, brokerPositions)
                ?.takeIf { it.quantity != 0 }
                ?.totalUnrealizedPnL
        }
        val selectedHasOpenPosition = selected?.let { instance ->
            SymbolMarkets.hasOpenPosition(instance, brokerPositions)
        } == true
        val selectedCardPresentation = selected?.let { instance ->
            DeploymentCardStateMapper.resolve(
                instance,
                sessionDate,
                selectedBrokerPnL,
                brokerOpenOrders,
                hasOpenPosition = selectedHasOpenPosition ||
                    (instance.status == DeploymentStatus.RUNNING &&
                        instance.live.state == ExecutionState.FILLED)
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
            val brokerPosition = SymbolMarkets.findOpenPosition(instance, brokerPositions)
                ?.takeIf { it.quantity != 0 }
            val brokerPnL = brokerPosition?.totalUnrealizedPnL
            StrategyUiMapper.toRowUi(
                instance,
                sessionDate,
                brokerUnrealizedPnL = brokerPnL,
                brokerOpenOrders = brokerOpenOrders,
                brokerPosition = brokerPosition
            )
        }
        val filteredSummary = FilteredDeploymentsSummaryMapper.build(
            instances = filtered,
            sessionDate = sessionDate,
            brokerPositions = brokerPositions,
            brokerOpenOrders = brokerOpenOrders,
        )
        val hasActiveFilters = state.searchQuery.isNotBlank() ||
            state.deploymentFilter != DeploymentFilter.ALL ||
            state.strategyTypeFilter != null ||
            selectedMarketZoneId != null

        val recapRunId = selectedSessionHistoryId?.takeIf { selected?.status != DeploymentStatus.RUNNING }
        val showSessionRecap = selected?.let { instance ->
            TradingPanelRecap.showsSessionRecap(
                instance,
                state.tradingPanelDismissedRecapSessionId,
                historicRunId = recapRunId,
            )
        } == true
        val touchTurnPipelineGraph = selected?.let { instance ->
            if (instance.isTouchTurn &&
                instance.status == DeploymentStatus.RUNNING
            ) {
                pipelineRefreshTick
            }
            TouchTurnPipelineUiMapper.graphForDeployment(
                instance = instance,
                brokerPositions = brokerPositions,
                brokerOpenOrders = brokerOpenOrders,
                brokerFills = brokerFills,
                showSessionRecap = showSessionRecap,
                recapRunId = recapRunId,
                nowEpochMillis = tradingClock.nowEpochMillis()
            )
        }
        val touchTurnOrderLifecycle = selected?.let { instance ->
            touchTurnOrderLifecycleFor(instance, showSessionRecap, recapRunId)
        }
        val touchTurnPrepare = selected?.let { instance ->
            TouchTurnPrepareUiMapper.forDeployment(
                instance = instance,
                prepareInProgress = prepareInProgressIds.contains(instance.id)
            )
        }

        val globalClosedSessionHistoryCount = deployments.sumOf { deployment ->
            deployment.sessionHistory.count { it.status != SessionStatus.IN_PROGRESS }
        }
        val globalHasInProgressSessions = deployments.any { deployment ->
            deployment.sessionHistory.any { it.status == SessionStatus.IN_PROGRESS }
        }
        val marketDataCaptureActive = selected?.let { instance ->
            SessionMarketDataCapture.activeForDeployment(instance.id) != null
        } == true

        _uiState.update {
            StrategiesUiState(
                filteredRows = listRows,
                filteredSummary = filteredSummary,
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
                addDialogPrefill = addDialogPrefill,
                showImportDialog = showImportDialog,
                symbolImport = symbolImport,
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
                            historicRunId = recapRunId,
                            marketDataCaptureActive = marketDataCaptureActive,
                        ),
                        requireBidAskForFills = requiresBidAskForFills &&
                            instance.status == DeploymentStatus.RUNNING &&
                            touchTurnOrderLifecycleFor(instance, showSessionRecap = false, recapRunId = null)
                                ?.phase != TouchTurnOrderLifecyclePhase.NOT_PLACED
                    )
                },
                liveSessionTrades = selected?.let { instance ->
                    if (instance.status != DeploymentStatus.RUNNING && !showSessionRecap) {
                        null
                    } else {
                        LiveSessionTradesUiMapper.forDeployment(
                            instance = instance,
                            liveFills = brokerFills,
                            brokerPosition = SymbolMarkets.findOpenPosition(instance, brokerPositions),
                            recapRunId = recapRunId,
                        )
                    }
                },
                touchTurnLiveOrderChart = buildTouchTurnLiveOrderChart(selected),
                touchTurnFormingBarPriceChart = buildTouchTurnFormingBarPriceChart(selected),
                startBlockedAlert = startBlockedAlert,
                globalAutoStartEnabled = state.globalAutoStartEnabled,
                tradingPanelShowsSessionRecap = showSessionRecap,
                tradingPanelRecapRunId = recapRunId,
                tradingPanelShowsLiveMarketQuotes = selected?.let { instance ->
                    TradingPanelRecap.showsLiveMarketQuotes(
                        instance,
                        state.tradingPanelDismissedRecapSessionId,
                        historicRunId = recapRunId,
                        marketDataCaptureActive = marketDataCaptureActive,
                    )
                } == true,
                touchTurnPipelineGraph = touchTurnPipelineGraph,
                touchTurnOrderLifecycle = touchTurnOrderLifecycle,
                touchTurnPrepare = touchTurnPrepare,
                globalClosedSessionHistoryCount = globalClosedSessionHistoryCount,
                globalHasInProgressSessions = globalHasInProgressSessions,
                sessionMarketDataCapture = selected?.let { instance ->
                    SessionMarketDataCapture.activeForDeployment(instance.id)?.let { capture ->
                        SessionMarketDataCaptureUi(
                            sessionId = capture.sessionId,
                            symbol = capture.symbol
                        )
                    }
                },
            )
        }
    }

    private fun touchTurnOrderLifecycleFor(
        instance: StrategyDeployment,
        showSessionRecap: Boolean,
        recapRunId: String? = null,
    ): TouchTurnOrderLifecycleUi? {
        if (!instance.isTouchTurn) return null
        val sessionEnded = instance.status != DeploymentStatus.RUNNING && showSessionRecap
        val live = LiveExecutionUiMapper.toLiveState(instance)
        val inActiveTrade = live?.state == ExecutionState.FILLED && live.showPanel
        val recapRun = if (sessionEnded) instance.touchTurnRecapRun(recapRunId) else null
        val session = if (sessionEnded) {
            instance.touchTurnAnalysisSessionForRun(recapRun)
        } else {
            instance.touchTurnSession
        }
        val hasOpenPosition = SymbolMarkets.hasOpenPosition(instance, brokerPositions)
        val hasOpenOrders = SymbolMarkets.hasOpenOrders(instance, brokerOpenOrders)
        val sessionTrades = TouchTurnPipelineUiMapper.liveSessionTrades(instance, brokerFills)
        return TouchTurnOrderLifecycleResolver.resolve(
            session = session,
            hasOpenPosition = hasOpenPosition,
            hasOpenOrders = hasOpenOrders,
            inActiveTrade = inActiveTrade,
            sessionEnded = sessionEnded,
            hasSessionTrades = sessionTrades.isNotEmpty()
        )
    }

    private fun recordTouchTurnLiveChartSamples() {
        recordTouchTurnLivePrices()
        recordTouchTurnEntryApproach()
    }

    private fun recordTouchTurnLivePrices() {
        val now = tradingClock.nowEpochMillis()
        for (deployment in deployments) {
            if (!deployment.isTouchTurn) continue
            if (deployment.status != DeploymentStatus.RUNNING) continue
            val session = deployment.touchTurnSession ?: continue
            val recordForForming = TouchTurnFormingBarPriceChartUiMapper.shouldRecordPrices(session)
            val recordForOrders = TouchTurnLiveOrderChartUiMapper.shouldRecordPrices(session)
            if (!recordForForming && !recordForOrders) continue
            val price = touchTurnChartPrice(deployment) ?: continue
            touchTurnPriceHistoryFor(deployment.symbol).record(now, price)
        }
    }

    private fun recordTouchTurnEntryApproach() {
        for (deployment in deployments) {
            if (!deployment.isTouchTurn) continue
            if (deployment.status != DeploymentStatus.RUNNING) continue
            val session = deployment.touchTurnSession ?: continue
            val setup = session.setup ?: continue
            val recordForForming = TouchTurnFormingBarPriceChartUiMapper.shouldRecordPrices(session)
            val recordForOrders = TouchTurnLiveOrderChartUiMapper.shouldRecordPrices(session)
            if (!recordForForming && !recordForOrders) continue
            val quote = LiveMarkPriceResolver.quoteForSymbol(deployment.symbol, brokerQuotes) ?: continue
            val fillGap = TouchTurnQuoteStripFormat.fillGap(
                entryPrice = setup.entry,
                entrySide = setup.side,
                bid = quote.bid,
                ask = quote.ask
            ) ?: continue
            val fillPrice = TouchTurnQuoteStripUiMapper.fillPriceForGap(
                entrySide = setup.side,
                bid = quote.bid,
                ask = quote.ask
            ) ?: continue
            val sessionId = deployment.inProgressSession()?.id ?: continue
            val tracker = touchTurnEntryApproachTrackerFor(deployment.id)
            tracker.bindSession(sessionId)
            tracker.record(fillGap, fillPrice)
        }
    }

    private fun pruneTouchTurnPriceHistories() {
        val activeDeployments = deployments.filter {
            it.isTouchTurn && it.status == DeploymentStatus.RUNNING
        }
        val activeSymbols = activeDeployments
            .map { SymbolMarkets.normalizeSymbol(it.symbol) }
            .toSet()
        touchTurnPriceHistories.keys.retainAll(activeSymbols)
        val activeDeploymentIds = activeDeployments.map { it.id }.toSet()
        touchTurnEntryApproachTrackers.keys.retainAll(activeDeploymentIds)
    }

    private fun closestApproachFor(deployment: StrategyDeployment): TouchTurnClosestApproachUi? {
        deployment.inProgressSession()?.id?.let { sessionId ->
            touchTurnEntryApproachTrackerFor(deployment.id).bindSession(sessionId)
        }
        return touchTurnEntryApproachTrackers[deployment.id]?.snapshot()
    }

    private fun buildTouchTurnFormingBarPriceChart(
        instance: StrategyDeployment?
    ): TouchTurnLiveOrderChartUiState? {
        val deployment = instance ?: return null
        if (deployment.status != DeploymentStatus.RUNNING) return null
        val session = deployment.touchTurnSession ?: return null
        val norm = SymbolMarkets.normalizeSymbol(deployment.symbol)
        val history = touchTurnPriceHistories[norm]?.snapshot().orEmpty()
        val currentPrice = touchTurnChartPrice(deployment)
        return TouchTurnFormingBarPriceChartUiMapper.build(
            deployment = deployment,
            session = session,
            priceHistory = history,
            currentPrice = currentPrice,
            statusHint = touchTurnChartStatusHint(deployment, session),
            statusHintIsWarning = TouchTurnTrailingStopWarnings.validationError(session) != null,
            quote = LiveMarkPriceResolver.quoteForSymbol(deployment.symbol, brokerQuotes),
            closestApproach = closestApproachFor(deployment)
        )
    }

    private fun fillReadinessHint(symbol: String): String? =
        LiveMarkPriceResolver.fillReadinessHint(
            quote = LiveMarkPriceResolver.quoteForSymbol(symbol, brokerQuotes),
            requiresBidAskForFills = requiresBidAskForFills
        )

    private fun touchTurnChartPrice(deployment: StrategyDeployment): Double? {
        val session = deployment.touchTurnSession ?: return null
        val hasOpenPosition = SymbolMarkets.hasOpenPosition(deployment, brokerPositions)
        return LiveMarkPriceResolver.resolveForTouchTurnChart(
            symbol = deployment.symbol,
            positions = brokerPositions,
            quotes = brokerQuotes,
            entrySide = session.setup?.side,
            ordersPlaced = session.ordersPlacedForSession,
            inPosition = hasOpenPosition
        )
    }

    private fun buildTouchTurnLiveOrderChart(
        instance: StrategyDeployment?
    ): TouchTurnLiveOrderChartUiState? {
        val deployment = instance ?: return null
        if (!deployment.isTouchTurn) return null
        if (deployment.status != DeploymentStatus.RUNNING) return null
        val session = deployment.touchTurnSession ?: return null
        val lifecycle = touchTurnOrderLifecycleFor(deployment, showSessionRecap = false) ?: return null
        if (!lifecycle.showLiveOrderChart) return null
        val symbolOrders = brokerOpenOrders.filter {
            SymbolMarkets.symbolsMatch(deployment.symbol, it.symbol)
        }

        val norm = SymbolMarkets.normalizeSymbol(deployment.symbol)
        val history = touchTurnPriceHistories[norm]?.snapshot().orEmpty()
        val currentPrice = touchTurnChartPrice(deployment)
        val sessionTrades = TouchTurnPipelineUiMapper.liveSessionTrades(deployment, brokerFills)
        val executedLevels = TouchTurnExecutedBracketLegs.resolve(
            trades = sessionTrades,
            plannedBracket = session.plannedBracket,
            bracketSetup = session.setup,
            sessionPnl = sessionTrades.sessionRealizedPnL().takeIf { sessionTrades.isNotEmpty() },
            persistedLegs = session.executedBracketLegs
        )
        return TouchTurnLiveOrderChartUiMapper.build(
            symbol = deployment.symbol,
            currencyCode = session.currencyCode,
            priceHistory = history,
            currentPrice = currentPrice,
            openOrders = symbolOrders,
            plannedBracket = session.plannedBracket,
            bracketSetup = session.setup,
            statusHint = touchTurnChartStatusHint(deployment, session),
            statusHintIsWarning = TouchTurnTrailingStopWarnings.validationError(session) != null,
            quote = LiveMarkPriceResolver.quoteForSymbol(deployment.symbol, brokerQuotes),
            closestApproach = closestApproachFor(deployment),
            executedLevels = executedLevels
        )
    }

    private fun touchTurnChartStatusHint(
        deployment: StrategyDeployment,
        session: TouchTurnSessionContext
    ): String? = TouchTurnTrailingStopWarnings.combineChartHints(
        fillReadinessHint(deployment.symbol),
        TouchTurnTrailingStopWarnings.chartHint(session)
    )

    private fun recordTouchTurnEngineSync(
        deploymentId: String,
        trigger: String,
        triggerDetails: Map<String, String> = emptyMap()
    ) {
        val instance = deployments.find { it.id == deploymentId } ?: return
        if (!instance.isTouchTurn) return
        val ctx = TouchTurnPipelineUiMapper.liveContext(
            instance = instance,
            brokerPositions = brokerPositions,
            brokerOpenOrders = brokerOpenOrders,
            brokerFills = brokerFills,
            nowEpochMillis = tradingClock.nowEpochMillis()
        )
        TouchTurnStatusBreadcrumbMapper.graph(
            instance = instance,
            hasOpenPosition = ctx.hasOpenPosition,
            hasOpenOrders = ctx.hasOpenOrders,
            sessionTrades = ctx.sessionTrades,
            nowEpochMillis = ctx.nowEpochMillis,
            syncTrigger = trigger,
            syncTriggerDetails = triggerDetails + brokerDiagnosticsDetails(instance, ctx.hasOpenOrders)
        )
    }

    private fun brokerDiagnosticsDetails(
        instance: StrategyDeployment,
        hasOpenOrders: Boolean
    ): Map<String, String> {
        val symbolOrders = SymbolMarkets.openOrdersForDeployment(instance, brokerOpenOrders)
        val lifecycle = touchTurnOrderLifecycleFor(instance, showSessionRecap = false)
        return mapOf(
            "broker.openOrdersForSymbol" to symbolOrders.size.toString(),
            "broker.openOrdersTotal" to brokerOpenOrders.size.toString(),
            "broker.hasOpenOrders" to hasOpenOrders.toString(),
            "broker.orderIdsForSymbol" to symbolOrders.joinToString(",") { "${it.orderId}:${it.status}" }
                .ifEmpty { "none" },
            "ui.orderLifecyclePhase" to (lifecycle?.phase?.name ?: "null"),
            "ui.showLiveOrdersPanel" to (lifecycle?.showLiveOrdersPanel?.toString() ?: "null")
        )
    }

    private fun logBrokerOpenOrdersForRunningTouchTurn(
        orders: List<WorkingOrder>,
        trigger: String
    ) {
        for (deployment in deployments) {
            if (!deployment.isTouchTurn) continue
            if (deployment.status != DeploymentStatus.RUNNING) continue
            val symbolOrders = SymbolMarkets.openOrdersForDeployment(deployment, orders)
            val fingerprint = symbolOrders.joinToString("|") { "${it.orderId}:${it.status}:${it.remaining}" }
            val last = lastBrokerOpenOrdersFingerprintByDeployment[deployment.id]
            if (last == fingerprint) continue
            lastBrokerOpenOrdersFingerprintByDeployment[deployment.id] = fingerprint
            SessionTrace.brokerOpenOrders(
                deploymentId = deployment.id,
                sessionId = deployment.inProgressSession()?.id,
                symbol = deployment.symbol,
                ordersForSymbol = symbolOrders,
                ordersTotal = orders.size,
                trigger = trigger
            )
            recordTouchTurnEngineSync(
                deploymentId = deployment.id,
                trigger = "broker_open_orders_changed",
                triggerDetails = mapOf(
                    "countForSymbol" to symbolOrders.size.toString(),
                    "countTotal" to orders.size.toString()
                )
            )
        }
    }

    private fun activeSessionId(deploymentId: String): String? =
        deployments.find { it.id == deploymentId }?.inProgressSession()?.id
}
