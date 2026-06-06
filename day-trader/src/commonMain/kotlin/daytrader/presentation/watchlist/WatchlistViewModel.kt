package daytrader.presentation.watchlist

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
import daytrader.domain.WatchlistLabels
import daytrader.domain.WatchlistTradePlan
import daytrader.domain.newWatchlistLabel
import daytrader.domain.newWatchlistEntry
import daytrader.gateway.BrokerGateway
import daytrader.gateway.BrokerKind
import daytrader.gateway.GatewayConnectionState
import daytrader.presentation.Formatters
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

class WatchlistViewModel(
    private val repository: WatchlistRepository,
    private val brokerGateway: BrokerGateway? = null,
    private val brokerKind: BrokerKind = BrokerKind.EMULATOR,
    private val scanService: WatchlistPriceScanService = WatchlistPriceScanService()
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private var watchlists: List<Watchlist> = emptyList()
    private var selectedWatchlistId: String = DEFAULT_WATCHLIST_ID
    private var connectionState: GatewayConnectionState = GatewayConnectionState.Disconnected
    private var showAddDialog = false
    private var tradePlansEditorDraft: WatchlistTradePlansEditorUi? = null
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

        brokerGateway?.connectionState
            ?.onEach { state ->
                connectionState = state
                emitUiState()
            }
            ?.launchIn(scope)
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
        val gateway = brokerGateway ?: return
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
            val resolveGateway = brokerGateway
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
                        currencyCode = draft.currencyCode
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
                val nearPlans = entryResult.proximityHits.filter { it.isNear }
                if (nearPlans.isEmpty()) return@mapNotNull null
                val summary = nearPlans.joinToString { hit ->
                    "${hit.planLabel} (${Formatters.price(hit.scannedPrice)} vs ${Formatters.price(hit.entryPrice)})"
                }
                entryResult.entryId to summary
            }
            .toMap()
    }

    private fun emitUiState() {
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
        val nearHits = lastScanResult?.entryResults.orEmpty()
            .flatMap { entryResult ->
                entryResult.proximityHits
                    .filter { it.isNear }
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
                connectionLabel = connectionLabel(connectionState),
                scanInProgress = scanInProgress,
                scanProgressLabel = scanProgressLabel,
                scanSummary = scanSummary,
                nearHits = nearHits
            )
        }
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

    private fun connectionLabel(state: GatewayConnectionState): String = when (state) {
        GatewayConnectionState.Connected -> "${brokerKind.displayName} connected"
        GatewayConnectionState.Connecting -> "Connecting…"
        is GatewayConnectionState.Error -> "Connection error"
        GatewayConnectionState.Disconnected -> "Disconnected"
    }
}
