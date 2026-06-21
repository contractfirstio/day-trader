package daytrader.presentation.watchlist

import daytrader.broker.SymbolMarkets
import daytrader.data.WatchlistPriceScanService
import daytrader.data.WatchlistRepository
import daytrader.data.WatchlistScanResult
import daytrader.data.ReversalScoreBatchResult
import daytrader.data.ReversalScoreCalculationStage
import daytrader.data.ReversalScoreService
import daytrader.data.StrategyDeploymentRepository
import daytrader.data.StrategyCatalog
import daytrader.domain.DEFAULT_WATCHLIST_ID
import daytrader.domain.DeploymentMarket
import daytrader.domain.InstrumentIdentity
import daytrader.domain.InstrumentListingCandidates
import daytrader.domain.InstrumentResolution
import daytrader.domain.InstrumentResolveLog
import daytrader.domain.PlanSizingMode
import daytrader.domain.ProximityThresholdMode
import daytrader.domain.StrategyType
import daytrader.domain.TradeSide
import daytrader.domain.Watchlist
import daytrader.domain.WatchlistEntry
import daytrader.domain.WatchlistBracketOrderPlanner
import daytrader.domain.WatchlistLabels
import daytrader.domain.WatchlistStrategyLinks
import daytrader.domain.WatchlistPlanDiaryEntry
import daytrader.domain.WatchlistPlanDiaryNotifications
import daytrader.domain.WatchlistTradePlan
import daytrader.domain.WatchlistTradePlanCalculator
import daytrader.domain.newWatchlistLabel
import daytrader.domain.newWatchlistEntry
import daytrader.domain.newWatchlistPlanDiaryEntryId
import daytrader.gateway.BrokerGateway
import daytrader.gateway.BrokerKind
import daytrader.gateway.GatewayConnectionState
import daytrader.gateway.TouchTurnBracketAck
import daytrader.domain.OhlcBar
import daytrader.gateway.AccountPosition
import daytrader.gateway.BrokerFill
import daytrader.gateway.LiveQuote
import daytrader.gateway.WorkingOrder
import daytrader.presentation.Formatters
import daytrader.presentation.strategies.LiveMarkPriceResolver
import daytrader.presentation.strategies.LivePriceTickHistory
import daytrader.presentation.strategies.TouchTurnQuoteStripUiMapper
import daytrader.platform.currentSessionDateIso
import daytrader.presentation.positions.SortDirection
import daytrader.presentation.navigation.AppScreen
import daytrader.presentation.ui.UiCoroutineScopes
import daytrader.presentation.ui.launchUiAction
import daytrader.presentation.ui.safeUiEmit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.sample
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.FlowPreview
import kotlin.time.Duration.Companion.milliseconds

