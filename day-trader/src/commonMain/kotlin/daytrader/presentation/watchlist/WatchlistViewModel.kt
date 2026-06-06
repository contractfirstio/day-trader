package daytrader.presentation.watchlist

import daytrader.broker.SymbolMarkets
import daytrader.data.WatchlistPriceScanService
import daytrader.data.WatchlistRepository
import daytrader.data.WatchlistScanResult
import daytrader.domain.DEFAULT_WATCHLIST_ID
import daytrader.domain.DeploymentMarket
import daytrader.domain.InstrumentIdentity
import daytrader.domain.InstrumentListingCandidates
import daytrader.domain.InstrumentResolution
import daytrader.domain.InstrumentResolveLog
import daytrader.domain.PlanSizingMode
import daytrader.domain.ProximityThresholdMode
import daytrader.domain.TradeSide
import daytrader.domain.Watchlist
import daytrader.domain.WatchlistEntry
import daytrader.domain.WatchlistBracketOrderPlanner
import daytrader.domain.WatchlistLabels
import daytrader.domain.WatchlistTradePlan
import daytrader.domain.WatchlistTradePlanCalculator
import daytrader.domain.newWatchlistLabel
import daytrader.domain.newWatchlistEntry
import daytrader.gateway.BrokerGateway
import daytrader.gateway.BrokerKind
import daytrader.gateway.GatewayConnectionState
import daytrader.gateway.TouchTurnBracketAck
import daytrader.presentation.Formatters
import daytrader.presentation.positions.SortDirection
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class WatchlistViewModel(
    private val repository: WatchlistRepository,
    brokerGateway: BrokerGateway? = null,
    touchTurnSessionGateway: BrokerGateway? = null,
    private val brokerKind: BrokerKind = BrokerKind.EMULATOR,
    private val ensureLiveMarketData: ((String, InstrumentIdentity?) -> Unit)? = null,
    private val scanService: WatchlistPriceScanService = WatchlistPriceScanService()
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val executionGateway = brokerGateway
    private val marketDataGateway = touchTurnSessionGateway ?: brokerGateway

    private var watchlists: List<Watchlist> = emptyList()
    private var selectedWatchlistId: String = DEFAULT_WATCHLIST_ID
    private var executionConnection: GatewayConnectionState = GatewayConnectionState.Disconnected
    private var marketDataConnection: GatewayConnectionState = GatewayConnectionState.Disconnected
    private var showAddDialog = false
    private var tradePlansEditorDraft: WatchlistTradePlansEditorUi? = null
    private var bracketOrderDraft: WatchlistBracketOrderUi? = null
    private var pendingBracketSymbol: String? = null
    private var bracketSubmitGeneration = 0
    private var sortColumn = WatchlistSortColumn.SYMBOL
    private var sortDirection = WatchlistSortDirection.ASCENDING
    private var activeGroupFilter: WatchlistGroupFilter = WatchlistGroupFilter.All
    private var scanInProgress = false
    private var scanProgressLabel: String? = null
    private var lastScanResult: WatchlistScanResult? = null

    private val _uiState = MutableStateFlow(WatchlistUiState())
    val uiState: StateFlow<WatchlistUiState> = _uiState.asStateFlow()

    init {
        repository.watchlists
            .onEach { lists ->
                watchlists = lists
                if (lists.none { it.id == selectedWatchlistId }) {
                    selectedWatchlistId = lists.firstOrNull()?.id ?: DEFAULT_WATCHLIST_ID
                }
                emitUiState()
            }
            .launchIn(scope)

        executionGateway?.connectionState
            ?.onEach { state ->
                executionConnection = state
                if (marketDataGateway === executionGateway) {
                    marketDataConnection = state
                }
                emitUiState()
            }
            ?.launchIn(scope)

        executionGateway?.touchTurnBracketPlacements
            ?.onEach(::handleBracketPlacementAck)
            ?.launchIn(scope)

        if (marketDataGateway != null && marketDataGateway !== executionGateway) {
            marketDataGateway.connectionState
                .onEach { state ->
                    marketDataConnection = state
                    emitUiState()
                }
                .launchIn(scope)
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
                orderIds = ack.orderIds
            )
        }
        bracketOrderDraft = draft.copy(
            submitInProgress = false,
            submitResultMessage = if (ack.result.isSuccess) {
                val ids = ack.orderIds.joinToString(", ")
                if (ids.isBlank()) "Bracket submitted." else "Bracket submitted (order ids: $ids)."
            } else {
                "Bracket failed: ${ack.result.exceptionOrNull()?.message ?: "unknown error"}"
            }
        )
        emitUiState()
    }

    private fun recordPlanOrderPlacement(entryId: String, planId: String, orderIds: List<Int>) {
        val watchlist = selectedWatchlist() ?: return
        val placedAt = System.currentTimeMillis()
        repository.updateWatchlist(watchlist.id) { current ->
            current.copy(
                entries = current.entries.map { entry ->
                    if (entry.id != entryId) entry
                    else entry.copy(
                        tradePlans = entry.tradePlans.map { plan ->
                            if (plan.id != planId) plan
                            else plan.copy(
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
            tradePlansEditorDraft = WatchlistUiMapper.toEditorUi(entry, updatedWatchlist)
        }
    }

    fun onReactivatePlan(planId: String) {
        val draft = tradePlansEditorDraft ?: return
        clearPlanOrderPlacement(draft.entryId, planId)
        emitUiState()
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
            tradePlansEditorDraft = WatchlistUiMapper.toEditorUi(entry, updatedWatchlist)
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
        if (scanInProgress || watchlist.entries.isEmpty()) return
        scope.launch {
            scanInProgress = true
            scanProgressLabel = "Starting scan…"
            emitUiState()
            val result = scanService.scan(
                entries = watchlist.entries,
                gateway = gateway
            ) { progress ->
                scanProgressLabel = "Checking ${progress.completed}/${progress.total} — ${progress.symbol}"
                emitUiState()
            }
            val activeWatchlist = selectedWatchlist() ?: return@launch
            result.entryResults.forEach { entryResult ->
                repository.updateEntry(activeWatchlist.id, entryResult.entryId) { entry ->
                    entry.copy(
                        lastScannedPrice = entryResult.price,
                        lastScannedAtEpochMs = result.scannedAtEpochMs
                    )
                }
            }
            lastScanResult = result
            scanInProgress = false
            scanProgressLabel = null
            emitUiState()
        }
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
        }
        repository.removeEntry(watchlist.id, entryId)
        emitUiState()
    }

    fun onOpenTradePlans(entryId: String) {
        val entry = findEntry(entryId) ?: return
        val watchlist = selectedWatchlist() ?: return
        tradePlansEditorDraft = refreshEditorLabels(
            WatchlistUiMapper.toEditorUi(entry = entry, watchlist = watchlist)
        )
        emitUiState()
    }

    fun onDismissTradePlans() {
        tradePlansEditorDraft = null
        bracketOrderDraft = null
        pendingBracketSymbol = null
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
                quantityText = outcome.quantity?.toString().orEmpty()
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
        bracketOrderDraft = draft.copy(submitInProgress = true, submitResultMessage = null)
        emitUiState()
        gateway.placeTouchTurnBracket(planResult.getOrThrow())
        scheduleBracketAckTimeout(submitGeneration)
    }

    private fun scheduleBracketAckTimeout(submitGeneration: Int) {
        scope.launch {
            delay(BRACKET_ACK_TIMEOUT_MS)
            if (bracketSubmitGeneration != submitGeneration) return@launch
            if (bracketOrderDraft?.submitInProgress != true) return@launch
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
            proximityThresholdValueText = planEditor.proximityThresholdValueText
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
            quantity = quantity
        )
    }

    private fun recomputeBracketOrder(draft: WatchlistBracketOrderUi, entry: WatchlistEntry): WatchlistBracketOrderUi {
        val entryPrice = draft.entryPriceText.toDoubleOrNull()
        val stopPrice = draft.stopPriceText.toDoubleOrNull()
        val targetPrice = draft.targetPriceText.toDoubleOrNull()
        val quantity = draft.quantityText.toIntOrNull()
        val previewPlan = WatchlistTradePlan(
            id = draft.planId,
            label = draft.planLabel,
            side = draft.side,
            entryPrice = entryPrice,
            stopPrice = stopPrice,
            targetPrice = targetPrice,
            investmentAmount = entryPrice?.let { price -> quantity?.let { price * it } }
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
                quantity = quantity
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
                proximityThresholdValueText = editor.proximityThresholdValueText
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
            current.copy(
                labels = mergedLabels,
                entries = current.entries.map { entry ->
                    if (entry.id == draft.entryId) {
                        entry.copy(tradePlans = updatedPlans, labelIds = labelIds)
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

    private fun emitUiState() {
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
        val filteredEntries = entries.filter(::matchesGroupFilter)
        val nearSummaries = nearHitSummaries()
        val comparator = when (sortColumn) {
            WatchlistSortColumn.COMPANY -> compareBy<WatchlistEntry> {
                it.companyName?.takeIf { name -> name.isNotBlank() } ?: it.symbol
            }
            WatchlistSortColumn.SYMBOL -> compareBy { it.symbol }
            WatchlistSortColumn.LAST -> compareBy { it.lastScannedPrice ?: Double.NEGATIVE_INFINITY }
            WatchlistSortColumn.NOTES -> compareBy { it.notes.orEmpty() }
        }
        val sorted = if (sortDirection == WatchlistSortDirection.DESCENDING) {
            filteredEntries.sortedWith(comparator.reversed())
        } else {
            filteredEntries.sortedWith(comparator)
        }
        val groupFilterChips = buildGroupFilterChips(entries, labels)
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
        val scanSummary = lastScanResult?.let { result ->
            when {
                result.entryResults.isEmpty() -> null
                result.nearHits.isEmpty() ->
                    "Scanned ${result.entryResults.size} symbols — none near entry${if (result.failedCount > 0) " (${result.failedCount} failed)" else ""}."
                else ->
                    "${result.nearHits.size} symbol(s) near entry${if (result.failedCount > 0) " (${result.failedCount} failed)" else ""}."
            }
        }
        _uiState.update {
            WatchlistUiState(
                watchlistName = watchlist?.name ?: "Watchlist",
                totalEntryCount = entries.size,
                rows = watchlist?.let { list ->
                    sorted.map { entry ->
                        WatchlistUiMapper.toRowUi(
                            entry = entry,
                            watchlist = list,
                            nearEntrySummary = nearSummaries[entry.id]
                        )
                    }
                }.orEmpty(),
                groupFilterChips = groupFilterChips,
                activeGroupFilter = activeGroupFilter,
                sortColumn = sortColumn,
                sortDirection = sortDirection,
                showAddDialog = showAddDialog,
                tradePlansEditor = tradePlansEditorDraft,
                bracketOrderEditor = bracketOrderDraft,
                connectionLabel = connectionLabel(executionConnection, marketDataConnection),
                scanInProgress = scanInProgress,
                scanProgressLabel = scanProgressLabel,
                scanSummary = scanSummary,
                nearHits = nearHits
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

    private fun matchesGroupFilter(entry: WatchlistEntry): Boolean = when (val filter = activeGroupFilter) {
        WatchlistGroupFilter.All -> true
        WatchlistGroupFilter.Ungrouped -> entry.labelIds.isEmpty()
        is WatchlistGroupFilter.Group -> WatchlistLabels.entryHasLabel(entry, filter.labelId)
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
    }
}