@OptIn(FlowPreview::class)
class WatchlistViewModel(
    private val repository: WatchlistRepository,
    private val strategyDeploymentRepository: StrategyDeploymentRepository? = null,
    brokerGateway: BrokerGateway? = null,
    touchTurnSessionGateway: BrokerGateway? = null,
    private val brokerKind: BrokerKind = BrokerKind.EMULATOR,
    private val ensureLiveMarketData: ((String, InstrumentIdentity?) -> Unit)? = null,
    private val scanService: WatchlistPriceScanService = WatchlistPriceScanService(),
    private val reversalScoreService: ReversalScoreService = ReversalScoreService(),
    private val onRequestStrategyDeploymentCreate: ((WatchlistStrategyCreateRequest) -> Unit)? = null,
    private val onDeleteLinkedDeployment: ((String) -> Unit)? = null,
    scope: CoroutineScope = UiCoroutineScopes.forScreen(AppScreen.WATCHLIST, "WatchlistViewModel"),
) {
    private val scope = scope
    private val executionGateway = brokerGateway
    private val marketDataGateway = touchTurnSessionGateway ?: brokerGateway

    private var watchlists: List<Watchlist> = emptyList()
    private var strategyDeployments: List<daytrader.domain.StrategyDeployment> = emptyList()
    private var selectedWatchlistId: String = DEFAULT_WATCHLIST_ID
    private var executionConnection: GatewayConnectionState = GatewayConnectionState.Disconnected
    private var marketDataConnection: GatewayConnectionState = GatewayConnectionState.Disconnected
    private var showAddDialog = false
    private var tradePlansEditorDraft: WatchlistTradePlansEditorUi? = null
    private var planDiaryEditorDraft: WatchlistPlanDiaryEditorUi? = null
    private var dueDiaryNotificationQueue: List<WatchlistPlanDiaryNotifications.DueNotification> = emptyList()
    private var diaryNotificationSnoozedForView: String? = null
    private var bracketOrderDraft: WatchlistBracketOrderUi? = null
    private var pendingBracketSymbol: String? = null
    private var bracketSubmitGeneration = 0
    private var sortColumn = WatchlistSortColumn.SYMBOL
    private var sortDirection = WatchlistSortDirection.ASCENDING
    private var activeGroupFilter: WatchlistGroupFilter = WatchlistGroupFilter.All
    private var activeStrategyFilter: WatchlistStrategyFilter = WatchlistStrategyFilter.All
    private var scanInProgress = false
    private var scanProgress: WatchlistScanProgressUi? = null
    private var lastScanResult: WatchlistScanResult? = null
    private var reversalScoreInProgress = false
    private var reversalScoreProgress: ReversalScoreProgressUi? = null
    private var reversalScoreLoadingEntryId: String? = null
    private var lastReversalScoreResult: ReversalScoreBatchResult? = null
    private var reversalScoreInsight: WatchlistReversalScoreInsightUi? = null
    private var entryChartsEntryId: String? = null
    private var entryDailyBars: List<OhlcBar> = emptyList()
    private var entryDailyBarsLoading = false
    private var entryDailyBarsError: String? = null
    private var brokerQuotes: Map<String, LiveQuote> = emptyMap()
    private var brokerOpenOrders: List<WorkingOrder> = emptyList()
    private var brokerFills: List<BrokerFill> = emptyList()
    private var brokerPositions: List<AccountPosition> = emptyList()
    private val entryLivePriceHistory = LivePriceTickHistory(maxPoints = 450, minIntervalMillis = 2_000L)

    private val _uiState = MutableStateFlow(WatchlistUiState())
    val uiState: StateFlow<WatchlistUiState> = _uiState.asStateFlow()

    init {
        repository.watchlists
            .onEach { lists ->
                watchlists = lists
                if (lists.none { it.id == selectedWatchlistId }) {
                    selectedWatchlistId = lists.firstOrNull()?.id ?: DEFAULT_WATCHLIST_ID
                }
                refreshDueDiaryNotifications()
                emitUiState()
            }
            .launchIn(scope)

        strategyDeploymentRepository?.deployments
            ?.onEach { deployments ->
                strategyDeployments = deployments
                tradePlansEditorDraft?.let { draft ->
                    tradePlansEditorDraft = refreshEditorStrategies(draft, draft.assignedStrategyDeploymentIds)
                }
                emitUiState()
            }
            ?.launchIn(scope)

        executionGateway?.connectionState
            ?.onEach { state ->
                executionConnection = state
                if (marketDataGateway === executionGateway) {
                    marketDataConnection = state
                }
                emitUiState(UiRefreshScope.LiveMarket)
            }
            ?.launchIn(scope)

        executionGateway?.touchTurnBracketPlacements
            ?.onEach(::handleBracketPlacementAck)
            ?.launchIn(scope)

        executionGateway?.openOrders
            ?.onEach { orders ->
                brokerOpenOrders = orders
                recordEntryLivePrices()
                emitUiState(UiRefreshScope.BrokerSnapshot)
            }
            ?.launchIn(scope)

        executionGateway?.fills
            ?.onEach { fills ->
                brokerFills = fills
                syncExecutedBracketLegsFromFills()
                recordEntryLivePrices()
                emitUiState(UiRefreshScope.BrokerSnapshot)
            }
            ?.launchIn(scope)

        executionGateway?.positions
            ?.onEach { positions ->
                brokerPositions = positions
                recordEntryLivePrices()
                emitUiState(UiRefreshScope.BrokerSnapshot)
            }
            ?.launchIn(scope)

        if (marketDataGateway != null && marketDataGateway !== executionGateway) {
            marketDataGateway.connectionState
                ?.onEach { state ->
                    marketDataConnection = state
                    emitUiState(UiRefreshScope.LiveMarket)
                }
                ?.launchIn(scope)
        }

        marketDataGateway?.quotes?.let { quotesFlow ->
            quotesFlow
                .onEach { quotes ->
                    brokerQuotes = quotes
                    recordEntryLivePrices()
                }
                .launchIn(scope)
            quotesFlow
                .sample(QUOTE_UI_REFRESH_INTERVAL_MS.milliseconds)
                .onEach { emitUiState(UiRefreshScope.LiveMarket) }
                .launchIn(scope)
        }
    }

    private enum class UiRefreshScope {
        Full,
        LiveMarket,
        BrokerSnapshot,
    }

    private fun emitUiState(scope: UiRefreshScope = UiRefreshScope.Full) {
        safeUiEmit(AppScreen.WATCHLIST, "emitUiState") {
            when (scope) {
                UiRefreshScope.Full -> applyFullUi()
                UiRefreshScope.LiveMarket -> applyLiveMarketUi()
                UiRefreshScope.BrokerSnapshot -> applyBrokerSnapshotUi()
            }
        }
    }

    private fun applyLiveMarketUi() {
        refreshTradePlansEditorProximity()
        refreshBracketOrderState()
        val resolvedReversalBatch = WatchlistStatusUiMapper.resolvedReversalBatch(
            inMemory = lastReversalScoreResult,
            watchlist = selectedWatchlist()
        )
        _uiState.update { current ->
            current.copy(
                connectionLabel = connectionLabel(executionConnection, marketDataConnection),
                statusStrip = WatchlistStatusUiMapper.buildStatusStrip(
                    execution = executionConnection,
                    marketData = marketDataConnection,
                    brokerKind = brokerKind,
                    lastReversalResult = resolvedReversalBatch
                ),
                entryCharts = tradePlansEditorDraft?.let(::buildEntryChartsUi),
                bracketOrderEditor = bracketOrderDraft,
            )
        }
    }

    private fun applyBrokerSnapshotUi() {
        if (tradePlansEditorDraft == null && bracketOrderDraft == null) return
        refreshBracketOrderState()
        _uiState.update { current ->
            current.copy(
                entryCharts = tradePlansEditorDraft?.let(::buildEntryChartsUi),
                bracketOrderEditor = bracketOrderDraft,
            )
        }
    }

    private fun handleBracketPlacementAck(ack: TouchTurnBracketAck) {
        val draft = bracketOrderDraft ?: return
        val pending = pendingBracketSymbol ?: return
        if (!SymbolMarkets.symbolsMatch(draft.symbol, ack.symbol)) return
        if (!SymbolMarkets.symbolsMatch(pending, ack.symbol)) return
        pendingBracketSymbol = null
        if (ack.result.isSuccess) {
            recordPlanOrderPlacement(
                entryId = draft.entryId,
                planId = draft.planId,
                orderIds = ack.orderIds,
                bracketDraft = draft
            )
            bracketOrderDraft = null
            pendingBracketSymbol = null
        } else {
            bracketOrderDraft = draft.copy(
                submitInProgress = false,
                submitResultMessage = "Bracket failed: ${ack.result.exceptionOrNull()?.message ?: "unknown error"}"
            )
        }
        emitUiState()
    }

    private fun recordPlanOrderPlacement(
        entryId: String,
        planId: String,
        orderIds: List<Int>,
        bracketDraft: WatchlistBracketOrderUi
    ) {
        val watchlist = selectedWatchlist() ?: return
        val placedAt = System.currentTimeMillis()
        val entryPrice = bracketDraft.entryPriceText.toDoubleOrNull()
        val stopPrice = bracketDraft.stopPriceText.toDoubleOrNull()
        val targetPrice = bracketDraft.targetPriceText.toDoubleOrNull()
        val quantity = bracketDraft.quantityText.toIntOrNull()
        val investmentAmount = entryPrice?.let { price -> quantity?.let { price * it } }
        repository.updateWatchlist(watchlist.id) { current ->
            current.copy(
                entries = current.entries.map { entry ->
                    if (entry.id != entryId) entry
                    else entry.copy(
                        tradePlans = entry.tradePlans.map { plan ->
                            if (plan.id != planId) plan
                            else plan.copy(
                                side = bracketDraft.side,
                                entryPrice = entryPrice ?: plan.entryPrice,
                                stopPrice = stopPrice ?: plan.stopPrice,
                                targetPrice = targetPrice ?: plan.targetPrice,
                                investmentAmount = investmentAmount ?: plan.investmentAmount,
                                orderPlacedAtEpochMs = placedAt,
                                placedOrderIds = orderIds
                            )
                        }
                    )
                }
            )
        }
        val updatedWatchlist = repository.watchlists.value.find { it.id == watchlist.id } ?: return
        val entry = updatedWatchlist.entries.find { it.id == entryId } ?: return
        if (tradePlansEditorDraft?.entryId == entryId) {
            tradePlansEditorDraft = WatchlistUiMapper.toEditorUi(entry, updatedWatchlist, strategyDeployments)
        }
    }

    fun onReactivatePlan(planId: String) {
        val draft = tradePlansEditorDraft ?: return
        clearPlanOrderPlacement(draft.entryId, planId)
        emitUiState()
    }

    fun onOpenPlanDiary(planId: String) {
        val entryId = tradePlansEditorDraft?.entryId ?: return
        openPlanDiary(entryId = entryId, planId = planId)
    }

    fun onDismissPlanDiary() {
        planDiaryEditorDraft = null
        diaryNotificationSnoozedForView = null
        emitUiState()
    }

    fun onStartAddDiaryEntry() {
        val draft = planDiaryEditorDraft ?: return
        planDiaryEditorDraft = draft.copy(
            composingEntry = true,
            editingEntryId = null,
            draftBody = "",
            draftNotifyOnDate = currentSessionDateIso(),
            draftNotifyEnabled = false
        )
        emitUiState()
    }

    fun onStartEditDiaryEntry(diaryEntryId: String) {
        val draft = planDiaryEditorDraft ?: return
        val entry = findEntry(draft.entryId) ?: return
        val plan = entry.tradePlans.find { it.id == draft.planId } ?: return
        val diaryEntry = plan.diaryEntries.find { it.id == diaryEntryId } ?: return
        planDiaryEditorDraft = draft.copy(
            composingEntry = false,
            editingEntryId = diaryEntryId,
            draftBody = diaryEntry.body,
            draftNotifyOnDate = diaryEntry.notifyOnDate.orEmpty(),
            draftNotifyEnabled = diaryEntry.notifyOnDate != null,
            focusedEntryId = diaryEntryId
        )
        emitUiState()
    }

    fun onCancelDiaryDraft() {
        val draft = planDiaryEditorDraft ?: return
        planDiaryEditorDraft = draft.copy(
            composingEntry = false,
            editingEntryId = null,
            draftBody = "",
            draftNotifyOnDate = "",
            draftNotifyEnabled = false
        )
        emitUiState()
    }

    fun onDiaryDraftBodyChange(value: String) {
        val draft = planDiaryEditorDraft ?: return
        planDiaryEditorDraft = draft.copy(draftBody = value)
        emitUiState()
    }

    fun onDiaryDraftNotifyEnabledChange(enabled: Boolean) {
        val draft = planDiaryEditorDraft ?: return
        planDiaryEditorDraft = draft.copy(
            draftNotifyEnabled = enabled,
            draftNotifyOnDate = when {
                enabled && draft.draftNotifyOnDate.isBlank() -> currentSessionDateIso()
                else -> draft.draftNotifyOnDate
            }
        )
        emitUiState()
    }

    fun onDiaryDraftNotifyDateChange(value: String) {
        val draft = planDiaryEditorDraft ?: return
        planDiaryEditorDraft = draft.copy(draftNotifyOnDate = value)
        emitUiState()
    }

    fun onSaveDiaryEntry() {
        val draft = planDiaryEditorDraft ?: return
        val body = draft.draftBody.trim()
        if (body.isEmpty()) return
        val notifyOnDate = if (draft.draftNotifyEnabled) {
            normalizeNotifyOnDate(draft.draftNotifyOnDate) ?: return
        } else {
            null
        }
        if (draft.editingEntryId != null) {
            mutatePlanDiaryEntries(draft.entryId, draft.planId) { entries ->
                entries.map { entry ->
                    if (entry.id != draft.editingEntryId) entry
                    else entry.copy(
                        body = body,
                        notifyOnDate = notifyOnDate,
                        notificationDismissed = false
                    )
                }
            }
        } else {
            val diaryEntry = WatchlistPlanDiaryEntry(
                id = newWatchlistPlanDiaryEntryId(),
                body = body,
                createdAtEpochMs = System.currentTimeMillis(),
                notifyOnDate = notifyOnDate
            )
            mutatePlanDiaryEntries(draft.entryId, draft.planId) { it + diaryEntry }
        }
        planDiaryEditorDraft = planDiaryEditorDraft?.copy(
            composingEntry = false,
            editingEntryId = null,
            draftBody = "",
            draftNotifyOnDate = "",
            draftNotifyEnabled = false
        )
        refreshDueDiaryNotifications()
        emitUiState()
    }

    fun onDeleteDiaryEntry(diaryEntryId: String) {
        val draft = planDiaryEditorDraft ?: return
        mutatePlanDiaryEntries(draft.entryId, draft.planId) { entries ->
            entries.filterNot { it.id == diaryEntryId }
        }
        if (diaryNotificationSnoozedForView == diaryEntryId) {
            diaryNotificationSnoozedForView = null
        }
        planDiaryEditorDraft = planDiaryEditorDraft?.let { current ->
            val wasEditing = current.editingEntryId == diaryEntryId
            current.copy(
                composingEntry = false,
                editingEntryId = current.editingEntryId?.takeUnless { it == diaryEntryId },
                focusedEntryId = current.focusedEntryId?.takeUnless { it == diaryEntryId },
                draftBody = if (wasEditing) "" else current.draftBody,
                draftNotifyOnDate = if (wasEditing) "" else current.draftNotifyOnDate,
                draftNotifyEnabled = if (wasEditing) false else current.draftNotifyEnabled
            )
        }
        refreshDueDiaryNotifications()
        emitUiState()
    }

    fun onDismissDiaryReminder(diaryEntryId: String) {
        val draft = planDiaryEditorDraft ?: return
        dismissDiaryReminder(draft.entryId, draft.planId, diaryEntryId)
        if (diaryNotificationSnoozedForView == diaryEntryId) {
            diaryNotificationSnoozedForView = null
        }
        refreshDueDiaryNotifications()
        emitUiState()
    }

    fun onViewDiaryNotification() {
        val notification = dueDiaryNotificationQueue.firstOrNull() ?: return
        val watchlist = watchlists.find { wl -> wl.entries.any { it.id == notification.entryId } }
        if (watchlist != null) {
            selectedWatchlistId = watchlist.id
        }
        if (tradePlansEditorDraft?.entryId != notification.entryId) {
            val entry = watchlists.flatMap { it.entries }.find { it.id == notification.entryId } ?: return
            val wl = watchlists.find { w -> w.entries.any { it.id == entry.id } } ?: return
            tradePlansEditorDraft = refreshEditorLabels(
                WatchlistUiMapper.toEditorUi(entry = entry, watchlist = wl, deployments = strategyDeployments)
            )
        }
        openPlanDiary(
            entryId = notification.entryId,
            planId = notification.planId,
            focusedEntryId = notification.diaryEntry.id
        )
        diaryNotificationSnoozedForView = notification.diaryEntry.id
    }

    fun onDismissDiaryNotification() {
        val notification = dueDiaryNotificationQueue.firstOrNull() ?: return
        if (diaryNotificationSnoozedForView == notification.diaryEntry.id) {
            diaryNotificationSnoozedForView = null
        }
        dismissDiaryReminder(
            entryId = notification.entryId,
            planId = notification.planId,
            diaryEntryId = notification.diaryEntry.id
        )
        refreshDueDiaryNotifications()
        emitUiState()
    }

    private fun openPlanDiary(entryId: String, planId: String, focusedEntryId: String? = null) {
        val entry = findEntry(entryId) ?: return
        val plan = entry.tradePlans.find { it.id == planId } ?: return
        planDiaryEditorDraft = WatchlistUiMapper.toDiaryEditorUi(
            entry = entry,
            plan = plan,
            focusedEntryId = focusedEntryId
        )
        emitUiState()
    }

    private fun dismissDiaryReminder(entryId: String, planId: String, diaryEntryId: String) {
        mutatePlanDiaryEntries(entryId, planId) { entries ->
            entries.map { entry ->
                if (entry.id != diaryEntryId) entry
                else entry.copy(notificationDismissed = true)
            }
        }
    }

    private fun mutatePlanDiaryEntries(
        entryId: String,
        planId: String,
        transform: (List<WatchlistPlanDiaryEntry>) -> List<WatchlistPlanDiaryEntry>
    ) {
        val watchlist = selectedWatchlist() ?: return
        repository.updateWatchlist(watchlist.id) { current ->
            current.copy(
                entries = current.entries.map { entry ->
                    if (entry.id != entryId) entry
                    else entry.copy(
                        tradePlans = entry.tradePlans.map { plan ->
                            if (plan.id != planId) plan
                            else plan.copy(diaryEntries = transform(plan.diaryEntries))
                        }
                    )
                }
            )
        }
        watchlists = repository.watchlists.value
        refreshDiaryRelatedEditors(entryId, planId)
    }

    private fun refreshDiaryRelatedEditors(entryId: String, planId: String) {
        val watchlist = selectedWatchlist() ?: return
        val entry = watchlist.entries.find { it.id == entryId } ?: return
        val plan = entry.tradePlans.find { it.id == planId } ?: return
        planDiaryEditorDraft?.let { draft ->
            if (draft.entryId == entryId && draft.planId == planId) {
                val entryIds = plan.diaryEntries.map { it.id }.toSet()
                planDiaryEditorDraft = WatchlistUiMapper.toDiaryEditorUi(
                    entry = entry,
                    plan = plan,
                    focusedEntryId = draft.focusedEntryId?.takeIf { it in entryIds },
                    composingEntry = draft.composingEntry && draft.editingEntryId == null,
                    editingEntryId = draft.editingEntryId?.takeIf { it in entryIds },
                    draftBody = draft.draftBody,
                    draftNotifyOnDate = draft.draftNotifyOnDate,
                    draftNotifyEnabled = draft.draftNotifyEnabled
                )
            }
        }
        if (tradePlansEditorDraft?.entryId == entryId) {
            tradePlansEditorDraft = refreshEditorLabels(
                WatchlistUiMapper.toEditorUi(entry = entry, watchlist = watchlist, deployments = strategyDeployments)
            )
        }
    }

    private fun refreshDueDiaryNotifications() {
        dueDiaryNotificationQueue = WatchlistPlanDiaryNotifications.findDue(
            watchlists = watchlists,
            todayIso = currentSessionDateIso()
        )
    }

    private fun normalizeNotifyOnDate(raw: String): String? {
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) return null
        if (!trimmed.matches(Regex("""\d{4}-\d{2}-\d{2}"""))) return null
        return trimmed
    }

    private fun clearPlanOrderPlacement(entryId: String, planId: String) {
        val watchlist = selectedWatchlist() ?: return
        repository.updateWatchlist(watchlist.id) { current ->
            current.copy(
                entries = current.entries.map { entry ->
                    if (entry.id != entryId) entry
                    else entry.copy(
                        tradePlans = entry.tradePlans.map { plan ->
                            if (plan.id != planId) plan else plan.withoutOrderPlacement()
                        }
                    )
                }
            )
        }
        val updatedWatchlist = repository.watchlists.value.find { it.id == watchlist.id } ?: return
        val entry = updatedWatchlist.entries.find { it.id == entryId } ?: return
        if (tradePlansEditorDraft?.entryId == entryId) {
            tradePlansEditorDraft = WatchlistUiMapper.toEditorUi(entry, updatedWatchlist, strategyDeployments)
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

    fun onHeaderClick(column: WatchlistSortColumn) {
        if (sortColumn == column) {
            sortDirection = if (sortDirection == WatchlistSortDirection.ASCENDING) {
                WatchlistSortDirection.DESCENDING
            } else {
                WatchlistSortDirection.ASCENDING
            }
        } else {
            sortColumn = column
            sortDirection = WatchlistSortDirection.ASCENDING
        }
        emitUiState()
    }

    fun onGroupFilterSelected(filter: WatchlistGroupFilter) {
        activeGroupFilter = filter
        emitUiState()
    }

    fun onCheckEntryProximity() {
        val gateway = marketDataGateway ?: executionGateway ?: return
        val watchlist = selectedWatchlist() ?: return
        if (scanInProgress || reversalScoreInProgress || watchlist.entries.isEmpty()) return
        scope.launchUiAction(AppScreen.WATCHLIST, "onCheckEntryProximity") {
            scanInProgress = true
            scanProgress = WatchlistScanProgressUi(completed = 0, total = watchlist.entries.size, symbol = "")
            emitUiState()
            try {
                val result = scanService.scan(
                    entries = watchlist.entries,
                    gateway = gateway
                ) { progress ->
                    scanProgress = WatchlistScanProgressUi(
                        completed = progress.completed,
                        total = progress.total,
                        symbol = progress.symbol
                    )
                    emitUiState()
                }
                val activeWatchlist = selectedWatchlist() ?: return@launchUiAction
                result.entryResults.forEach { entryResult ->
                    repository.updateEntry(activeWatchlist.id, entryResult.entryId) { entry ->
                        entry.copy(
                            lastScannedPrice = entryResult.price,
                            lastScannedAtEpochMs = result.scannedAtEpochMs
                        )
                    }
                }
                lastScanResult = result
            } finally {
                scanInProgress = false
                scanProgress = null
                emitUiState()
            }
        }
    }

    fun onCalculateReversalScores() {
        val gateway = marketDataGateway ?: executionGateway ?: return
        val watchlist = selectedWatchlist() ?: return
        val entriesToScore = entriesMatchingFilters(watchlist.entries)
        if (reversalScoreInProgress || scanInProgress || entriesToScore.isEmpty()) return
        scope.launchUiAction(AppScreen.WATCHLIST, "onCalculateReversalScores") {
            reversalScoreInProgress = true
            reversalScoreProgress = null
            reversalScoreLoadingEntryId = null
            emitUiState()
            try {
                val result = reversalScoreService.calculateScores(
                    entries = entriesToScore,
                    gateway = gateway
                ) { progress ->
                    reversalScoreProgress = WatchlistStatusUiMapper.buildReversalScoreProgress(progress)
                    if (progress.stage == ReversalScoreCalculationStage.SYMBOLS) {
                        reversalScoreLoadingEntryId = progress.entryId
                    }
                    emitUiState()
                }
                val activeWatchlist = selectedWatchlist() ?: return@launchUiAction
                result.entryResults.forEach { entryResult ->
                    val computed = entryResult.result ?: return@forEach
                    val scoredAtEpochMs = System.currentTimeMillis()
                    repository.updateEntry(activeWatchlist.id, entryResult.entryId) { entry ->
                        entry.copy(
                            reversalScore = computed.compositeScore,
                            reversalScoreAtEpochMs = scoredAtEpochMs,
                            reversalScoreAlignmentBadge = entryResult.alignmentBadge,
                            reversalScoreInsightText = computed.insightText,
                            reversalScoreRecommendationText = computed.recommendationText
                        )
                    }
                }
                repository.updateWatchlist(activeWatchlist.id) { current ->
                    current.copy(
                        lastReversalScoreHomeMarketRegimes = result.homeMarketRegimes.map { it.toWatchlistRegime() }
                    )
                }
                lastReversalScoreResult = result
            } finally {
                reversalScoreInProgress = false
                reversalScoreProgress = null
                reversalScoreLoadingEntryId = null
                emitUiState()
            }
        }
    }

    fun onOpenReversalScoreInsight(entryId: String) {
        val entry = selectedWatchlist()?.entries?.firstOrNull { it.id == entryId } ?: return
        val score = entry.reversalScore ?: return
        val insightText = entry.reversalScoreInsightText?.takeIf { it.isNotBlank() } ?: return
        val recommendationText = entry.reversalScoreRecommendationText?.takeIf { it.isNotBlank() } ?: return
        reversalScoreInsight = WatchlistReversalScoreInsightUi(
            entryId = entry.id,
            symbol = entry.symbol,
            companyName = entry.companyName?.takeIf { it.isNotBlank() } ?: entry.symbol,
            compositeScore = score,
            contextBadgeLabel = entry.reversalScoreAlignmentBadge?.label,
            insightText = insightText,
            recommendationText = recommendationText
        )
        emitUiState()
    }

    fun onDismissReversalScoreInsight() {
        reversalScoreInsight = null
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
        scope.launchUiAction(
            screen = AppScreen.WATCHLIST,
            source = "resolveInstrumentForSymbol",
            onFailure = { error ->
                withContext(Dispatchers.Main) {
                    onResult(Result.failure(error))
                }
            },
        ) {
            val resolveGateway = marketDataGateway ?: executionGateway
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

    fun onAddEntry(
        symbol: String,
        marketZoneId: String,
        currencyCode: String,
        companyName: String?,
        instrument: InstrumentIdentity?,
        notes: String? = null
    ) {
        val watchlist = selectedWatchlist() ?: return
        val norm = daytrader.broker.SymbolMarkets.normalizeSymbol(symbol)
        if (watchlist.entries.any { daytrader.broker.SymbolMarkets.symbolsMatch(it.symbol, norm) }) {
            showAddDialog = false
            emitUiState()
            return
        }
        val entry = newWatchlistEntry(
            symbol = symbol,
            marketZoneId = marketZoneId,
            currencyCode = currencyCode,
            companyName = companyName,
            instrument = instrument,
            notes = notes
        )
        repository.addEntry(watchlist.id, entry)
        showAddDialog = false
        emitUiState()
    }

    fun onRemoveEntry(entryId: String) {
        val watchlist = selectedWatchlist() ?: return
        if (tradePlansEditorDraft?.entryId == entryId) {
            tradePlansEditorDraft = null
            planDiaryEditorDraft = null
            clearEntryCharts()
        }
        repository.removeEntry(watchlist.id, entryId)
        emitUiState()
    }

    fun onOpenTradePlans(entryId: String) {
        val entry = findEntry(entryId) ?: return
        val watchlist = selectedWatchlist() ?: return
        tradePlansEditorDraft = refreshEditorDraft(
            WatchlistUiMapper.toEditorUi(entry = entry, watchlist = watchlist, deployments = strategyDeployments)
        )
        startEntryCharts(entry)
        emitUiState()
    }

    fun onDismissTradePlans() {
        tradePlansEditorDraft = null
        planDiaryEditorDraft = null
        bracketOrderDraft = null
        pendingBracketSymbol = null
        clearEntryCharts()
        emitUiState()
    }

    fun onUpdatePlanSide(planId: String, side: TradeSide) {
        updatePlanEditor(planId) { it.copy(side = side) }
    }

    fun onUpdatePlanSizingMode(planId: String, sizingMode: PlanSizingMode) {
        updatePlanEditor(planId) { it.copy(sizingMode = sizingMode) }
    }

    fun onUpdatePlanProximityEnabled(planId: String, enabled: Boolean) {
        updatePlanEditor(planId) { editor ->
            val updated = editor.copy(proximityAlertEnabled = enabled)
            if (enabled && updated.proximityThresholdValueText.isBlank()) {
                updated.copy(proximityThresholdValueText = "1")
            } else {
                updated
            }
        }
    }

    fun onUpdatePlanProximityMode(planId: String, mode: ProximityThresholdMode) {
        updatePlanEditor(planId) { it.copy(proximityThresholdMode = mode) }
    }

    fun onUpdatePlanStopEntry(planId: String, stopEntry: Boolean) {
        updatePlanEditor(planId) { it.copy(stopEntry = stopEntry) }
    }

    fun onUpdatePlanAdjustableTrailingStop(planId: String, enabled: Boolean) {
        updatePlanEditor(planId) { it.copy(adjustableTrailingStop = enabled) }
    }

    fun onUpdatePlanField(planId: String, field: WatchlistPlanField, value: String) {
        updatePlanEditor(planId) { plan ->
            when (field) {
                WatchlistPlanField.ENTRY -> plan.copy(entryPriceText = value)
                WatchlistPlanField.STOP -> plan.copy(stopPriceText = value)
                WatchlistPlanField.TARGET -> plan.copy(targetPriceText = value)
                WatchlistPlanField.INVESTMENT -> plan.copy(investmentAmountText = value)
                WatchlistPlanField.PROXIMITY_THRESHOLD -> plan.copy(proximityThresholdValueText = value)
            }
        }
    }

    fun onEditorGroupInputChange(value: String) {
        val draft = tradePlansEditorDraft ?: return
        tradePlansEditorDraft = refreshEditorLabels(draft.copy(newGroupInput = value))
        emitUiState()
    }

    fun onAddEditorGroup(labelId: String? = null) {
        val draft = tradePlansEditorDraft ?: return
        val watchlist = selectedWatchlist() ?: return
        if (labelId != null) {
            tradePlansEditorDraft = refreshEditorLabels(
                draft.copy(
                    assignedLabelIds = WatchlistLabels.mergeLabelId(draft.assignedLabelIds, labelId),
                    newGroupInput = ""
                )
            )
            emitUiState()
            return
        }
        val normalized = WatchlistLabels.normalizeName(draft.newGroupInput) ?: return
        val pendingDomain = WatchlistUiMapper.toDomainLabels(draft.pendingLabels)
        val registry = WatchlistLabels.combinedRegistry(watchlist.labels, pendingDomain)
        val existing = WatchlistLabels.findByName(registry, normalized)
        if (existing != null) {
            tradePlansEditorDraft = refreshEditorLabels(
                draft.copy(
                    assignedLabelIds = WatchlistLabels.mergeLabelId(draft.assignedLabelIds, existing.id),
                    newGroupInput = ""
                )
            )
        } else {
            val newLabel = newWatchlistLabel(normalized) ?: return
            tradePlansEditorDraft = refreshEditorLabels(
                draft.copy(
                    pendingLabels = draft.pendingLabels + WatchlistLabelUi(newLabel.id, newLabel.name),
                    assignedLabelIds = WatchlistLabels.mergeLabelId(draft.assignedLabelIds, newLabel.id),
                    newGroupInput = ""
                )
            )
        }
        emitUiState()
    }

    fun onRemoveEditorGroup(labelId: String) {
        val draft = tradePlansEditorDraft ?: return
        tradePlansEditorDraft = refreshEditorLabels(
            draft.copy(assignedLabelIds = WatchlistLabels.removeLabelId(draft.assignedLabelIds, labelId))
        )
        emitUiState()
    }

    fun onCreateStrategyDeployment(strategyType: StrategyType) {
        val draft = tradePlansEditorDraft ?: return
        val entry = findEntry(draft.entryId) ?: return
        onRequestStrategyDeploymentCreate?.invoke(
            WatchlistStrategyCreateRequest(
                entryId = draft.entryId,
                symbol = entry.symbol,
                marketZoneId = entry.marketZoneId,
                currencyCode = entry.currencyCode,
                companyName = entry.companyName,
                instrument = entry.instrument,
                strategyType = strategyType
            )
        )
    }

    fun linkStrategyDeploymentToEntry(entryId: String, deploymentId: String) {
        val watchlist = selectedWatchlist() ?: return
        repository.updateWatchlist(watchlist.id) { current ->
            current.copy(
                entries = current.entries.map { entry ->
                    if (entry.id == entryId) {
                        entry.copy(
                            strategyDeploymentIds = WatchlistStrategyLinks.mergeDeploymentId(
                                entry.strategyDeploymentIds,
                                deploymentId
                            )
                        )
                    } else {
                        entry
                    }
                }
            )
        }
        tradePlansEditorDraft?.takeIf { it.entryId == entryId }?.let { draft ->
            tradePlansEditorDraft = refreshEditorStrategies(
                draft,
                WatchlistStrategyLinks.mergeDeploymentId(draft.assignedStrategyDeploymentIds, deploymentId)
            )
        }
        emitUiState()
    }

    fun onRemoveStrategy(deploymentId: String) {
        val draft = tradePlansEditorDraft ?: return
        if (onDeleteLinkedDeployment != null) {
            onDeleteLinkedDeployment.invoke(deploymentId)
            val updatedWatchlist = repository.watchlists.value.find { it.id == selectedWatchlistId }
            val updatedEntry = updatedWatchlist?.entries?.find { it.id == draft.entryId }
            tradePlansEditorDraft = if (updatedEntry != null && updatedWatchlist != null) {
                WatchlistUiMapper.toEditorUi(updatedEntry, updatedWatchlist, strategyDeployments)
            } else {
                refreshEditorStrategies(
                    draft,
                    WatchlistStrategyLinks.removeDeploymentId(draft.assignedStrategyDeploymentIds, deploymentId)
                )
            }
        } else {
            tradePlansEditorDraft = refreshEditorStrategies(
                draft,
                WatchlistStrategyLinks.removeDeploymentId(draft.assignedStrategyDeploymentIds, deploymentId)
            )
        }
        emitUiState()
    }

    fun onStrategyFilterSelected(filter: WatchlistStrategyFilter) {
        activeStrategyFilter = filter
        emitUiState()
    }

    private fun refreshEditorDraft(draft: WatchlistTradePlansEditorUi): WatchlistTradePlansEditorUi =
        refreshEditorStrategies(refreshEditorLabels(draft), draft.assignedStrategyDeploymentIds)

    private fun refreshEditorStrategies(
        draft: WatchlistTradePlansEditorUi,
        assignedIds: List<String>
    ): WatchlistTradePlansEditorUi {
        val assigned = WatchlistStrategyLinks.resolve(assignedIds, strategyDeployments)
        val available = WatchlistStrategyLinks.available(strategyDeployments, assignedIds)
        return draft.copy(
            assignedStrategyDeploymentIds = assignedIds,
            assignedStrategies = WatchlistUiMapper.toStrategyUi(assigned),
            availableStrategies = WatchlistUiMapper.toStrategyUi(available)
        )
    }

    private fun refreshEditorLabels(draft: WatchlistTradePlansEditorUi): WatchlistTradePlansEditorUi {
        val watchlist = selectedWatchlist() ?: return draft
        val pendingDomain = WatchlistUiMapper.toDomainLabels(draft.pendingLabels)
        val registry = WatchlistLabels.combinedRegistry(watchlist.labels, pendingDomain)
        val assignedLabels = WatchlistLabels.resolveLabels(draft.assignedLabelIds, registry)
        val availableLabels = WatchlistLabels.availableLabels(registry, draft.assignedLabelIds)
        return draft.copy(
            assignedLabels = WatchlistUiMapper.toLabelUi(assignedLabels),
            availableLabels = WatchlistUiMapper.toLabelUi(availableLabels)
        )
    }

    fun onOpenBracketOrder(planId: String) {
        val plansDraft = tradePlansEditorDraft ?: return
        val entry = findEntry(plansDraft.entryId) ?: return
        val planEditor = plansDraft.plans.find { it.planId == planId } ?: return
        val base = entry.tradePlans.find { it.id == planId }
            ?: WatchlistTradePlan(id = planId, label = planEditor.label)
        val plan = planFromEditor(planEditor, base)
        val outcome = WatchlistTradePlanCalculator.compute(plan)
        bracketOrderDraft = recomputeBracketOrder(
            WatchlistBracketOrderUi(
                entryId = plansDraft.entryId,
                planId = planId,
                symbol = plansDraft.symbol,
                companyName = plansDraft.companyName,
                planLabel = planEditor.label,
                currencyCode = plansDraft.currencyCode,
                side = planEditor.side,
                entryPriceText = planEditor.entryPriceText,
                stopPriceText = planEditor.stopPriceText,
                targetPriceText = planEditor.targetPriceText,
                quantityText = outcome.quantity?.toString().orEmpty(),
                stopEntry = planEditor.stopEntry,
                adjustableTrailingStop = planEditor.adjustableTrailingStop
            ),
            entry
        )
        emitUiState()
    }

    fun onDismissBracketOrder() {
        bracketOrderDraft = null
        pendingBracketSymbol = null
        emitUiState()
    }

    fun onUpdateBracketOrderSide(side: TradeSide) {
        val draft = bracketOrderDraft ?: return
        val entry = findEntry(draft.entryId) ?: return
        bracketOrderDraft = recomputeBracketOrder(draft.copy(side = side), entry)
        emitUiState()
    }

    fun onUpdateBracketOrderField(field: WatchlistBracketOrderField, value: String) {
        val draft = bracketOrderDraft ?: return
        val entry = findEntry(draft.entryId) ?: return
        val updated = when (field) {
            WatchlistBracketOrderField.ENTRY -> draft.copy(entryPriceText = value)
            WatchlistBracketOrderField.STOP -> draft.copy(stopPriceText = value)
            WatchlistBracketOrderField.TARGET -> draft.copy(targetPriceText = value)
            WatchlistBracketOrderField.QUANTITY -> draft.copy(quantityText = value)
        }
        bracketOrderDraft = recomputeBracketOrder(updated, entry)
        emitUiState()
    }

    fun onSubmitBracketOrder() {
        val draft = bracketOrderDraft ?: return
        val gateway = executionGateway ?: return
        if (executionConnection != GatewayConnectionState.Connected || !draft.canSubmit) return
        val entry = findEntry(draft.entryId) ?: return
        val planResult = buildTouchTurnPlanFromBracketDraft(draft, entry)
        if (planResult.isFailure) {
            bracketOrderDraft = draft.copy(
                validationErrors = listOf(planResult.exceptionOrNull()?.message ?: "Invalid bracket"),
                canSubmit = false
            )
            emitUiState()
            return
        }
        pendingBracketSymbol = draft.symbol
        ensureLiveMarketData?.invoke(entry.symbol, entry.instrument)
        val submitGeneration = ++bracketSubmitGeneration
        bracketOrderDraft = draft.copy(
            submitInProgress = true,
            submitResultMessage = null,
            validationErrors = emptyList()
        )
        emitUiState()
        try {
            gateway.placeTouchTurnBracket(planResult.getOrThrow())
            scheduleBracketAckTimeout(submitGeneration)
        } catch (error: Throwable) {
            pendingBracketSymbol = null
            bracketOrderDraft = bracketOrderDraft?.copy(
                submitInProgress = false,
                submitResultMessage = error.message ?: "Failed to place bracket order",
            )
            emitUiState()
        }
    }

    private fun scheduleBracketAckTimeout(submitGeneration: Int) {
        scope.launchUiAction(AppScreen.WATCHLIST, "scheduleBracketAckTimeout") {
            delay(BRACKET_ACK_TIMEOUT_MS)
            if (bracketSubmitGeneration != submitGeneration) return@launchUiAction
            if (bracketOrderDraft?.submitInProgress != true) return@launchUiAction
            pendingBracketSymbol = null
            bracketOrderDraft = bracketOrderDraft?.copy(
                submitInProgress = false,
                submitResultMessage = "Bracket timed out waiting for broker acknowledgment."
            )
            emitUiState()
        }
    }

    private fun planFromEditor(planEditor: WatchlistPlanEditorUi, base: WatchlistTradePlan): WatchlistTradePlan =
        WatchlistUiMapper.planFromEditorFields(
            plan = base.copy(label = planEditor.label),
            side = planEditor.side,
            entryPriceText = planEditor.entryPriceText,
            stopPriceText = planEditor.stopPriceText,
            targetPriceText = planEditor.targetPriceText,
            investmentAmountText = planEditor.investmentAmountText,
            sizingMode = planEditor.sizingMode,
            proximityAlertEnabled = planEditor.proximityAlertEnabled,
            proximityThresholdMode = planEditor.proximityThresholdMode,
            proximityThresholdValueText = planEditor.proximityThresholdValueText,
            stopEntry = planEditor.stopEntry,
            adjustableTrailingStop = planEditor.adjustableTrailingStop
        )

    private fun bracketOptionsFromDraft(draft: WatchlistBracketOrderUi): WatchlistBracketOrderPlanner.BracketOrderOptions =
        WatchlistBracketOrderPlanner.BracketOrderOptions(
            stopEntry = draft.stopEntry,
            adjustableTrailingStop = draft.adjustableTrailingStop
        )

    private fun buildTouchTurnPlanFromBracketDraft(
        draft: WatchlistBracketOrderUi,
        entry: WatchlistEntry
    ): Result<daytrader.domain.TouchTurnOrderPlan> {
        val entryPrice = draft.entryPriceText.toDoubleOrNull()
            ?: return Result.failure(IllegalArgumentException("Entry price required"))
        val stopPrice = draft.stopPriceText.toDoubleOrNull()
            ?: return Result.failure(IllegalArgumentException("Stop price required"))
        val targetPrice = draft.targetPriceText.toDoubleOrNull()
            ?: return Result.failure(IllegalArgumentException("Target price required"))
        val quantity = draft.quantityText.toIntOrNull()
            ?: return Result.failure(IllegalArgumentException("Quantity required"))
        return WatchlistBracketOrderPlanner.buildTouchTurnPlan(
            symbol = SymbolMarkets.normalizeSymbol(entry.symbol),
            currencyCode = entry.currencyCode,
            instrument = entry.instrument,
            side = draft.side,
            entryPrice = entryPrice,
            stopPrice = stopPrice,
            targetPrice = targetPrice,
            quantity = quantity,
            options = bracketOptionsFromDraft(draft)
        )
    }

    private fun recomputeBracketOrder(draft: WatchlistBracketOrderUi, entry: WatchlistEntry): WatchlistBracketOrderUi {
        val entryPrice = draft.entryPriceText.toDoubleOrNull()
        val stopPrice = draft.stopPriceText.toDoubleOrNull()
        val targetPrice = draft.targetPriceText.toDoubleOrNull()
        val quantity = draft.quantityText.toIntOrNull()
        val options = bracketOptionsFromDraft(draft)
        val previewPlan = WatchlistTradePlan(
            id = draft.planId,
            label = draft.planLabel,
            side = draft.side,
            entryPrice = entryPrice,
            stopPrice = stopPrice,
            targetPrice = targetPrice,
            investmentAmount = entryPrice?.let { price -> quantity?.let { price * it } },
            stopEntry = draft.stopEntry,
            adjustableTrailingStop = draft.adjustableTrailingStop
        )
        val calculatorOutcome = WatchlistTradePlanCalculator.compute(previewPlan)
        val bracketResult = if (entryPrice != null && stopPrice != null && targetPrice != null && quantity != null) {
            WatchlistBracketOrderPlanner.buildTouchTurnPlan(
                symbol = entry.symbol,
                currencyCode = entry.currencyCode,
                instrument = entry.instrument,
                side = draft.side,
                entryPrice = entryPrice,
                stopPrice = stopPrice,
                targetPrice = targetPrice,
                quantity = quantity,
                options = options
            )
        } else {
            Result.failure(IllegalArgumentException("Complete all bracket fields"))
        }
        val errors = buildList {
            addAll(calculatorOutcome.errors)
            bracketResult.exceptionOrNull()?.message?.let { add(it) }
        }.distinct()
        val connected = executionConnection == GatewayConnectionState.Connected && executionGateway != null
        return draft.copy(
            bracketOrderSummary = WatchlistBracketOrderPlanner.bracketOrderSummary(options),
            outcome = if (calculatorOutcome.isComplete) {
                WatchlistUiMapper.outcomeUi(calculatorOutcome, draft.currencyCode)
            } else {
                null
            },
            validationErrors = errors,
            canSubmit = connected && errors.isEmpty() && !draft.submitInProgress
        )
    }

    fun onSaveTradePlans() {
        val draft = tradePlansEditorDraft ?: return
        val watchlist = selectedWatchlist() ?: return
        val entry = findEntry(draft.entryId) ?: return
        val updatedPlans = draft.plans.map { editor ->
            val base = entry.tradePlans.find { it.id == editor.planId }
                ?: WatchlistTradePlan(id = editor.planId, label = editor.label)
            WatchlistUiMapper.planFromEditorFields(
                plan = base.copy(label = editor.label),
                side = editor.side,
                entryPriceText = editor.entryPriceText,
                stopPriceText = editor.stopPriceText,
                targetPriceText = editor.targetPriceText,
                investmentAmountText = editor.investmentAmountText,
                sizingMode = editor.sizingMode,
                proximityAlertEnabled = editor.proximityAlertEnabled,
                proximityThresholdMode = editor.proximityThresholdMode,
                proximityThresholdValueText = editor.proximityThresholdValueText,
                stopEntry = editor.stopEntry,
                adjustableTrailingStop = editor.adjustableTrailingStop
            )
        }
        repository.updateWatchlist(watchlist.id) { current ->
            val pendingDomain = WatchlistUiMapper.toDomainLabels(draft.pendingLabels)
            val mergedLabels = WatchlistLabels.mergePendingLabels(current.labels, pendingDomain)
            val labelIds = WatchlistLabels.remapAssignedIds(
                assignedIds = draft.assignedLabelIds,
                watchlistLabels = current.labels,
                pendingLabels = pendingDomain
            )
            val strategyIds = WatchlistStrategyLinks.remapAssignedIds(
                assignedIds = draft.assignedStrategyDeploymentIds,
                deployments = strategyDeployments
            )
            current.copy(
                labels = mergedLabels,
                entries = current.entries.map { entry ->
                    if (entry.id == draft.entryId) {
                        entry.copy(
                            tradePlans = updatedPlans,
                            labelIds = labelIds,
                            strategyDeploymentIds = strategyIds
                        )
                    } else {
                        entry
                    }
                }
            )
        }
        tradePlansEditorDraft = null
        emitUiState()
    }

    private fun updatePlanEditor(planId: String, transform: (WatchlistPlanEditorUi) -> WatchlistPlanEditorUi) {
        val draft = tradePlansEditorDraft ?: return
        val entry = findEntry(draft.entryId) ?: return
        tradePlansEditorDraft = draft.copy(
            plans = draft.plans.map { editor ->
                if (editor.planId != planId) {
                    editor
                } else {
                    val base = entry.tradePlans.find { it.id == planId }
                        ?: WatchlistTradePlan(id = planId, label = editor.label)
                    WatchlistUiMapper.recomputeEditorPlan(
                        editor = transform(editor),
                        base = base,
                        currencyCode = draft.currencyCode,
                        scannedPrice = draft.scannedPrice
                    )
                }
            }
        )
        emitUiState()
    }

    private fun startEntryCharts(entry: WatchlistEntry) {
        entryChartsEntryId = entry.id
        entryDailyBars = emptyList()
        entryDailyBarsLoading = true
        entryDailyBarsError = null
        entryLivePriceHistory.clear()
        ensureLiveMarketData?.invoke(entry.symbol, entry.instrument)
        scope.launchUiAction(AppScreen.WATCHLIST, "loadEntryDailyBars") {
            loadEntryDailyBars(entry)
        }
    }

    private fun clearEntryCharts() {
        entryChartsEntryId = null
        entryDailyBars = emptyList()
        entryDailyBarsLoading = false
        entryDailyBarsError = null
        entryLivePriceHistory.clear()
    }

    private suspend fun loadEntryDailyBars(entry: WatchlistEntry) {
        val gateway = marketDataGateway ?: executionGateway
        if (gateway == null) {
            if (entryChartsEntryId != entry.id) return
            entryDailyBarsLoading = false
            entryDailyBarsError = "Broker not connected"
            emitUiState()
            return
        }
        if (gateway.connectionState.value != GatewayConnectionState.Connected) {
            if (entryChartsEntryId != entry.id) return
            entryDailyBarsLoading = false
            entryDailyBarsError = "Connect to market data to load daily history"
            emitUiState()
            return
        }
        val result = withContext(Dispatchers.Default) {
            gateway.fetchDailyBars(entry.symbol, entry.instrument)
        }
        if (entryChartsEntryId != entry.id) return
        entryDailyBarsLoading = false
        result.fold(
            onSuccess = {
                entryDailyBars = it
                entryDailyBarsError = null
            },
            onFailure = {
                entryDailyBars = emptyList()
                entryDailyBarsError = it.message ?: "Failed to load daily history"
            }
        )
        emitUiState()
    }

    private fun recordEntryLivePrices() {
        val editor = tradePlansEditorDraft ?: return
        if (entryChartsEntryId != editor.entryId) return
        if (!shouldRecordEntryLivePrices(editor)) return
        val price = LiveMarkPriceResolver.resolve(editor.symbol, brokerPositions, brokerQuotes) ?: return
        entryLivePriceHistory.record(System.currentTimeMillis(), price)
    }

    private fun shouldRecordEntryLivePrices(editor: WatchlistTradePlansEditorUi): Boolean {
        val entry = findEntry(editor.entryId)
        val hasPlacedBracket = entry?.tradePlans?.any { it.hasPlacedOrder } == true
        val hasBracketDraft = bracketOrderDraft?.entryId == editor.entryId
        val hasQuotes = LiveMarkPriceResolver.quoteForSymbol(editor.symbol, brokerQuotes) != null
        val hasPosition = brokerPositions.any { SymbolMarkets.symbolsMatch(editor.symbol, it.symbol) }
        return hasPlacedBracket || hasBracketDraft || hasQuotes || hasPosition
    }

    private fun syncExecutedBracketLegsFromFills() {
        val editor = tradePlansEditorDraft ?: return
        val entry = findEntry(editor.entryId) ?: return
        val plan = WatchlistChartLevels.activePlacedPlan(
            entry = entry,
            bracketDraft = bracketOrderDraft?.takeIf { it.entryId == editor.entryId }
        ) ?: return
        val detected = WatchlistChartExecution.executedLevels(
            symbol = editor.symbol,
            entry = entry,
            fills = brokerFills,
            bracketDraft = bracketOrderDraft?.takeIf { it.entryId == editor.entryId },
            planEditors = editor.plans
        )
        val merged = WatchlistChartExecution.mergeDetectedExecutedLegs(plan, detected)
        if (merged == plan.executedBracketLegs) return
        val watchlist = selectedWatchlist() ?: return
        repository.updateWatchlist(watchlist.id) { current ->
            current.copy(
                entries = current.entries.map { watchlistEntry ->
                    if (watchlistEntry.id != entry.id) watchlistEntry else {
                        watchlistEntry.copy(
                            tradePlans = watchlistEntry.tradePlans.map { tradePlan ->
                                if (tradePlan.id != plan.id) tradePlan else tradePlan.copy(executedBracketLegs = merged)
                            }
                        )
                    }
                }
            )
        }
    }

    private fun buildEntryChartsUi(editor: WatchlistTradePlansEditorUi): WatchlistEntryChartsUi {
        val entry = findEntry(editor.entryId)
        val quote = LiveMarkPriceResolver.quoteForSymbol(editor.symbol, brokerQuotes)
        val currentPrice = LiveMarkPriceResolver.resolve(editor.symbol, brokerPositions, brokerQuotes)
        val hasLiveQuote = quote?.bid?.let { it > 0.0 } == true ||
            quote?.ask?.let { it > 0.0 } == true ||
            quote?.last?.let { it > 0.0 } == true
        val history = entryLivePriceHistory.snapshot()
        val bracketDraft = bracketOrderDraft?.takeIf { it.entryId == editor.entryId }
        val orderLevels = WatchlistChartLevels.forEntry(
            symbol = editor.symbol,
            entry = entry,
            openOrders = brokerOpenOrders,
            bracketDraft = bracketDraft,
            planEditors = editor.plans
        )
        val executedLevels = WatchlistChartExecution.executedLevels(
            symbol = editor.symbol,
            entry = entry,
            fills = brokerFills,
            bracketDraft = bracketDraft,
            planEditors = editor.plans
        )
        val hasOrderLevels = orderLevels.isNotEmpty()
        val liveAvailable = hasLiveQuote || history.isNotEmpty() || currentPrice != null || hasOrderLevels
        val liveStatusLabel = when {
            liveAvailable -> null
            marketDataConnection != GatewayConnectionState.Connected ->
                "Connect to market data for live prices"
            else -> "Waiting for live quotes…"
        }
        return WatchlistEntryChartsUi(
            symbol = editor.symbol,
            currencyCode = editor.currencyCode,
            dailyBars = entryDailyBars,
            dailyLoading = entryDailyBarsLoading,
            dailyError = entryDailyBarsError,
            livePriceHistory = history,
            liveCurrentPrice = currentPrice,
            liveAvailable = liveAvailable,
            liveStatusLabel = liveStatusLabel,
            liveQuoteStrip = TouchTurnQuoteStripUiMapper.from(
                quote = quote,
                currencyCode = editor.currencyCode,
                bracketSetup = null,
                levels = orderLevels
            ),
            orderLevels = orderLevels,
            executedLevels = executedLevels,
            listingExch = null
        )
    }

    private fun findEntry(entryId: String): WatchlistEntry? =
        selectedWatchlist()?.entries?.find { it.id == entryId }

    private fun selectedWatchlist(): Watchlist? =
        watchlists.find { it.id == selectedWatchlistId } ?: watchlists.firstOrNull()

    private fun nearHitSummaries(): Map<String, String> {
        val result = lastScanResult ?: return emptyMap()
        return result.entryResults
            .mapNotNull { entryResult ->
                val entry = findEntry(entryResult.entryId) ?: return@mapNotNull null
                val placedPlanIds = entry.tradePlans.filter { it.hasPlacedOrder }.map { it.id }.toSet()
                val nearPlans = entryResult.proximityHits.filter { it.isNear && it.planId !in placedPlanIds }
                if (nearPlans.isEmpty()) return@mapNotNull null
                val summary = nearPlans.joinToString { hit ->
                    "${hit.planLabel} (${Formatters.price(hit.scannedPrice)} vs ${Formatters.price(hit.entryPrice)})"
                }
                entryResult.entryId to summary
            }
            .toMap()
    }

    private fun applyFullUi() {
        refreshTradePlansEditorProximity()
        refreshBracketOrderState()
        val watchlist = selectedWatchlist()
        val entries = watchlist?.entries.orEmpty()
        val labels = watchlist?.labels.orEmpty()
        if (activeGroupFilter is WatchlistGroupFilter.Group &&
            labels.none { it.id == (activeGroupFilter as WatchlistGroupFilter.Group).labelId }
        ) {
            activeGroupFilter = WatchlistGroupFilter.All
        }
        if (activeStrategyFilter is WatchlistStrategyFilter.Strategy) {
            val strategyType = (activeStrategyFilter as WatchlistStrategyFilter.Strategy).strategyType
            if (WatchlistStrategyLinks.countForStrategyType(entries, strategyType, strategyDeployments) == 0) {
                activeStrategyFilter = WatchlistStrategyFilter.All
            }
        }
        val visibleEntries = entriesMatchingFilters(entries)
        val nearSummaries = nearHitSummaries()
        val sorted = WatchlistEntrySorter.sortedEntries(
            entries = visibleEntries,
            column = sortColumn,
            direction = sortDirection,
            watchlist = watchlist,
            deployments = strategyDeployments,
            nearSummaries = nearSummaries
        )
        val groupFilterChips = buildGroupFilterChips(entries, labels)
        val strategyFilterChips = buildStrategyFilterChips(entries, strategyDeployments)
        val placedPlanIdsByEntry = entries.associate { entry ->
            entry.id to entry.tradePlans.filter { it.hasPlacedOrder }.map { it.id }.toSet()
        }
        val nearHits = lastScanResult?.entryResults.orEmpty()
            .flatMap { entryResult ->
                val placedPlanIds = placedPlanIdsByEntry[entryResult.entryId].orEmpty()
                entryResult.proximityHits
                    .filter { it.isNear && it.planId !in placedPlanIds }
                    .map { hit ->
                        WatchlistNearHitUi(
                            entryId = entryResult.entryId,
                            symbol = entryResult.symbol,
                            planLabel = hit.planLabel,
                            summary = "${hit.planLabel}: ${Formatters.price(hit.scannedPrice)} near entry ${Formatters.price(hit.entryPrice)}"
                        )
                    }
            }
        val scanSummary = null
        val reversalScoreSummary = null
        val resolvedReversalBatch = WatchlistStatusUiMapper.resolvedReversalBatch(
            inMemory = lastReversalScoreResult,
            watchlist = watchlist
        )
        val statusStrip = WatchlistStatusUiMapper.buildStatusStrip(
            execution = executionConnection,
            marketData = marketDataConnection,
            brokerKind = brokerKind,
            lastReversalResult = resolvedReversalBatch
        )
        val macroRegimeCards = resolvedReversalBatch?.let(WatchlistStatusUiMapper::buildMacroRegimeCards).orEmpty()
        val activitySummary = WatchlistStatusUiMapper.buildActivitySummary(
            scanResult = lastScanResult,
            reversalResult = resolvedReversalBatch
        )
        _uiState.update {
            WatchlistUiState(
                watchlistName = watchlist?.name ?: "Watchlist",
                totalEntryCount = entries.size,
                rows = watchlist?.let { list ->
                    sorted.map { entry ->
                        WatchlistUiMapper.toRowUi(
                            entry = entry,
                            watchlist = list,
                            deployments = strategyDeployments,
                            nearEntrySummary = nearSummaries[entry.id],
                            reversalScoreLoading = reversalScoreInProgress && entry.id == reversalScoreLoadingEntryId
                        )
                    }
                }.orEmpty(),
                groupFilterChips = groupFilterChips,
                activeGroupFilter = activeGroupFilter,
                strategyFilterChips = strategyFilterChips,
                activeStrategyFilter = activeStrategyFilter,
                sortColumn = sortColumn,
                sortDirection = sortDirection,
                showAddDialog = showAddDialog,
                tradePlansEditor = tradePlansEditorDraft,
                entryCharts = tradePlansEditorDraft?.let(::buildEntryChartsUi),
                planDiaryEditor = planDiaryEditorDraft,
                pendingDiaryNotification = dueDiaryNotificationQueue.firstOrNull()
                    ?.takeIf { it.diaryEntry.id != diaryNotificationSnoozedForView }
                    ?.let(WatchlistUiMapper::toDiaryNotificationUi),
                bracketOrderEditor = bracketOrderDraft,
                connectionLabel = connectionLabel(executionConnection, marketDataConnection),
                statusStrip = statusStrip,
                macroRegimeCards = macroRegimeCards,
                activitySummary = activitySummary,
                scanInProgress = scanInProgress,
                scanProgress = scanProgress,
                scanSummary = scanSummary,
                reversalScoreInProgress = reversalScoreInProgress,
                reversalScoreProgress = reversalScoreProgress,
                reversalScoreProgressLabel = null,
                reversalScoreSummary = reversalScoreSummary,
                reversalScoreLoadingEntryId = reversalScoreLoadingEntryId,
                reversalScoreInsight = reversalScoreInsight,
                nearHits = nearHits,
                storageScopeLabel = brokerKind.displayName
            )
        }
    }

    private fun refreshBracketOrderState() {
        val draft = bracketOrderDraft ?: return
        val entry = findEntry(draft.entryId) ?: return
        bracketOrderDraft = recomputeBracketOrder(draft, entry).copy(
            submitInProgress = draft.submitInProgress,
            submitResultMessage = draft.submitResultMessage
        )
    }

    private fun refreshTradePlansEditorProximity() {
        val draft = tradePlansEditorDraft ?: return
        val entry = findEntry(draft.entryId) ?: return
        val scannedPrice = entry.lastScannedPrice
        if (draft.scannedPrice == scannedPrice) return
        tradePlansEditorDraft = draft.copy(
            scannedPrice = scannedPrice,
            formattedLast = Formatters.price(scannedPrice?.takeIf { it > 0.0 }),
            plans = draft.plans.map { planEditor ->
                val base = entry.tradePlans.find { it.id == planEditor.planId }
                    ?: WatchlistTradePlan(id = planEditor.planId, label = planEditor.label)
                WatchlistUiMapper.recomputeEditorPlan(
                    editor = planEditor,
                    base = base,
                    currencyCode = draft.currencyCode,
                    scannedPrice = scannedPrice
                )
            }
        )
    }

    private fun entriesMatchingFilters(entries: List<WatchlistEntry>): List<WatchlistEntry> =
        entries.filter { matchesGroupFilter(it) && matchesStrategyFilter(it) }

    private fun matchesGroupFilter(entry: WatchlistEntry): Boolean = when (val filter = activeGroupFilter) {
        WatchlistGroupFilter.All -> true
        WatchlistGroupFilter.Ungrouped -> entry.labelIds.isEmpty()
        is WatchlistGroupFilter.Group -> WatchlistLabels.entryHasLabel(entry, filter.labelId)
    }

    private fun matchesStrategyFilter(entry: WatchlistEntry): Boolean = when (val filter = activeStrategyFilter) {
        WatchlistStrategyFilter.All -> true
        WatchlistStrategyFilter.Unassigned -> entry.strategyDeploymentIds.isEmpty()
        is WatchlistStrategyFilter.Strategy ->
            WatchlistStrategyLinks.entryHasStrategyType(entry, filter.strategyType, strategyDeployments)
    }

    private fun buildStrategyFilterChips(
        entries: List<WatchlistEntry>,
        deployments: List<daytrader.domain.StrategyDeployment>
    ): List<WatchlistStrategyFilterChipUi> {
        val chips = mutableListOf(
            WatchlistStrategyFilterChipUi(
                filter = WatchlistStrategyFilter.All,
                label = "All strategies (${entries.size})",
                count = entries.size,
                selected = activeStrategyFilter is WatchlistStrategyFilter.All
            )
        )
        WatchlistStrategyLinks.linkedStrategyTypes(entries, deployments)
            .sortedBy { StrategyCatalog.displayName(it).lowercase() }
            .forEach { strategyType ->
                val count = WatchlistStrategyLinks.countForStrategyType(entries, strategyType, deployments)
                chips += WatchlistStrategyFilterChipUi(
                    filter = WatchlistStrategyFilter.Strategy(strategyType),
                    label = "${StrategyCatalog.displayName(strategyType)} ($count)",
                    count = count,
                    selected = activeStrategyFilter == WatchlistStrategyFilter.Strategy(strategyType)
                )
            }
        chips += WatchlistStrategyFilterChipUi(
            filter = WatchlistStrategyFilter.Unassigned,
            label = "No strategy (${WatchlistStrategyLinks.countUnassigned(entries)})",
            count = WatchlistStrategyLinks.countUnassigned(entries),
            selected = activeStrategyFilter is WatchlistStrategyFilter.Unassigned
        )
        return chips
    }

    private fun buildGroupFilterChips(
        entries: List<WatchlistEntry>,
        labels: List<daytrader.domain.WatchlistLabel>
    ): List<WatchlistGroupFilterChipUi> {
        val chips = mutableListOf(
            WatchlistGroupFilterChipUi(
                filter = WatchlistGroupFilter.All,
                label = "All (${entries.size})",
                count = entries.size,
                selected = activeGroupFilter is WatchlistGroupFilter.All
            )
        )
        labels.forEach { label ->
            val count = WatchlistLabels.countForLabel(entries, label.id)
            chips += WatchlistGroupFilterChipUi(
                filter = WatchlistGroupFilter.Group(label.id),
                label = "${label.name} ($count)",
                count = count,
                selected = activeGroupFilter == WatchlistGroupFilter.Group(label.id)
            )
        }
        val ungroupedCount = WatchlistLabels.countUngrouped(entries)
        if (ungroupedCount > 0) {
            chips += WatchlistGroupFilterChipUi(
                filter = WatchlistGroupFilter.Ungrouped,
                label = "Ungrouped ($ungroupedCount)",
                count = ungroupedCount,
                selected = activeGroupFilter is WatchlistGroupFilter.Ungrouped
            )
        }
        return chips
    }

    private fun connectionLabel(
        execution: GatewayConnectionState,
        marketData: GatewayConnectionState
    ): String = when (brokerKind) {
        BrokerKind.EMULATOR_LIVE_IB_MARKET_DATA, BrokerKind.REPLAY -> {
            val exec = gatewayConnectionPhrase(execution, "Paper execution")
            val md = gatewayConnectionPhrase(marketData, "IB market data")
            "$exec · $md"
        }
        else -> gatewayConnectionPhrase(execution, brokerKind.displayName)
    }

    private fun gatewayConnectionPhrase(state: GatewayConnectionState, label: String): String = when (state) {
        GatewayConnectionState.Connected -> "$label connected"
        GatewayConnectionState.Connecting -> "$label connecting…"
        is GatewayConnectionState.Error -> "$label connection error"
        GatewayConnectionState.Disconnected -> "$label disconnected"
    }

    companion object {
        private const val BRACKET_ACK_TIMEOUT_MS = 30_000L
        private const val QUOTE_UI_REFRESH_INTERVAL_MS = 100L
    }
}
