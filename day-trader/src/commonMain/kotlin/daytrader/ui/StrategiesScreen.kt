package daytrader.ui

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import daytrader.presentation.strategies.isSelectable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import daytrader.domain.OhlcBar
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import daytrader.broker.SymbolMarkets
import daytrader.domain.DeploymentMarket
import daytrader.domain.MarketSource
import daytrader.domain.InstrumentListingCandidates
import daytrader.domain.InstrumentResolveLog
import daytrader.domain.InstrumentIdentity
import daytrader.domain.InstrumentResolution
import daytrader.domain.ResolvedInstrument
import daytrader.domain.RthMarketSessions
import daytrader.data.StrategyCatalog
import daytrader.domain.ExecutionState
import daytrader.domain.DeploymentStatus
import daytrader.domain.StrategyDeployment
import daytrader.domain.isTouchTurn
import daytrader.domain.StrategyType
import daytrader.domain.FirstCandleCloseStatus
import daytrader.domain.FirstCandleColor
import daytrader.domain.LiquidityCandleEvaluation
import daytrader.domain.TouchTurnBracketSetup
import daytrader.domain.TouchTurnCandleStatus
import daytrader.domain.TouchTurnLogic
import daytrader.presentation.strategies.TouchTurnScreenLabels
import daytrader.domain.StrategySession
import daytrader.domain.TouchTurnSessionContext
import daytrader.domain.TouchTurnTradeSide
import daytrader.domain.TouchTurnPrepareOverallStatus
import daytrader.domain.TouchTurnPrepareStatus
import daytrader.domain.inProgressSession
import daytrader.domain.touchTurnAnalysisSessionForRun
import daytrader.domain.touchTurnRecapRun
import daytrader.domain.touchTurnRecapSessionPnl
import daytrader.domain.touchTurnRecapSessionTrades
import daytrader.domain.sessionDisplayPnL
import daytrader.presentation.strategies.TouchTurnSessionStartUiMapper
import kotlinx.coroutines.flow.map
import daytrader.presentation.Formatters
import daytrader.presentation.strategies.*
import daytrader.ui.theme.*

@Composable
fun StrategiesScreen(viewModel: StrategiesViewModel) {
    val chromeState by viewModel.chromeState.collectAsState()

    if (chromeState.showAddDialog) {
        val addPrefill = chromeState.addDialogPrefill
        AddStrategyDeploymentDialog(
            onDismiss = viewModel::onDismissAddDialog,
            defaultMaxDollarsFor = viewModel::defaultMaxDollarsFor,
            onResolveSymbol = viewModel::resolveInstrumentForSymbol,
            prefill = addPrefill,
            onCreate = viewModel::onCreateDeployment
        )
    }

    chromeState.symbolImport?.let { importState ->
        if (chromeState.showImportDialog) {
            DeploymentSymbolImportDialog(
                state = importState,
                onDismiss = viewModel::onDismissImportDialog,
                onPickFile = viewModel::onPickImportCsvFile,
                onImportTargetChange = viewModel::onImportTargetChange,
                onStrategyTypeChange = viewModel::onImportStrategyTypeChange,
                onMaxDollarsChange = viewModel::onImportMaxDollarsChange,
                onStartImport = viewModel::onStartSymbolImport
            )
        }
    }

    chromeState.instrumentBulkRefresh?.let { refreshState ->
        if (chromeState.showInstrumentBulkRefreshDialog) {
            InstrumentBulkRefreshDialog(
                state = refreshState,
                onDismiss = viewModel::onDismissInstrumentBulkRefreshDialog,
                onStart = viewModel::onStartInstrumentBulkRefresh
            )
        }
    }

    chromeState.startBlockedAlert?.let { alert ->
        StartBlockedByPositionDialog(
            alert = alert,
            onDismiss = viewModel::onDismissStartBlockedAlert
        )
    }

    StrategiesScreenContent(viewModel)
}

@Composable
private fun StrategiesScreenContent(viewModel: StrategiesViewModel) {
    val listState by viewModel.listState.collectAsState()

    Column(modifier = Modifier.fillMaxSize().padding(16.dp).testTag("StrategiesScreen")) {
        StrategiesHeader(
            searchQuery = listState.searchQuery,
            onSearchChange = viewModel::onSearchChange,
            onClearSearch = {
                if (listState.searchQuery.isNotEmpty()) viewModel.onSearchChange("")
            },
            onAddInstance = viewModel::onShowAddDialog,
            onImportSymbols = viewModel::onShowImportDialog
        )

        Spacer(modifier = Modifier.height(8.dp))

        StrategiesFilterPanel(
            deploymentFilter = listState.deploymentFilter,
            strategyTypeFilter = listState.strategyTypeFilter,
            hasActiveFilters = listState.hasActiveFilters,
            filteredCount = listState.filteredCount,
            canRefreshFilteredInstruments = listState.canRelookupInstrument,
            onDeploymentFilterChange = viewModel::onDeploymentFilterChange,
            onStrategyTypeFilterChange = viewModel::onStrategyTypeFilterChange,
            onClearFilters = viewModel::onClearFilters,
            onRefreshFilteredInstruments = viewModel::onShowInstrumentBulkRefreshDialog
        )

        Spacer(modifier = Modifier.height(10.dp))

        HorizontalSplitPane(
            modifier = Modifier.fillMaxWidth().weight(1f),
            leftContent = { StrategiesDeploymentList(viewModel) },
            rightContent = { StrategiesDeploymentDetail(viewModel) }
        )
    }
}

@Composable
private fun StrategiesDeploymentList(viewModel: StrategiesViewModel) {
    val listState by viewModel.listState.collectAsState()
    val selectedDeploymentId by viewModel.detailState
        .map { it.selectedDeploymentId }
        .collectAsState(initial = null)

    Column(
        modifier = Modifier
            .fillMaxSize()
            .border(1.dp, TableHeaderBg, RoundedCornerShape(8.dp))
            .background(SurfaceDark, RoundedCornerShape(8.dp))
            .padding(horizontal = 8.dp, vertical = 6.dp)
            .testTag("StrategyDeploymentList")
    ) {
        if (listState.globalClosedSessionHistoryCount > 0) {
            DeploymentsListHeader(
                closedSessionHistoryCount = listState.globalClosedSessionHistoryCount,
                hasInProgressSessions = listState.globalHasInProgressSessions,
                onDeleteAllSessionHistory = viewModel::onDeleteAllSessionHistoryForAllDeployments
            )
            Spacer(modifier = Modifier.height(4.dp))
        }

        listState.filteredSummary?.let { summary ->
            FilteredDeploymentsSummaryPanel(summary = summary)
            Spacer(modifier = Modifier.height(6.dp))
        }

        if (listState.filteredRows.isEmpty()) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(
                    "No deployments match your filter.",
                    color = TextSecondary,
                    fontSize = 13.sp
                )
            }
        } else {
            DeploymentListSortBar(
                activeSortColumn = listState.sortColumn,
                sortDirection = listState.sortDirection,
                onHeaderClick = viewModel::onDeploymentListHeaderClick
            )
            Spacer(modifier = Modifier.height(4.dp))
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                items(listState.filteredRows, key = { it.id }) { row ->
                    StrategyDeploymentCard(
                        row = row,
                        isSelected = row.id == selectedDeploymentId,
                        onSelect = { viewModel.onSelectDeployment(row.id) },
                        onToggleSession = { viewModel.onToggleSession(row.id) },
                        globalAutoStartEnabled = listState.globalAutoStartEnabled,
                        onAutoStartChange = { enabled ->
                            viewModel.onUpdateDeployment(row.id) {
                                it.copy(autoStartOnMarketOpen = enabled)
                            }
                        }
                    )
                }
            }
        }
    }
}

@Composable
private fun StrategiesDeploymentDetail(viewModel: StrategiesViewModel) {
    val detailState by viewModel.detailState.collectAsState()
    val liveState by viewModel.liveState.collectAsState()

    StrategyDeploymentDetailPanel(
        selectedDeployment = detailState.selectedDeployment,
        cardPresentation = detailState.selectedCardPresentation,
        detailTab = detailState.detailTab,
        globalAutoStartEnabled = detailState.globalAutoStartEnabled,
        sessionHistory = detailState.sessionHistory,
        liveExecution = detailState.liveExecution,
        liveBroker = liveState.liveBroker,
        liveSessionTrades = liveState.liveSessionTrades,
        touchTurnLiveOrderChart = liveState.touchTurnLiveOrderChart,
        touchTurnFormingBarPriceChart = liveState.touchTurnFormingBarPriceChart,
        touchTurnPipelineGraph = liveState.touchTurnPipelineGraph,
        touchTurnOrderLifecycle = liveState.touchTurnOrderLifecycle,
        touchTurnPrepare = detailState.touchTurnPrepare,
        tradingPanelShowsSessionRecap = detailState.tradingPanelShowsSessionRecap,
        tradingPanelRecapRunId = detailState.tradingPanelRecapRunId,
        tradingPanelShowsLiveMarketQuotes = liveState.tradingPanelShowsLiveMarketQuotes,
        sessionMarketDataCapture = liveState.sessionMarketDataCapture,
        onStopMarketDataCapture = viewModel::onStopSessionMarketDataCapture,
        onResetTradingPanel = viewModel::onResetTradingPanel,
        onTabChange = viewModel::onDetailTabChange,
        onResolveSymbol = viewModel::resolveInstrumentForSymbol,
        onUpdateDeployment = viewModel::onUpdateDeployment,
        onCopyTouchTurnRulesToOther = viewModel::onCopyTouchTurnRulesToOther,
        onCopyRiskBudgetToOther = viewModel::onCopyRiskBudgetToOther,
        deploymentCopyTargets = detailState.deploymentCopyTargets,
        canRelookupInstrument = detailState.canRelookupInstrument,
        instrumentRelookupInProgress = detailState.instrumentRelookupInProgress,
        instrumentRelookupMessage = detailState.instrumentRelookupMessage,
        onRelookupInstrument = viewModel::onRelookupDeploymentInstrument,
        onStartStop = viewModel::onToggleSession,
        onPrepareSession = viewModel::onPrepareSession,
        onSessionHistoryHeaderClick = viewModel::onSessionHistoryHeaderClick,
        onSelectSessionHistory = viewModel::onSelectSessionHistory,
        onDeleteSessionHistory = viewModel::onDeleteSessionHistory,
        onDeleteAllSessionHistory = viewModel::onDeleteAllSessionHistory,
        onAdjustStop = viewModel::onAdjustStop,
        onClosePosition = viewModel::onClosePosition,
        onDuplicate = viewModel::onDuplicateSelected,
        onDelete = viewModel::onDeleteSelected,
        modifier = Modifier.fillMaxSize()
    )
}

@Composable
private fun StrategyDeploymentDetailPanel(
    selectedDeployment: StrategyDeployment?,
    cardPresentation: DeploymentCardPresentation?,
    detailTab: StrategyDetailTab,
    globalAutoStartEnabled: Boolean,
    sessionHistory: SessionHistoryUiState?,
    liveExecution: LiveExecutionUiState?,
    liveBroker: LiveBrokerUiState?,
    liveSessionTrades: LiveSessionTradesUiState?,
    touchTurnLiveOrderChart: TouchTurnLiveOrderChartUiState?,
    touchTurnFormingBarPriceChart: TouchTurnLiveOrderChartUiState?,
    touchTurnPipelineGraph: TouchTurnPipelineGraph?,
    touchTurnOrderLifecycle: TouchTurnOrderLifecycleUi?,
    touchTurnPrepare: TouchTurnPrepareUiState?,
    tradingPanelShowsSessionRecap: Boolean,
    tradingPanelRecapRunId: String?,
    tradingPanelShowsLiveMarketQuotes: Boolean,
    sessionMarketDataCapture: SessionMarketDataCaptureUi?,
    onStopMarketDataCapture: (String) -> Unit,
    onResetTradingPanel: (String) -> Unit,
    onTabChange: (StrategyDetailTab) -> Unit,
    onResolveSymbol: (String, (Result<InstrumentResolution>) -> Unit) -> Unit,
    onUpdateDeployment: (String, (StrategyDeployment) -> StrategyDeployment) -> Unit,
    onCopyTouchTurnRulesToOther: (String, Set<String>) -> Unit,
    onCopyRiskBudgetToOther: (String, Set<String>) -> Unit,
    deploymentCopyTargets: List<StrategyDeploymentCopyTarget>,
    canRelookupInstrument: Boolean,
    instrumentRelookupInProgress: Boolean,
    instrumentRelookupMessage: String?,
    onRelookupInstrument: (String) -> Unit,
    onStartStop: (String) -> Unit,
    onPrepareSession: (String) -> Unit,
    onSessionHistoryHeaderClick: (SessionHistorySortColumn) -> Unit,
    onSelectSessionHistory: (String) -> Unit,
    onDeleteSessionHistory: (String, String) -> Unit,
    onDeleteAllSessionHistory: (String) -> Unit,
    onAdjustStop: (String, String) -> Unit,
    onClosePosition: (String) -> Unit,
    onDuplicate: () -> Unit,
    onDelete: () -> Unit,
    modifier: Modifier = Modifier
) {
    val accent = cardPresentation?.accent ?: DeploymentCardAccent.STOPPED_IDLE
    InstanceCardChrome(
        accent = accent,
        modifier = modifier.testTag("StrategyDeploymentDetail")
    ) {
        if (selectedDeployment == null) {
            StrategyDetailEmptyState(modifier = Modifier.fillMaxSize())
        } else {
            StrategyDeploymentDetail(
                instance = selectedDeployment,
                cardPresentation = cardPresentation,
                detailTab = detailTab,
                globalAutoStartEnabled = globalAutoStartEnabled,
                sessionHistory = sessionHistory,
                liveExecution = liveExecution,
                liveBroker = liveBroker,
                liveSessionTrades = liveSessionTrades,
                touchTurnLiveOrderChart = touchTurnLiveOrderChart,
                touchTurnFormingBarPriceChart = touchTurnFormingBarPriceChart,
                touchTurnPipelineGraph = touchTurnPipelineGraph,
                touchTurnOrderLifecycle = touchTurnOrderLifecycle,
                touchTurnPrepare = touchTurnPrepare,
                tradingPanelShowsSessionRecap = tradingPanelShowsSessionRecap,
                tradingPanelRecapRunId = tradingPanelRecapRunId,
                tradingPanelShowsLiveMarketQuotes = tradingPanelShowsLiveMarketQuotes,
                sessionMarketDataCapture = sessionMarketDataCapture,
                onStopMarketDataCapture = { onStopMarketDataCapture(selectedDeployment.id) },
                onResetTradingPanel = { onResetTradingPanel(selectedDeployment.id) },
                onTabChange = onTabChange,
                onResolveSymbol = onResolveSymbol,
                onUpdate = { transform -> onUpdateDeployment(selectedDeployment.id, transform) },
                onCopyTouchTurnRulesToOther = { marketZoneIds ->
                    onCopyTouchTurnRulesToOther(selectedDeployment.id, marketZoneIds)
                },
                onCopyRiskBudgetToOther = { marketZoneIds ->
                    onCopyRiskBudgetToOther(selectedDeployment.id, marketZoneIds)
                },
                deploymentCopyTargets = deploymentCopyTargets,
                canRelookupInstrument = canRelookupInstrument,
                instrumentRelookupInProgress = instrumentRelookupInProgress,
                instrumentRelookupMessage = instrumentRelookupMessage,
                onRelookupInstrument = onRelookupInstrument,
                onStartStop = { onStartStop(selectedDeployment.id) },
                onPrepareSession = { onPrepareSession(selectedDeployment.id) },
                onSessionHistoryHeaderClick = onSessionHistoryHeaderClick,
                onSelectSessionHistory = onSelectSessionHistory,
                onDeleteSessionHistory = onDeleteSessionHistory,
                onDeleteAllSessionHistory = onDeleteAllSessionHistory,
                onAdjustStop = onAdjustStop,
                onClosePosition = onClosePosition,
                onDuplicate = onDuplicate,
                onDelete = onDelete
            )
        }
    }
}

@Composable
private fun StrategiesFilterPanel(
    deploymentFilter: DeploymentFilter,
    strategyTypeFilter: StrategyType?,
    hasActiveFilters: Boolean,
    filteredCount: Int,
    canRefreshFilteredInstruments: Boolean,
    onDeploymentFilterChange: (DeploymentFilter) -> Unit,
    onStrategyTypeFilterChange: (StrategyType?) -> Unit,
    onClearFilters: () -> Unit,
    onRefreshFilteredInstruments: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(SurfaceDark, RoundedCornerShape(6.dp))
            .border(1.dp, TableHeaderBg, RoundedCornerShape(6.dp))
            .padding(horizontal = 8.dp, vertical = 5.dp)
            .testTag("StrategiesFilterPanel")
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                FilterChip(
                    label = "All",
                    selected = deploymentFilter == DeploymentFilter.ALL,
                    onClick = { onDeploymentFilterChange(DeploymentFilter.ALL) }
                )
                FilterChip(
                    label = "Active",
                    selected = deploymentFilter == DeploymentFilter.RUNNING,
                    onClick = { onDeploymentFilterChange(DeploymentFilter.RUNNING) }
                )
                FilterChip(
                    label = "Stopped",
                    selected = deploymentFilter == DeploymentFilter.STOPPED,
                    onClick = { onDeploymentFilterChange(DeploymentFilter.STOPPED) }
                )
            }
            Box(
                modifier = Modifier
                    .padding(horizontal = 8.dp)
                    .width(1.dp)
                    .height(16.dp)
                    .background(TableHeaderBg)
            )
            Row(
                modifier = Modifier
                    .weight(1f)
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                FilterChip(
                    label = "All types",
                    selected = strategyTypeFilter == null,
                    onClick = { onStrategyTypeFilterChange(null) }
                )
                StrategyType.entries.forEach { type ->
                    FilterChip(
                        label = StrategyCatalog.displayName(type),
                        selected = strategyTypeFilter == type,
                        onClick = {
                            onStrategyTypeFilterChange(if (strategyTypeFilter == type) null else type)
                        }
                    )
                }
            }
            if (hasActiveFilters) {
                TextButton(
                    onClick = onClearFilters,
                    contentPadding = PaddingValues(horizontal = 6.dp, vertical = 0.dp),
                    modifier = Modifier.height(20.dp)
                ) {
                    Text("Clear", color = GainGreen, fontSize = 10.sp)
                }
            }
            OutlinedButton(
                onClick = onRefreshFilteredInstruments,
                enabled = canRefreshFilteredInstruments && filteredCount > 0,
                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp),
                modifier = Modifier
                    .height(24.dp)
                    .testTag("RefreshFilteredInstrumentsButton"),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = Color.White)
            ) {
                Icon(
                    Icons.Default.Refresh,
                    contentDescription = "Refresh filtered instruments",
                    modifier = Modifier.size(12.dp)
                )
                Spacer(modifier = Modifier.width(4.dp))
                Text("Refresh IB ($filteredCount)", fontSize = 10.sp, maxLines = 1)
            }
        }
    }
}


@Composable
private fun StrategyDetailEmptyState(modifier: Modifier = Modifier) {
    Box(modifier = modifier, contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(Icons.Default.TouchApp, contentDescription = null, tint = TextSecondary, modifier = Modifier.size(48.dp))
            Spacer(modifier = Modifier.height(12.dp))
            Text("Select a deployment", color = TextSecondary, fontSize = 14.sp)
        }
    }
}

@Composable
private fun StrategyDeploymentDetail(
    instance: StrategyDeployment,
    cardPresentation: DeploymentCardPresentation?,
    detailTab: StrategyDetailTab,
    globalAutoStartEnabled: Boolean,
    sessionHistory: SessionHistoryUiState?,
    liveExecution: LiveExecutionUiState?,
    liveBroker: LiveBrokerUiState?,
    liveSessionTrades: LiveSessionTradesUiState?,
    touchTurnLiveOrderChart: TouchTurnLiveOrderChartUiState?,
    touchTurnFormingBarPriceChart: TouchTurnLiveOrderChartUiState?,
    touchTurnPipelineGraph: TouchTurnPipelineGraph?,
    touchTurnOrderLifecycle: TouchTurnOrderLifecycleUi?,
    touchTurnPrepare: TouchTurnPrepareUiState?,
    tradingPanelShowsSessionRecap: Boolean,
    tradingPanelRecapRunId: String?,
    tradingPanelShowsLiveMarketQuotes: Boolean,
    sessionMarketDataCapture: SessionMarketDataCaptureUi?,
    onStopMarketDataCapture: () -> Unit,
    onResetTradingPanel: () -> Unit,
    onTabChange: (StrategyDetailTab) -> Unit,
    onResolveSymbol: (String, (Result<InstrumentResolution>) -> Unit) -> Unit,
    onUpdate: ((StrategyDeployment) -> StrategyDeployment) -> Unit,
    onCopyTouchTurnRulesToOther: (Set<String>) -> Unit,
    onCopyRiskBudgetToOther: (Set<String>) -> Unit,
    deploymentCopyTargets: List<StrategyDeploymentCopyTarget>,
    canRelookupInstrument: Boolean,
    instrumentRelookupInProgress: Boolean,
    instrumentRelookupMessage: String?,
    onRelookupInstrument: (String) -> Unit,
    onStartStop: () -> Unit,
    onPrepareSession: () -> Unit,
    onSessionHistoryHeaderClick: (SessionHistorySortColumn) -> Unit,
    onSelectSessionHistory: (String) -> Unit,
    onDeleteSessionHistory: (String, String) -> Unit,
    onDeleteAllSessionHistory: (String) -> Unit,
    onAdjustStop: (String, String) -> Unit,
    onClosePosition: (String) -> Unit,
    onDuplicate: () -> Unit,
    onDelete: () -> Unit
) {
    Column(modifier = Modifier.fillMaxSize()) {
        Column(modifier = Modifier.padding(20.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.Top
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(StrategyUiMapper.displayName(instance), fontSize = 22.sp, fontWeight = FontWeight.Bold, color = Color.White)
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        StrategyUiMapper.strategyDisplayName(instance),
                        fontSize = 14.sp,
                        color = BrandRed,
                        fontWeight = FontWeight.Medium
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        StrategyUiMapper.strategyDescription(instance),
                        fontSize = 12.sp,
                        color = TextSecondary
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (instance.isTouchTurn &&
                        instance.status != DeploymentStatus.RUNNING
                    ) {
                        val prepareBusy = touchTurnPrepare?.inProgress == true
                        OutlinedButton(
                            onClick = onPrepareSession,
                            enabled = !prepareBusy,
                            shape = RoundedCornerShape(6.dp),
                            modifier = Modifier.testTag("TouchTurnPrepareButton")
                        ) {
                            if (prepareBusy) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(14.dp),
                                    strokeWidth = 2.dp,
                                    color = TextSecondary
                                )
                            } else {
                                Icon(
                                    Icons.Default.Checklist,
                                    contentDescription = null,
                                    modifier = Modifier.size(16.dp)
                                )
                            }
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(if (prepareBusy) "Preparing…" else "Prepare")
                        }
                    }
                    Button(
                        onClick = onStartStop,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = if (instance.status == DeploymentStatus.RUNNING) SurfaceDark else GainGreen
                        ),
                        shape = RoundedCornerShape(6.dp)
                    ) {
                        Icon(
                            if (instance.status == DeploymentStatus.RUNNING) Icons.Default.Stop else Icons.Default.PlayArrow,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(if (instance.status == DeploymentStatus.RUNNING) "End session" else "Start session")
                    }
                    OutlinedButton(onClick = onDuplicate, shape = RoundedCornerShape(6.dp)) {
                        Icon(Icons.Default.ContentCopy, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Duplicate")
                    }
                    IconButton(onClick = onDelete) {
                        Icon(Icons.Default.Delete, contentDescription = "Delete", tint = LossRed)
                    }
                }
            }
        }

        TabRow(
            selectedTabIndex = detailTab.ordinal,
            containerColor = TableHeaderBg,
            contentColor = Color.White,
            divider = { HorizontalDivider(color = DarkBackground) }
        ) {
            StrategyDetailTab.entries.forEach { tab ->
                Tab(
                    selected = detailTab == tab,
                    onClick = { onTabChange(tab) },
                    text = {
                        Text(tab.displayLabel(), fontSize = 13.sp)
                    }
                )
            }
        }

        if (sessionMarketDataCapture != null) {
            SessionMarketDataCaptureBar(
                capture = sessionMarketDataCapture,
                onStop = onStopMarketDataCapture,
                modifier = Modifier.testTag("SessionMarketDataCaptureBar")
            )
        }

        if (detailTab == StrategyDetailTab.LIVE && tradingPanelShowsLiveMarketQuotes) {
            liveBroker?.let { broker -> TradingTabLiveMarketStrip(broker) }
        }

        if (detailTab == StrategyDetailTab.SESSION_HISTORY) {
            PerformanceTab(
                sessionHistory = sessionHistory,
                onSessionHistoryHeaderClick = onSessionHistoryHeaderClick,
                onSelectRun = onSelectSessionHistory,
                onDeleteRun = { runId -> onDeleteSessionHistory(instance.id, runId) },
                onDeleteAllRuns = { onDeleteAllSessionHistory(instance.id) },
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .padding(20.dp),
            )
        } else {
            Column(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(20.dp)
            ) {
                when (detailTab) {
                    StrategyDetailTab.CONFIGURATION -> ConfigurationTab(
                        instance = instance,
                        globalAutoStartEnabled = globalAutoStartEnabled,
                        touchTurnPrepare = touchTurnPrepare,
                        deploymentCopyTargets = deploymentCopyTargets,
                        onResolveSymbol = onResolveSymbol,
                        onUpdate = onUpdate,
                        onCopyTouchTurnRulesToOther = onCopyTouchTurnRulesToOther,
                        onCopyRiskBudgetToOther = onCopyRiskBudgetToOther,
                        canRelookupInstrument = canRelookupInstrument,
                        instrumentRelookupInProgress = instrumentRelookupInProgress,
                        instrumentRelookupMessage = instrumentRelookupMessage,
                        onRelookupInstrument = { onRelookupInstrument(instance.id) }
                    )
                    StrategyDetailTab.LIVE -> LiveTab(
                        instance = instance,
                        liveExecution = liveExecution,
                        liveBroker = liveBroker,
                        liveSessionTrades = liveSessionTrades,
                        touchTurnLiveOrderChart = touchTurnLiveOrderChart,
                        touchTurnFormingBarPriceChart = touchTurnFormingBarPriceChart,
                        touchTurnPipelineGraph = touchTurnPipelineGraph,
                        touchTurnOrderLifecycle = touchTurnOrderLifecycle,
                        showSessionRecap = tradingPanelShowsSessionRecap,
                        tradingPanelRecapRunId = tradingPanelRecapRunId,
                        onResetTradingPanel = onResetTradingPanel,
                        onAdjustStop = onAdjustStop,
                        onClosePosition = onClosePosition
                    )
                    StrategyDetailTab.SESSION_HISTORY -> Unit
                }
            }
        }

        HorizontalDivider(color = DarkBackground)
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(TableHeaderBg)
                .padding(horizontal = 20.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text("Last update: ${instance.live.updatedAt}", fontSize = 11.sp, color = TextSecondary)
            val chipLabel = cardPresentation?.chipLabel ?: "Stopped"
            val chipAccent = cardPresentation?.accent ?: DeploymentCardAccent.STOPPED_IDLE
            InstanceStateChip(label = chipLabel, accent = chipAccent)
        }
    }
}

private fun deploymentCopyTargetCount(
    sourceId: String,
    deploymentCopyTargets: List<StrategyDeploymentCopyTarget>,
    selectedMarketZoneIds: Set<String>
): Int = deploymentCopyTargets.count { target ->
    target.id != sourceId &&
        selectedMarketZoneIds.any { DeploymentMarket.zonesMatch(it, target.marketZoneId) }
}

@Composable
private fun DeploymentMarketCopyDialog(
    title: String,
    description: String,
    confirmButtonText: String,
    marketCheckboxTestTagPrefix: String,
    dialogTestTag: String,
    sourceId: String,
    deploymentCopyTargets: List<StrategyDeploymentCopyTarget>,
    onDismiss: () -> Unit,
    onConfirm: (Set<String>) -> Unit
) {
    val selectedMarkets = remember {
        mutableStateMapOf<String, Boolean>().apply {
            RthMarketSessions.all.forEach { session ->
                put(session.zoneId, true)
            }
        }
    }
    val selectedZoneIds = selectedMarkets.filterValues { it }.keys
    val marketCounts = remember(sourceId, deploymentCopyTargets) {
        RthMarketSessions.all.associate { session ->
            session.zoneId to deploymentCopyTargetCount(sourceId, deploymentCopyTargets, setOf(session.zoneId))
        }
    }
    val targetCount = remember(sourceId, deploymentCopyTargets, selectedZoneIds) {
        deploymentCopyTargetCount(sourceId, deploymentCopyTargets, selectedZoneIds)
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = SurfaceDark,
        title = {
            Text(title, color = Color.White, fontWeight = FontWeight.Bold)
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    description,
                    color = TextSecondary,
                    fontSize = 14.sp,
                    lineHeight = 18.sp
                )
                RthMarketSessions.all.forEach { session ->
                    val marketCount = marketCounts[session.zoneId] ?: 0
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("$marketCheckboxTestTagPrefix-${session.label}"),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Checkbox(
                            checked = selectedMarkets[session.zoneId] == true,
                            onCheckedChange = { checked ->
                                selectedMarkets[session.zoneId] = checked
                            },
                            colors = CheckboxDefaults.colors(
                                checkedColor = BrandRed,
                                checkmarkColor = Color.White
                            )
                        )
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                DeploymentMarket.sessionDisplayLabel(session),
                                fontSize = 13.sp,
                                color = Color.White,
                                fontWeight = FontWeight.Medium
                            )
                            Text(
                                "$marketCount deployment${if (marketCount == 1) "" else "s"}",
                                fontSize = 11.sp,
                                color = TextSecondary
                            )
                        }
                    }
                }
                Text(
                    if (selectedZoneIds.isEmpty()) {
                        "Select at least one market."
                    } else if (targetCount == 0) {
                        "No other deployments match the selected markets."
                    } else {
                        "Apply to $targetCount deployment${if (targetCount == 1) "" else "s"}."
                    },
                    fontSize = 13.sp,
                    color = if (targetCount > 0 && selectedZoneIds.isNotEmpty()) Color.White else TextSecondary,
                    fontWeight = FontWeight.Medium
                )
            }
        },
        confirmButton = {
            Button(
                onClick = { onConfirm(selectedZoneIds) },
                enabled = selectedZoneIds.isNotEmpty() && targetCount > 0,
                colors = ButtonDefaults.buttonColors(containerColor = GainGreen)
            ) {
                Text(confirmButtonText)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel", color = TextSecondary)
            }
        },
        modifier = Modifier.testTag(dialogTestTag)
    )
}

@Composable
private fun TouchTurnRulesCopyDialog(
    sourceId: String,
    deploymentCopyTargets: List<StrategyDeploymentCopyTarget>,
    onDismiss: () -> Unit,
    onConfirm: (Set<String>) -> Unit
) {
    DeploymentMarketCopyDialog(
        title = "Copy Touch Turn rules",
        description = "Apply this deployment's rules and thresholds to other deployments in the " +
            "selected markets. Changes apply on the next session start.",
        confirmButtonText = "Copy rules",
        marketCheckboxTestTagPrefix = "TouchTurnRulesCopyMarket",
        dialogTestTag = "TouchTurnRulesCopyToAllDialog",
        sourceId = sourceId,
        deploymentCopyTargets = deploymentCopyTargets,
        onDismiss = onDismiss,
        onConfirm = onConfirm
    )
}

@Composable
private fun RiskBudgetCopyDialog(
    sourceId: String,
    deploymentCopyTargets: List<StrategyDeploymentCopyTarget>,
    onDismiss: () -> Unit,
    onConfirm: (Set<String>) -> Unit
) {
    DeploymentMarketCopyDialog(
        title = "Copy risk budget",
        description = "Apply this deployment's risk budget to other deployments in the selected markets.",
        confirmButtonText = "Copy risk budget",
        marketCheckboxTestTagPrefix = "RiskBudgetCopyMarket",
        dialogTestTag = "RiskBudgetCopyToAllDialog",
        sourceId = sourceId,
        deploymentCopyTargets = deploymentCopyTargets,
        onDismiss = onDismiss,
        onConfirm = onConfirm
    )
}

@Composable
private fun ConfigurationTab(
    instance: StrategyDeployment,
    globalAutoStartEnabled: Boolean,
    touchTurnPrepare: TouchTurnPrepareUiState?,
    deploymentCopyTargets: List<StrategyDeploymentCopyTarget>,
    onResolveSymbol: (String, (Result<InstrumentResolution>) -> Unit) -> Unit,
    onUpdate: ((StrategyDeployment) -> StrategyDeployment) -> Unit,
    onCopyTouchTurnRulesToOther: (Set<String>) -> Unit,
    onCopyRiskBudgetToOther: (Set<String>) -> Unit,
    canRelookupInstrument: Boolean,
    instrumentRelookupInProgress: Boolean,
    instrumentRelookupMessage: String?,
    onRelookupInstrument: () -> Unit
) {
    val canEdit = instance.status != DeploymentStatus.RUNNING
    val otherDeploymentCount = remember(instance.id, deploymentCopyTargets) {
        deploymentCopyTargets.count { it.id != instance.id }
    }

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        if (!globalAutoStartEnabled) {
            Text(
                "Global auto-start is OFF (top bar). Per-deployment settings are saved but won't run until re-enabled.",
                fontSize = 12.sp,
                color = LossRed,
                lineHeight = 15.sp
            )
        }
        ConfigField(
            label = "Symbol",
            value = instance.symbol,
            enabled = false,
            onValueChange = {}
        )
        DeploymentMarketSection(
            deployment = instance,
            canEdit = canEdit,
            onResolveSymbol = onResolveSymbol,
            onUpdate = onUpdate,
            canRelookupInstrument = canRelookupInstrument,
            instrumentRelookupInProgress = instrumentRelookupInProgress,
            instrumentRelookupMessage = instrumentRelookupMessage,
            onRelookupInstrument = onRelookupInstrument
        )
        if (!canEdit) {
            Text(
                "End the session to edit configuration.",
                fontSize = 12.sp,
                color = TextSecondary
            )
        }
        AutoStartOnMarketOpenField(
            checked = instance.autoStartOnMarketOpen,
            enabled = canEdit && globalAutoStartEnabled,
            onCheckedChange = { enabled ->
                onUpdate { it.copy(autoStartOnMarketOpen = enabled) }
            }
        )
        var showCopyRiskBudgetConfirm by remember { mutableStateOf(false) }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.Bottom
        ) {
            ConfigField(
                label = "Risk budget (\$)",
                value = instance.maxDollars.toString(),
                enabled = canEdit,
                onValueChange = { value ->
                    value.toIntOrNull()?.takeIf { it > 0 }?.let { max ->
                        onUpdate { it.copy(maxDollars = max) }
                    }
                },
                modifier = Modifier.weight(1f)
            )
            OutlinedButton(
                onClick = { showCopyRiskBudgetConfirm = true },
                enabled = canEdit && otherDeploymentCount > 0,
                modifier = Modifier.testTag("RiskBudgetCopyToAllButton")
            ) {
                Text(
                    "Copy budget…",
                    color = if (canEdit && otherDeploymentCount > 0) Color.White else TextSecondary
                )
            }
        }
        if (showCopyRiskBudgetConfirm) {
            RiskBudgetCopyDialog(
                sourceId = instance.id,
                deploymentCopyTargets = deploymentCopyTargets,
                onDismiss = { showCopyRiskBudgetConfirm = false },
                onConfirm = { marketZoneIds ->
                    showCopyRiskBudgetConfirm = false
                    onCopyRiskBudgetToOther(marketZoneIds)
                }
            )
        }
        if (instance.isTouchTurn) {
            var showTouchTurnRules by remember(instance.id, instance.touchTurnRules) {
                mutableStateOf(false)
            }
            var showCopyRulesConfirm by remember { mutableStateOf(false) }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedButton(
                    onClick = { showTouchTurnRules = true },
                    enabled = canEdit,
                    modifier = Modifier.testTag("TouchTurnRulesConfigButton")
                ) {
                    Text("Touch Turn rules…", color = if (canEdit) Color.White else TextSecondary)
                }
                OutlinedButton(
                    onClick = { showCopyRulesConfirm = true },
                    enabled = canEdit && otherDeploymentCount > 0,
                    modifier = Modifier.testTag("TouchTurnRulesCopyToAllButton")
                ) {
                    Text(
                        "Copy rules…",
                        color = if (canEdit && otherDeploymentCount > 0) Color.White else TextSecondary
                    )
                }
            }
            if (showCopyRulesConfirm) {
                TouchTurnRulesCopyDialog(
                    sourceId = instance.id,
                    deploymentCopyTargets = deploymentCopyTargets,
                    onDismiss = { showCopyRulesConfirm = false },
                    onConfirm = { marketZoneIds ->
                        showCopyRulesConfirm = false
                        onCopyTouchTurnRulesToOther(marketZoneIds)
                    }
                )
            }
            if (showTouchTurnRules) {
                TouchTurnRulesConfigDialog(
                    initialRules = instance.touchTurnRules,
                    enabled = canEdit,
                    onDismiss = { showTouchTurnRules = false },
                    onSave = { rules ->
                        onUpdate { it.copy(touchTurnRules = rules) }
                        showTouchTurnRules = false
                    }
                )
            }
            touchTurnPrepare?.let { prepare ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.Top
                ) {
                    TouchTurnPrepareChecklist(
                        prepare = prepare,
                        modifier = Modifier.weight(1f)
                    )
                    TouchTurnMarketOpenTimers(
                        deployment = instance,
                        modifier = Modifier.weight(1f)
                    )
                }
            } ?: TouchTurnMarketOpenTimers(deployment = instance)
        }
    }
}

@Composable
private fun TouchTurnPrepareChecklist(
    prepare: TouchTurnPrepareUiState,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(SurfaceDark, RoundedCornerShape(6.dp))
            .padding(horizontal = 10.dp, vertical = 8.dp)
            .testTag("TouchTurnPrepareChecklist"),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                "Pre-market prepare",
                fontSize = 11.sp,
                fontWeight = FontWeight.SemiBold,
                color = Color.White
            )
            val statusLabel = when {
                prepare.inProgress -> "Running…"
                prepare.readyForStart -> "Ready for Start"
                prepare.stale -> "Stale — re-run Prepare"
                prepare.overallStatus == TouchTurnPrepareOverallStatus.WARN -> "Ready (warnings)"
                prepare.overallStatus != null -> prepare.overallStatus.name
                else -> "Not run"
            }
            Text(
                statusLabel,
                fontSize = 10.sp,
                color = touchTurnPrepareStatusColor(prepare),
                fontWeight = FontWeight.Medium
            )
        }
        prepare.preparedAtLabel?.let { at ->
            Text("Last run $at (market local)", fontSize = 10.sp, color = TextSecondary)
        }
        if (prepare.checks.isEmpty() && !prepare.inProgress) {
            Text(
                "Use Prepare before Start to validate IB, bootstrap history, and cache signal context for today.",
                fontSize = 10.sp,
                color = TextSecondary,
                lineHeight = 14.sp
            )
        }
        prepare.checks.forEach { row ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.Top
            ) {
                Text(
                    touchTurnPrepareStatusGlyph(row.status),
                    fontSize = 11.sp,
                    color = touchTurnPrepareCheckColor(row.status),
                    modifier = Modifier.width(14.dp)
                )
                Column(modifier = Modifier.weight(1f)) {
                    Text(row.label, fontSize = 10.sp, color = Color.White)
                    row.detail?.let { detail ->
                        Text(detail, fontSize = 9.sp, color = TextSecondary, lineHeight = 12.sp)
                    }
                }
            }
        }
    }
}

private fun touchTurnPrepareStatusGlyph(status: TouchTurnPrepareStatus): String = when (status) {
    TouchTurnPrepareStatus.PASS -> "✓"
    TouchTurnPrepareStatus.WARN -> "!"
    TouchTurnPrepareStatus.FAIL -> "✗"
}

private fun touchTurnPrepareCheckColor(status: TouchTurnPrepareStatus): Color = when (status) {
    TouchTurnPrepareStatus.PASS -> GainGreen
    TouchTurnPrepareStatus.WARN -> MarketOpenBorder
    TouchTurnPrepareStatus.FAIL -> LossRed
}

private fun touchTurnPrepareStatusColor(prepare: TouchTurnPrepareUiState): Color = when {
    prepare.inProgress -> TextSecondary
    prepare.readyForStart -> GainGreen
    prepare.overallStatus == TouchTurnPrepareOverallStatus.WARN -> MarketOpenBorder
    prepare.overallStatus == TouchTurnPrepareOverallStatus.FAIL -> LossRed
    prepare.stale -> MarketOpenBorder
    else -> TextSecondary
}

@Composable
private fun TouchTurnMarketOpenTimers(
    deployment: daytrader.domain.StrategyDeployment,
    modifier: Modifier = Modifier
) {
    val marketZone = DeploymentMarket.effectiveZoneId(deployment)
    val secondTick = LocalUiSecondTick.current
    val timers = remember(marketZone, secondTick) { TouchTurnScreenLabels.marketOpenTimers(marketZone) }
    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(SurfaceDark, RoundedCornerShape(6.dp))
            .padding(horizontal = 10.dp, vertical = 8.dp)
            .testTag("TouchTurnMarketOpenTimers"),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Text(
            "Market session (${timers.zoneAbbrev})",
            fontSize = 11.sp,
            fontWeight = FontWeight.SemiBold,
            color = Color.White
        )
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .testTag("TouchTurnSinceMarketOpen"),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("Since market open", fontSize = 10.sp, color = TextSecondary)
            Text(
                timers.elapsedSinceOpen,
                fontSize = 13.sp,
                fontWeight = FontWeight.Bold,
                color = Color.White,
                modifier = Modifier.testTag("TouchTurnSinceMarketOpenElapsed")
            )
        }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .testTag("TouchTurnUntilNextMarketOpen"),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                "Next open at ${timers.nextOpenAt}",
                fontSize = 10.sp,
                color = TextSecondary,
                modifier = Modifier.weight(1f)
            )
            Text(
                timers.countdownToNextOpen,
                fontSize = 13.sp,
                fontWeight = FontWeight.Bold,
                color = Color(0xFFFFB74D),
                modifier = Modifier.testTag("TouchTurnUntilNextMarketOpenCountdown")
            )
        }
    }
}

@Composable
private fun TouchTurnFirstCandleSection(session: TouchTurnSessionContext?, symbol: String) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(DarkBackground, RoundedCornerShape(8.dp))
            .padding(horizontal = 10.dp, vertical = 8.dp)
            .testTag("TouchTurnFirstCandleSection"),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Text(
            "Touch Turn — session open",
            fontSize = 12.sp,
            fontWeight = FontWeight.SemiBold,
            color = Color.White
        )
        when {
            session == null -> Text(
                "Loading Touch Turn session from IB…",
                fontSize = 12.sp,
                color = TextSecondary
            )
            session.status == TouchTurnCandleStatus.LOADING -> Text(
                "Loading opening bar and 14-day ADR from IB…",
                fontSize = 12.sp,
                color = TextSecondary
            )
            session.status == TouchTurnCandleStatus.FAILED -> Text(
                session.errorMessage ?: "Failed to load session data.",
                fontSize = 12.sp,
                color = LossRed
            )
            session.status == TouchTurnCandleStatus.READY && session.candle == null -> {
                val secondTick = LocalUiSecondTick.current
                val closeStatus = remember(session, secondTick) { session.candleCloseStatus() }
                val currency = session.currencyCode
                val fmt: (Double) -> String = { Formatters.moneyPlain(it, currency) }
                val statusMessage = when (closeStatus) {
                    FirstCandleCloseStatus.FORMING ->
                        "15-minute bar still forming — OHLC available after bar close."
                    FirstCandleCloseStatus.CLOSED ->
                        "Bar closed — loading final 15-minute OHLC from IB…"
                    else -> "Opening bar timing unknown."
                }
                Text(statusMessage, fontSize = 12.sp, color = TextSecondary)
                session.openingBarTime?.let { time ->
                    Text(time, fontSize = 10.sp, color = TextSecondary, maxLines = 1)
                }
                session.adr14?.let { adr ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        TouchTurnMetric("ADR (14d)", fmt(adr), Modifier.weight(1f), compact = true)
                        TouchTurnMetric("25% thresh.", fmt(session.rangeThreshold), Modifier.weight(1f), compact = true)
                    }
                }
            }
            session.candle != null -> {
                val secondTick = LocalUiSecondTick.current
                val closeStatus = remember(session, secondTick) { session.candleCloseStatus() }
                val liquidityEval = remember(session, secondTick) { session.liquidityEvaluation() }
                val candle = session.candle
                val currency = session.currencyCode
                val fmt: (Double) -> String = { Formatters.moneyPlain(it, currency) }

                TouchTurnPanelGroup(
                    title = "Opening 15-minute bar",
                    testTag = "TouchTurnOpeningBarGroup",
                    compact = true
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        verticalAlignment = Alignment.Top
                    ) {
                        Column(
                            modifier = Modifier.weight(1f),
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                candle.time?.let { time ->
                                    Text(time, fontSize = 10.sp, color = TextSecondary, maxLines = 1)
                                }
                                val closeColor = when (closeStatus) {
                                    FirstCandleCloseStatus.CLOSED -> GainGreen
                                    FirstCandleCloseStatus.FORMING -> Color(0xFFFFB74D)
                                    FirstCandleCloseStatus.UNKNOWN -> TextSecondary
                                }
                                Text(
                                    TouchTurnLogic.closeStatusLabel(closeStatus),
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    color = closeColor,
                                    modifier = Modifier.testTag("TouchTurnCandleCloseStatus")
                                )
                            }
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .testTag("TouchTurnDirectionPrices"),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                TouchTurnMetric("Open", fmt(candle.open), Modifier.weight(1f), compact = true)
                                TouchTurnMetric("Close", fmt(candle.close), Modifier.weight(1f), compact = true)
                            }
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                TouchTurnMetric("High", fmt(candle.high), Modifier.weight(1f), compact = true)
                                TouchTurnMetric("Low", fmt(candle.low), Modifier.weight(1f), compact = true)
                                TouchTurnMetric("Range", fmt(candle.range), Modifier.weight(1f), compact = true)
                            }
                        }
                        session.firstCandleColor()?.let { color ->
                            FirstCandleStick(
                                candle = candle,
                                color = color,
                                modifier = Modifier
                                    .testTag("TouchTurnCandleColor")
                                    .size(width = 28.dp, height = 52.dp)
                            )
                        }
                    }
                }

                TouchTurnPanelGroup(
                    title = "Liquidity bar check",
                    testTag = "TouchTurnLiquidityGroup",
                    compact = true
                ) {
                    session.adr14?.let { adr ->
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            TouchTurnMetric("ADR (14d)", fmt(adr), Modifier.weight(1f), compact = true)
                            TouchTurnMetric("25% thresh.", fmt(session.rangeThreshold), Modifier.weight(1f), compact = true)
                        }
                    }
                    when (liquidityEval) {
                        LiquidityCandleEvaluation.AWAITING_CLOSE -> {
                            Text(
                                "Waiting for bar to close…",
                                fontSize = 11.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = Color(0xFFFFB74D),
                                modifier = Modifier.testTag("TouchTurnLiquidityStatus")
                            )
                        }
                        LiquidityCandleEvaluation.LIQUIDITY -> {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    TouchTurnLogic.liquidityEvaluationLabel(liquidityEval),
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    color = GainGreen,
                                    modifier = Modifier.testTag("TouchTurnLiquidityStatus")
                                )
                                Text(
                                    "${fmt(candle.range)} > ${fmt(session.rangeThreshold)}",
                                    fontSize = 10.sp,
                                    color = TextSecondary
                                )
                            }
                        }
                        LiquidityCandleEvaluation.NOT_LIQUIDITY -> {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    TouchTurnLogic.liquidityEvaluationLabel(liquidityEval),
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    color = TextSecondary,
                                    modifier = Modifier.testTag("TouchTurnLiquidityStatus")
                                )
                                Text(
                                    "${fmt(candle.range)} ≤ ${fmt(session.rangeThreshold)}",
                                    fontSize = 10.sp,
                                    color = TextSecondary
                                )
                            }
                        }
                        LiquidityCandleEvaluation.UNKNOWN -> {
                            Text(
                                TouchTurnLogic.liquidityEvaluationLabel(liquidityEval),
                                fontSize = 11.sp,
                                color = TextSecondary,
                                modifier = Modifier.testTag("TouchTurnLiquidityStatus")
                            )
                        }
                    }
                }

                if (liquidityEval == LiquidityCandleEvaluation.LIQUIDITY &&
                    closeStatus == FirstCandleCloseStatus.CLOSED
                ) {
                    val orderSetup = remember(session, secondTick) {
                        session.setup?.takeIf { it.isLiquidityCandle }
                            ?: TouchTurnLogic.computeBracketSetup(
                                candle,
                                session.rangeThreshold,
                                session.rules
                            )
                    }
                    TouchTurnPanelGroup(
                        title = "Order preview (not sent)",
                        testTag = "TouchTurnOrderPreviewGroup",
                        compact = true
                    ) {
                        Text(
                            "Preview only — ${TouchTurnLogic.orderPreviewSummary(orderSetup, session.rules)}",
                            fontSize = 10.sp,
                            color = TextSecondary,
                            lineHeight = 13.sp
                        )
                        if (TouchTurnLogic.setupActionableForEntry(orderSetup, session.rules)) {
                            TouchTurnOrderPreviewChart(
                                candle = candle,
                                setup = orderSetup,
                                fmt = fmt,
                                modifier = Modifier.testTag("TouchTurnOrderPreviewChart")
                            )
                        } else {
                            Text(
                                "Flat opening candle — no directional entry.",
                                fontSize = 11.sp,
                                color = TextSecondary
                            )
                        }
                    }
                }

            }
        }
    }
}

@Composable
internal fun TouchTurnPanelGroup(
    title: String,
    testTag: String,
    compact: Boolean = false,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit
) {
    var expanded by rememberSaveable(testTag) { mutableStateOf(true) }
    val collapsible = compact
    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(SurfaceDark, RoundedCornerShape(6.dp))
            .border(1.dp, TableHeaderBg, RoundedCornerShape(6.dp))
            .padding(if (compact) 8.dp else 12.dp)
            .testTag(testTag),
        verticalArrangement = Arrangement.spacedBy(if (compact) 6.dp else 10.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .then(
                    if (collapsible) {
                        Modifier.clickable { expanded = !expanded }
                    } else {
                        Modifier
                    }
                ),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                title,
                fontSize = if (compact) 11.sp else 12.sp,
                fontWeight = FontWeight.SemiBold,
                color = BrandRed,
                modifier = Modifier.weight(1f)
            )
            if (collapsible) {
                Text(
                    text = if (expanded) "▾" else "▸",
                    fontSize = 10.sp,
                    color = TextSecondary,
                    modifier = Modifier.testTag("$testTag-CollapseToggle")
                )
            }
        }
        if (!compact) {
            HorizontalDivider(color = TableHeaderBg)
        }
        if (!collapsible || expanded) {
            content()
        }
    }
}

@Composable
internal fun FirstCandleStick(
    candle: OhlcBar,
    color: FirstCandleColor,
    modifier: Modifier = Modifier
) {
    val bodyColor = when (color) {
        FirstCandleColor.GREEN -> CandleGreen
        FirstCandleColor.RED -> CandleRed
        FirstCandleColor.DOJI -> TextSecondary
    }
    val minBodyPx = with(androidx.compose.ui.platform.LocalDensity.current) { 4.dp.toPx() }

    Canvas(modifier = modifier) {
        val centerX = size.width / 2f
        val height = size.height
        val bodyWidth = size.width * 0.42f
        val range = (candle.high - candle.low).toFloat().coerceAtLeast(0.0001f)

        fun y(price: Double): Float {
            val fraction = ((candle.high - price) / range).toFloat()
            return (fraction * height).coerceIn(0f, height)
        }

        val yHigh = y(candle.high)
        val yLow = y(candle.low)
        val yOpen = y(candle.open)
        val yClose = y(candle.close)
        val wickStroke = 2.dp.toPx()

        drawLine(
            color = bodyColor,
            start = Offset(centerX, yHigh),
            end = Offset(centerX, yLow),
            strokeWidth = wickStroke
        )

        when (color) {
            FirstCandleColor.DOJI -> {
                val halfW = bodyWidth / 2f
                drawLine(
                    color = bodyColor,
                    start = Offset(centerX - halfW, yClose),
                    end = Offset(centerX + halfW, yClose),
                    strokeWidth = wickStroke
                )
            }
            else -> {
                val top = minOf(yOpen, yClose)
                val bottom = maxOf(yOpen, yClose)
                val bodyHeight = (bottom - top).coerceAtLeast(minBodyPx)
                drawRect(
                    color = bodyColor,
                    topLeft = Offset(centerX - bodyWidth / 2f, top),
                    size = Size(bodyWidth, bodyHeight)
                )
            }
        }
    }
}

private data class TouchTurnPriceLevel(
    val price: Double,
    val label: String,
    val color: Color,
    val strokeWidthDp: Float = 2f,
    val kind: TouchTurnOrderLevelKind? = null
)

@Composable
internal fun TouchTurnOrderPreviewChart(
    candle: OhlcBar,
    setup: TouchTurnBracketSetup,
    fmt: (Double) -> String,
    executedLevels: Set<TouchTurnOrderLevelKind> = emptySet(),
    modifier: Modifier = Modifier
) {
    val showThrob = executedLevels.isNotEmpty()
    val throbTransition = rememberInfiniteTransition(label = "touchTurnExecutedOrderThrob")
    val throbAlpha by throbTransition.animateFloat(
        initialValue = if (showThrob) 0.2f else 1f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(animation = tween(550), repeatMode = RepeatMode.Reverse),
        label = "touchTurnExecutedOrderThrobAlpha"
    )
    val throbStrokeBoost by throbTransition.animateFloat(
        initialValue = if (showThrob) 0f else 1f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(animation = tween(550), repeatMode = RepeatMode.Reverse),
        label = "touchTurnExecutedOrderThrobStroke"
    )
    val entryColor = Color(0xFF42A5F5)
    val bodyColor = when (setup.candleColor) {
        FirstCandleColor.GREEN -> CandleGreen
        FirstCandleColor.RED -> CandleRed
        FirstCandleColor.DOJI -> TextSecondary
    }
    val prices = buildList {
        add(candle.high)
        add(candle.low)
        add(candle.open)
        add(candle.close)
        add(setup.entry)
        add(setup.stopLoss)
        add(setup.takeProfit)
    }
    val pad = candle.range * 0.15
    val priceTop = prices.max() + pad
    val priceBottom = prices.min() - pad
    val density = androidx.compose.ui.platform.LocalDensity.current

    val levels = buildList {
        add(TouchTurnPriceLevel(candle.high, "High", TextSecondary.copy(alpha = 0.55f), 1f))
        add(TouchTurnPriceLevel(candle.low, "Low", TextSecondary.copy(alpha = 0.55f), 1f))
        add(
            TouchTurnPriceLevel(
                setup.entry,
                "Entry (${TouchTurnLogic.tradeSideLabel(setup.side)})",
                entryColor,
                2.5f,
                TouchTurnOrderLevelKind.ENTRY
            )
        )
        add(
            TouchTurnPriceLevel(
                setup.takeProfit,
                "Take profit (${TouchTurnLogic.takeProfitFibLabel(setup.candleColor)})",
                GainGreen,
                2.5f,
                TouchTurnOrderLevelKind.TAKE_PROFIT
            )
        )
        add(
            TouchTurnPriceLevel(
                setup.stopLoss,
                "Stop loss",
                LossRed,
                2.5f,
                TouchTurnOrderLevelKind.STOP_LOSS
            )
        )
    }

    BoxWithConstraints(
        modifier = modifier
            .fillMaxWidth()
            .height(TouchTurnChartDimensions.orderPreviewHeight)
    ) {
        val chartHeight = maxHeight
        val labelColumnWidth = TouchTurnChartDimensions.orderLevelLabelColumnWidth
        val chartWidth = (maxWidth - labelColumnWidth).coerceAtLeast(80.dp)

        Canvas(
            modifier = Modifier
                .width(chartWidth)
                .fillMaxHeight()
        ) {
            val plot = TouchTurnChartDimensions.plotBounds(
                canvasWidth = size.width,
                canvasHeight = size.height,
                density = density,
                includeHorizontalPadding = false
            )
            val candleLeft = size.width * 0.34f
            val candleWidth = size.width * 0.28f
            val centerX = candleLeft + candleWidth / 2f
            fun y(price: Double): Float = plot.yForPrice(price, priceBottom, priceTop)

            drawRect(
                color = TableHeaderBg.copy(alpha = 0.4f),
                topLeft = Offset(candleLeft, y(candle.high)),
                size = Size(candleWidth, y(candle.low) - y(candle.high))
            )

            val wickStroke = 2.dp.toPx()
            drawLine(
                color = bodyColor,
                start = Offset(centerX, y(candle.high)),
                end = Offset(centerX, y(candle.low)),
                strokeWidth = wickStroke
            )
            val yOpen = y(candle.open)
            val yClose = y(candle.close)
            val top = minOf(yOpen, yClose)
            val bottom = maxOf(yOpen, yClose)
            val minBodyPx = 4.dp.toPx()
            drawRect(
                color = bodyColor,
                topLeft = Offset(centerX - candleWidth * 0.42f, top),
                size = Size(candleWidth * 0.84f, (bottom - top).coerceAtLeast(minBodyPx))
            )

            levels.forEach { level ->
                val yPos = y(level.price)
                val executed = level.kind != null && level.kind in executedLevels
                drawLine(
                    color = level.color.copy(alpha = if (executed) 0.45f else 0.55f),
                    start = Offset(0f, yPos),
                    end = Offset(size.width, yPos),
                    strokeWidth = level.strokeWidthDp.dp.toPx()
                )
            }
        }

        if (showThrob) {
            Canvas(
                modifier = Modifier
                    .width(chartWidth)
                    .fillMaxHeight()
                    .testTag("TouchTurnOrderPreviewChartThrob")
            ) {
                val plot = TouchTurnChartDimensions.plotBounds(
                    canvasWidth = size.width,
                    canvasHeight = size.height,
                    density = density,
                    includeHorizontalPadding = false
                )
                fun y(price: Double): Float = plot.yForPrice(price, priceBottom, priceTop)
                val baseLevels = levels.filter { it.kind != null && it.kind in executedLevels }
                baseLevels.forEach { level ->
                    val yPos = y(level.price)
                    val strokePx = level.strokeWidthDp.dp.toPx() * (1.6f + throbStrokeBoost * 1.4f)
                    val glowAlpha = throbAlpha * 0.35f
                    drawLine(
                        color = level.color.copy(alpha = glowAlpha),
                        start = Offset(0f, yPos),
                        end = Offset(size.width, yPos),
                        strokeWidth = strokePx * 2.2f
                    )
                    drawLine(
                        color = level.color.copy(alpha = throbAlpha),
                        start = Offset(0f, yPos),
                        end = Offset(size.width, yPos),
                        strokeWidth = strokePx
                    )
                }
            }
        }

        Box(modifier = Modifier.fillMaxSize()) {
            levels.sortedByDescending { it.price }.forEach { level ->
                val lineY = TouchTurnChartDimensions.yForPrice(level.price, priceBottom, priceTop, chartHeight)
                val yOffset = TouchTurnChartDimensions.levelLabelYOffset(lineY, chartHeight)
                val executed = level.kind != null && level.kind in executedLevels
                val labelColor = when {
                    executed -> level.color.copy(alpha = throbAlpha)
                    level.kind != null -> level.color.copy(alpha = 0.85f)
                    else -> level.color
                }
                Row(
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .offset(x = chartWidth, y = yOffset)
                        .width(labelColumnWidth)
                        .background(TableHeaderBg.copy(alpha = 0.95f), RoundedCornerShape(4.dp))
                        .padding(horizontal = 5.dp, vertical = 2.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        level.label,
                        fontSize = 8.sp,
                        color = labelColor,
                        fontWeight = if (executed) FontWeight.SemiBold else FontWeight.Normal,
                        maxLines = 1,
                        modifier = Modifier.weight(1f)
                    )
                    Text(fmt(level.price), fontSize = 9.sp, fontWeight = FontWeight.Medium, color = Color.White)
                }
            }
        }

        Row(
            modifier = Modifier
                .align(Alignment.BottomStart)
                .padding(top = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            TouchTurnLegendDot("Bar", bodyColor)
            TouchTurnLegendDot("Entry", entryColor)
            TouchTurnLegendDot("TP", GainGreen)
            TouchTurnLegendDot("SL", LossRed)
            val sideLabel = if (setup.side == TouchTurnTradeSide.SHORT) "Short" else "Long"
            Text(sideLabel, fontSize = 9.sp, color = TextSecondary)
            if (executedLevels.isNotEmpty()) {
                Text("· pulsing = filled", fontSize = 9.sp, color = TextSecondary.copy(alpha = 0.85f))
            }
        }
    }
}

@Composable
private fun TouchTurnLegendDot(label: String, color: Color) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        Box(
            modifier = Modifier
                .size(8.dp)
                .background(color, RoundedCornerShape(2.dp))
        )
        Text(label, fontSize = 9.sp, color = TextSecondary)
    }
}

@Composable
internal fun TouchTurnMetric(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
    compact: Boolean = false
) {
    Column(modifier = modifier) {
        Text(label, fontSize = if (compact) 9.sp else 10.sp, color = TextSecondary, maxLines = 1)
        Text(
            value,
            fontSize = if (compact) 11.sp else 13.sp,
            fontWeight = FontWeight.Medium,
            color = Color.White,
            maxLines = 1
        )
    }
}

@Composable
private fun TouchTurnLivePipelineDetailHost(
    selectedNodeId: TouchTurnPipelineNodeId?,
    pipelineGraph: TouchTurnPipelineGraph?,
    instance: StrategyDeployment,
    analysisSession: TouchTurnSessionContext?,
    recapRun: StrategySession?,
    recapRunId: String?,
    recapSessionStartUi: TouchTurnSessionStartUi?,
    sessionEnded: Boolean,
    liveExecution: LiveExecutionUiState?,
    liveBroker: LiveBrokerUiState?,
    liveSessionTrades: LiveSessionTradesUiState?,
    touchTurnLiveOrderChart: TouchTurnLiveOrderChartUiState?,
    touchTurnFormingBarPriceChart: TouchTurnLiveOrderChartUiState?,
    orderLifecycle: TouchTurnOrderLifecycleUi?,
    onAdjustStop: (String, String) -> Unit,
    onClosePosition: (String) -> Unit
) {
    val inActiveTrade = liveExecution?.state == ExecutionState.FILLED && liveExecution.showPanel
    val recapSessionTrades = remember(instance.id, instance.sessionHistory.size, recapRunId) {
        instance.touchTurnRecapSessionTrades(recapRunId)
    }
    val recapSessionPnl = remember(instance.id, instance.sessionHistory.size, recapSessionTrades, recapRunId) {
        instance.touchTurnRecapSessionPnl(recapRunId)
            ?: liveSessionTrades?.tradeDetail?.let { detail ->
                when {
                    detail.showNetAsPrimary -> detail.netPnL
                    else -> detail.realizedPnL
                }
            }
    }
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        TouchTurnPipelineDetailPanel(
            selectedNodeId = selectedNodeId,
            graph = pipelineGraph
        ) { nodeId ->
        when (nodeId) {
            TouchTurnPipelineNodeId.Readiness ->
                TouchTurnPipelineSectionStart(
                    instance = instance,
                    graph = pipelineGraph,
                    lastClosedRun = recapRun,
                    session = analysisSession,
                    startUi = recapSessionStartUi,
                )
            TouchTurnPipelineNodeId.Data ->
                TouchTurnPipelineSectionData(
                    session = analysisSession,
                    symbol = instance.symbol,
                    formingBarPriceChart = touchTurnFormingBarPriceChart
                )
            TouchTurnPipelineNodeId.Rules ->
                TouchTurnPipelineSectionRules(
                    session = analysisSession,
                    graph = pipelineGraph,
                    formingBarPriceChart = if (sessionEnded) null else touchTurnFormingBarPriceChart,
                    sessionEnded = sessionEnded,
                    requireLivePriceChecks = recapRun?.touchTurnRunRecord?.runContext?.brokerKind
                        ?.usesLiveIbMarketData == true
                )
            TouchTurnPipelineNodeId.FiveMinConfirmation ->
                TouchTurnPipelineSectionFiveMin(
                    session = analysisSession,
                    graph = pipelineGraph
                )
            TouchTurnPipelineNodeId.Orders -> {
                val lifecycle = orderLifecycle
                if (!sessionEnded) {
                    TouchTurnPipelineSectionLiveOrderPricing(
                        session = analysisSession,
                        liveOrderChart = touchTurnLiveOrderChart,
                        sessionCandleTestTag = "TouchTurnOrdersSessionCandlePriceChart"
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                }
                if (!sessionEnded && lifecycle?.showLiveOrdersPanel == true) {
                    liveBroker?.let { broker ->
                        LiveBrokerSection(broker = broker, showPosition = false, slimOrders = true)
                    } ?: Text(
                        "Broker orders unavailable.",
                        fontSize = 11.sp,
                        color = TextSecondary
                    )
                    lifecycle.statusMessage?.let { message ->
                        Text(
                            message,
                            fontSize = 11.sp,
                            color = GainGreen
                        )
                    }
                } else if (lifecycle?.showOrdersPreview != false) {
                    TouchTurnPipelineSectionOrdersPreview(
                        session = analysisSession,
                        sessionTrades = if (sessionEnded) {
                            recapSessionTrades
                        } else {
                            liveSessionTrades?.sessionTrades.orEmpty()
                        },
                        sessionPnl = if (sessionEnded) {
                            recapSessionPnl
                        } else {
                            liveSessionTrades?.sessionTrades?.sessionDisplayPnL()
                        }
                    )
                }
            }
            TouchTurnPipelineNodeId.Position -> {
                if (!sessionEnded) {
                    TouchTurnPipelineSectionLiveOrderPricing(
                        session = analysisSession,
                        liveOrderChart = touchTurnLiveOrderChart,
                        sessionCandleTestTag = "TouchTurnPositionSessionCandlePriceChart"
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                }
                val live = liveExecution
                if (!sessionEnded && live != null && live.showPanel) {
                    LiveExecutionPanel(
                        symbol = instance.symbol,
                        live = live,
                        broker = liveBroker,
                        tradeDetail = liveSessionTrades?.tradeDetail,
                        onAdjustStop = onAdjustStop,
                        onClosePosition = onClosePosition
                    )
                } else {
                    liveSessionTrades?.tradeDetail?.let { detail ->
                        SessionTradeDetailPanel(
                            detail = detail,
                            testTagPrefix = "LiveSessionTrade"
                        )
                    } ?: Text(
                        if (sessionEnded) "No trade data for this session." else "No open position yet.",
                        fontSize = 11.sp,
                        color = TextSecondary
                    )
                }
                if (!sessionEnded && inActiveTrade) {
                    liveSessionTrades?.tradeDetail?.let { detail ->
                        if (detail.fills.isNotEmpty()) {
                            SessionTradeFillsPanel(detail = detail, testTagPrefix = "LiveSessionTrade")
                        }
                    }
                }
            }
            TouchTurnPipelineNodeId.Close ->
                if (sessionEnded) {
                    TouchTurnPipelineSectionClose(closedRun = recapRun, graph = pipelineGraph)
                } else {
                    TouchTurnSessionAutoStopStatus(instance = instance)
                }
        }
        }
    }
}

@Composable
private fun TouchTurnSessionAutoStopStatus(instance: StrategyDeployment) {
    val secondTick = LocalUiSecondTick.current
    val autoStop = remember(instance, secondTick) { TouchTurnScreenLabels.autoStopStatus(instance) } ?: return
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("SessionAutoStopStatus")
    ) {
        Text(
            "Touch Turn session auto-stop",
            fontSize = 11.sp,
            fontWeight = FontWeight.Medium,
            color = TextSecondary
        )
        Text(
            "Stops when a trade closes (win or loss), or ${autoStop.stopAfterMinOpen}m after RTH open " +
                "(then cancels working orders and closes any open position).",
            fontSize = 10.sp,
            color = TextSecondary.copy(alpha = 0.85f)
        )
        if (autoStop.pastDeadline || autoStop.remainingLabel != null) {
            Text(
                if (autoStop.pastDeadline) {
                    TouchTurnScreenLabels.pastDeadlineLabel(autoStop.stopAfterMinOpen)
                } else {
                    autoStop.remainingLabel.orEmpty()
                },
                fontSize = 11.sp,
                color = if (autoStop.pastDeadline) Color(0xFFFFB74D) else TextSecondary,
                modifier = Modifier.testTag("SessionAutoStopLabel")
            )
        }
    }
}

@Composable
private fun LiveTab(
    instance: StrategyDeployment,
    liveExecution: LiveExecutionUiState?,
    liveBroker: LiveBrokerUiState?,
    liveSessionTrades: LiveSessionTradesUiState?,
    touchTurnLiveOrderChart: TouchTurnLiveOrderChartUiState?,
    touchTurnFormingBarPriceChart: TouchTurnLiveOrderChartUiState?,
    touchTurnPipelineGraph: TouchTurnPipelineGraph?,
    touchTurnOrderLifecycle: TouchTurnOrderLifecycleUi?,
    showSessionRecap: Boolean,
    tradingPanelRecapRunId: String?,
    onResetTradingPanel: () -> Unit,
    onAdjustStop: (String, String) -> Unit,
    onClosePosition: (String) -> Unit
) {
    val inActiveTrade = liveExecution?.state == ExecutionState.FILLED && liveExecution.showPanel
    val isTouchTurn = instance.isTouchTurn
    val isRunning = instance.status == DeploymentStatus.RUNNING
    val sessionEnded = isTouchTurn && !isRunning && showSessionRecap
    val touchTurnInstance = instance.takeIf { isTouchTurn }
    val recapRun = remember(
        instance.id,
        instance.sessionHistory.size,
        showSessionRecap,
        tradingPanelRecapRunId,
    ) {
        if (showSessionRecap) instance.touchTurnRecapRun(tradingPanelRecapRunId) else null
    }
    val recapSessionStartUi = remember(recapRun, instance.id) {
        recapRun?.touchTurnRunRecord?.runContext?.let { runContext ->
            TouchTurnSessionStartUiMapper.forHistory(
                instance = instance,
                run = recapRun,
                runContext = runContext,
            )
        }
    }
    val analysisSession = remember(
        instance.id,
        instance.touchTurnSession,
        instance.sessionHistory.size,
        showSessionRecap,
        tradingPanelRecapRunId,
    ) {
        when {
            isRunning -> instance.touchTurnAnalysisSessionForRun(null)
            showSessionRecap -> instance.touchTurnAnalysisSessionForRun(recapRun)
            else -> null
        }
    }
    val orderLifecycle = touchTurnOrderLifecycle
    var selectedPipelineNode by rememberSaveable(instance.id, showSessionRecap, tradingPanelRecapRunId) {
        mutableStateOf<TouchTurnPipelineNodeId?>(null)
    }
    var lastTrackedCurrentNode by remember(instance.id, showSessionRecap, tradingPanelRecapRunId) {
        mutableStateOf<TouchTurnPipelineNodeId?>(null)
    }
    val pipelineLiveSessionTrades = if (isRunning || showSessionRecap) liveSessionTrades else null
    LaunchedEffect(touchTurnPipelineGraph) {
        val graph = touchTurnPipelineGraph ?: return@LaunchedEffect
        val currentActive = graph.currentNodeId()
        val selected = selectedPipelineNode?.let { graph.node(it) }
        when {
            selectedPipelineNode == null || selected == null || !selected.isSelectable() -> {
                selectedPipelineNode = graph.defaultSelectedNode()
                lastTrackedCurrentNode = currentActive
            }
            currentActive != lastTrackedCurrentNode -> {
                selectedPipelineNode = currentActive ?: graph.defaultSelectedNode()
                lastTrackedCurrentNode = currentActive
            }
        }
    }
    Column(
        modifier = Modifier.fillMaxWidth().testTag("LiveTab"),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        val activeSessionId = when {
            isRunning -> instance.inProgressSession()?.id
            showSessionRecap -> recapRun?.id
            else -> null
        }
        activeSessionId?.let { sessionId ->
            SessionLogReference(
                deploymentId = instance.id,
                sessionId = sessionId,
                modifier = Modifier.padding(bottom = 2.dp)
            )
        }
        if (isTouchTurn && !isRunning && showSessionRecap) {
            val recapTimeLabel = recapRun?.let { run ->
                daytrader.presentation.Formatters.runSessionTimeDisplay(
                    startedAt = run.startedAt,
                    stoppedAt = run.stoppedAt,
                    inProgress = false,
                )
            }?.takeIf { it != "—" }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    recapTimeLabel?.let { "Showing session · $it" } ?: "Showing session recap",
                    fontSize = 12.sp,
                    color = TextSecondary,
                    modifier = Modifier.testTag("TradingPanelRecapSessionLabel"),
                )
                TextButton(
                    onClick = onResetTradingPanel,
                    modifier = Modifier.testTag("TradingPanelResetButton"),
                ) {
                    Icon(
                        Icons.Default.Refresh,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp),
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("Reset panel")
                }
            }
        }
        if (isTouchTurn && !isRunning && !showSessionRecap) {
            Text(
                "Ready for the next session. Pick a past run on Session history to review it here.",
                fontSize = 13.sp,
                color = TextSecondary,
                modifier = Modifier.testTag("TradingPanelIdleHint"),
            )
        }
        if (isTouchTurn && touchTurnPipelineGraph != null) {
            val headerStyle = when {
                sessionEnded || inActiveTrade -> LiveTradingHeaderStyle.BreadcrumbOnly
                else -> LiveTradingHeaderStyle.Watching
            }
            if (headerStyle != LiveTradingHeaderStyle.Watching || !inActiveTrade) {
                LiveTradingPositionPnLHeader(
                    symbol = instance.symbol,
                    broker = liveBroker,
                    liveExecution = liveExecution,
                    touchTurnInstance = touchTurnInstance,
                    style = headerStyle,
                    pipelineGraphOverride = touchTurnPipelineGraph,
                    selectedPipelineNodeId = selectedPipelineNode,
                    onPipelineNodeSelected = { selectedPipelineNode = it }
                )
            }
            TouchTurnLivePipelineDetailHost(
                selectedNodeId = selectedPipelineNode,
                pipelineGraph = touchTurnPipelineGraph,
                instance = instance,
                analysisSession = analysisSession,
                recapRun = recapRun,
                recapRunId = tradingPanelRecapRunId,
                recapSessionStartUi = recapSessionStartUi,
                sessionEnded = sessionEnded,
                liveExecution = liveExecution,
                liveBroker = liveBroker,
                liveSessionTrades = pipelineLiveSessionTrades,
                touchTurnLiveOrderChart = touchTurnLiveOrderChart,
                touchTurnFormingBarPriceChart = touchTurnFormingBarPriceChart,
                orderLifecycle = orderLifecycle,
                onAdjustStop = onAdjustStop,
                onClosePosition = onClosePosition
            )
        } else if (isRunning) {
            val headerStyle = LiveTradingHeaderStyle.Watching
            if (headerStyle != LiveTradingHeaderStyle.Watching || !inActiveTrade) {
                LiveTradingPositionPnLHeader(
                    symbol = instance.symbol,
                    broker = liveBroker,
                    liveExecution = liveExecution,
                    touchTurnInstance = touchTurnInstance,
                    style = headerStyle,
                    pipelineGraphOverride = touchTurnPipelineGraph,
                    selectedPipelineNodeId = selectedPipelineNode,
                    onPipelineNodeSelected = { selectedPipelineNode = it }
                )
            }
            when {
                liveExecution == null -> {
                    Text("No live data.", color = TextSecondary, fontSize = 13.sp)
                }
                liveExecution.showPanel -> {
                    LiveExecutionPanel(
                        symbol = instance.symbol,
                        live = liveExecution,
                        broker = liveBroker,
                        tradeDetail = liveSessionTrades?.tradeDetail,
                        onAdjustStop = onAdjustStop,
                        onClosePosition = onClosePosition
                    )
                }
                !liveExecution.isRunning -> {
                    Text("Session stopped.", color = TextSecondary, fontSize = 13.sp)
                }
                else -> {
                    Text("Flat — awaiting setup.", color = TextSecondary, fontSize = 13.sp)
                }
            }
            liveSessionTrades?.let { trades ->
                val showFills = !inActiveTrade || trades.tradeDetail.fills.isNotEmpty()
                if (showFills) {
                    LiveSessionTradesSection(
                        trades = trades,
                        inProgress = true,
                        fillsOnly = inActiveTrade
                    )
                }
            }
            liveBroker?.let { broker ->
                LiveBrokerSection(
                    broker = broker,
                    showPosition = !inActiveTrade,
                    slimOrders = inActiveTrade
                )
            }
        } else {
            if (liveSessionTrades != null) {
                LiveSessionTradesSection(
                    liveSessionTrades,
                    inProgress = false,
                    fillsOnly = false
                )
            } else {
                Text(
                    "Start a session to view broker data. After a session ends, fills appear here for P&L verification.",
                    color = TextSecondary,
                    fontSize = 13.sp,
                    modifier = Modifier.testTag("LiveTabStoppedHint")
                )
            }
            liveBroker?.let { broker ->
                LiveBrokerSection(
                    broker = broker,
                    showPosition = true,
                    slimOrders = false
                )
            }
        }
    }
}

private enum class LiveTradingHeaderStyle {
    /** Touch Turn pipeline only while the active-position card shows P&L. */
    BreadcrumbOnly,
    /** Pre-trade / flat: breadcrumb + status P&L when not in an active trade card. */
    Watching,
    /** Last closed session recap. */
    SessionEnded
}

private data class LivePositionPnLDisplay(
    val pnlText: String,
    val isPositive: Boolean,
    val subtitle: String,
    val hasOpenPosition: Boolean
)

private fun resolveLivePositionPnL(
    symbol: String,
    broker: LiveBrokerUiState?,
    liveExecution: LiveExecutionUiState?
): LivePositionPnLDisplay {
    broker?.position?.let { position ->
        return LivePositionPnLDisplay(
            pnlText = position.formattedUnrealizedPnL,
            isPositive = position.isPositivePnL,
            subtitle = "${position.sideLabel} ${position.quantity} · ${position.symbol}",
            hasOpenPosition = true
        )
    }
    if (liveExecution?.state == ExecutionState.FILLED && liveExecution.formattedUnrealized != null) {
        return LivePositionPnLDisplay(
            pnlText = liveExecution.formattedUnrealized,
            isPositive = liveExecution.isUnrealizedPositive,
            subtitle = liveExecution.headline,
            hasOpenPosition = true
        )
    }
    val subtitle = when {
        broker?.isConnected == false -> "Broker not connected"
        broker != null -> "Flat · no open position for $symbol"
        else -> "Flat · awaiting broker position"
    }
    return LivePositionPnLDisplay(
        pnlText = "—",
        isPositive = true,
        subtitle = subtitle,
        hasOpenPosition = false
    )
}

@Composable
private fun LiveTradingPositionPnLHeader(
    symbol: String,
    broker: LiveBrokerUiState?,
    liveExecution: LiveExecutionUiState?,
    touchTurnInstance: StrategyDeployment? = null,
    pipelineGraphOverride: TouchTurnPipelineGraph? = null,
    style: LiveTradingHeaderStyle = LiveTradingHeaderStyle.Watching,
    selectedPipelineNodeId: TouchTurnPipelineNodeId? = null,
    onPipelineNodeSelected: ((TouchTurnPipelineNodeId) -> Unit)? = null
) {
    val display = resolveLivePositionPnL(symbol, broker, liveExecution)
    val pnlColor = if (display.hasOpenPosition) {
        if (display.isPositive) GainGreen else LossRed
    } else {
        TextSecondary
    }
    val headerBg = when (style) {
        LiveTradingHeaderStyle.BreadcrumbOnly -> SurfaceDark
        else -> if (display.hasOpenPosition) {
            pnlColor.copy(alpha = 0.14f)
        } else {
            SurfaceDark
        }
    }
    val borderColor = when (style) {
        LiveTradingHeaderStyle.BreadcrumbOnly -> TableHeaderBg
        else -> if (display.hasOpenPosition) pnlColor.copy(alpha = 0.45f) else TableHeaderBg
    }
    val secondTick = LocalUiSecondTick.current
    val hasOpenOrders = broker?.openOrders?.isNotEmpty() == true
    val pipelineGraph = pipelineGraphOverride ?: remember(
        touchTurnInstance,
        display.hasOpenPosition,
        hasOpenOrders,
        secondTick
    ) {
        touchTurnInstance?.let { instance ->
            TouchTurnStatusBreadcrumbMapper.graph(
                instance = instance,
                hasOpenPosition = display.hasOpenPosition,
                hasOpenOrders = hasOpenOrders
            )
        }
    }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(headerBg, RoundedCornerShape(8.dp))
            .border(
                width = 1.dp,
                color = borderColor,
                shape = RoundedCornerShape(8.dp)
            )
            .padding(horizontal = 10.dp, vertical = 6.dp)
            .testTag("LiveTradingPositionPnLHeader"),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        if (pipelineGraph != null) {
            TouchTurnPipelineGraphView(
                graph = pipelineGraph,
                compact = true,
                showTitle = false,
                selectedNodeId = selectedPipelineNodeId,
                onNodeSelected = onPipelineNodeSelected,
                modifier = Modifier.testTag("TouchTurnStatusBreadcrumb")
            )
        }
        if (style != LiveTradingHeaderStyle.BreadcrumbOnly) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Text(
                        if (style == LiveTradingHeaderStyle.SessionEnded) "Session ended" else "Position P&L",
                        fontSize = 11.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = BrandRed,
                        letterSpacing = 0.3.sp
                    )
                    Text(
                        if (style == LiveTradingHeaderStyle.SessionEnded) {
                            "Pipeline log for last run · $symbol"
                        } else {
                            display.subtitle
                        },
                        fontSize = 11.sp,
                        color = TextSecondary,
                        maxLines = 2
                    )
                }
                Text(
                    display.pnlText,
                    fontSize = 26.sp,
                    fontWeight = FontWeight.Bold,
                    color = pnlColor,
                    modifier = Modifier.testTag("LiveTradingPositionPnLValue")
                )
            }
        }
    }
}

@Composable
private fun LiveSessionTradesSection(
    trades: LiveSessionTradesUiState,
    inProgress: Boolean,
    fillsOnly: Boolean
) {
    val title = when {
        fillsOnly -> "Fills"
        inProgress -> "Session trade (${trades.symbol})"
        else -> "Last session trade (${trades.symbol})"
    }
    TouchTurnPanelGroup(
        title = title,
        testTag = "LiveSessionTradesSection",
        compact = true
    ) {
        if (!fillsOnly) {
            trades.runLabel?.let { label ->
                Text(
                    if (inProgress) "Session open" else "Last session · $label",
                    fontSize = 10.sp,
                    color = TextSecondary,
                    modifier = Modifier.testTag("LiveSessionTradesRunLabel")
                )
            }
            SessionTradeDetailPanel(
                detail = trades.tradeDetail,
                testTagPrefix = "LiveSessionTrade"
            )
        } else if (trades.tradeDetail.fills.isNotEmpty()) {
            SessionTradeFillsPanel(
                detail = trades.tradeDetail,
                testTagPrefix = "LiveSessionTrade"
            )
        }
    }
}

@Composable
private fun LiveBrokerSection(
    broker: LiveBrokerUiState,
    showPosition: Boolean = true,
    slimOrders: Boolean = false
) {
    val ordersTitle = if (slimOrders) "Working bracket" else "Open orders (${broker.symbol})"
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("LiveBrokerSection"),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        broker.statusMessage?.let { message ->
            Text(message, fontSize = 11.sp, color = TextSecondary)
        }

        if (showPosition) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.Top
            ) {
                TouchTurnPanelGroup(
                    title = "Broker position (${broker.symbol})",
                    testTag = "LiveBrokerPositionGroup",
                    compact = true,
                    modifier = Modifier.weight(1f)
                ) {
                    LiveBrokerPositionContent(broker = broker)
                }
                TouchTurnPanelGroup(
                    title = ordersTitle,
                    testTag = "LiveBrokerOrdersGroup",
                    compact = true,
                    modifier = Modifier.weight(1f)
                ) {
                    LiveBrokerOrdersContent(broker = broker, slimOrders = slimOrders)
                }
            }
        } else {
            TouchTurnPanelGroup(
                title = ordersTitle,
                testTag = "LiveBrokerOrdersGroup",
                compact = true
            ) {
                LiveBrokerOrdersContent(broker = broker, slimOrders = slimOrders)
            }
        }
    }
}

@Composable
private fun LiveBrokerPositionContent(broker: LiveBrokerUiState) {
    val position = broker.position
    if (position == null) {
        Text(
            if (broker.isConnected) "No open position for this symbol." else "Position unavailable.",
            fontSize = 11.sp,
            color = TextSecondary,
            modifier = Modifier.testTag("LiveBrokerNoPosition")
        )
    } else {
        Column(modifier = Modifier.fillMaxWidth()) {
            Text(
                position.companyName,
                fontSize = 11.sp,
                fontWeight = FontWeight.SemiBold,
                color = Color.White,
                maxLines = 1
            )
            Text(
                "${position.sideLabel} ${position.quantity} · ${position.symbol}",
                fontSize = 10.sp,
                color = TextSecondary,
                modifier = Modifier.testTag("LiveBrokerPositionSummary")
            )
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            TouchTurnMetric("Avg", position.formattedAvgPrice, Modifier.weight(1f), compact = true)
            TouchTurnMetric("Mkt", position.formattedMarketPrice, Modifier.weight(1f), compact = true)
            position.formattedDailyChange?.let { change ->
                TouchTurnMetric("Day", change, Modifier.weight(1f), compact = true)
            }
        }
    }
}

@Composable
private fun LiveBrokerOrdersContent(
    broker: LiveBrokerUiState,
    slimOrders: Boolean
) {
    if (broker.openOrders.isEmpty()) {
        Text(
            if (broker.isConnected) "No open orders for this symbol." else "Orders unavailable.",
            fontSize = 11.sp,
            color = TextSecondary,
            modifier = Modifier.testTag("LiveBrokerNoOrders")
        )
    } else {
        broker.openOrders.forEachIndexed { index, order ->
            if (index > 0) {
                HorizontalDivider(color = TableHeaderBg.copy(alpha = 0.6f))
            }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("LiveBrokerOrder_${order.orderId}"),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    if (slimOrders) {
                        Text(
                            "#${order.orderId} · ${order.action}",
                            fontSize = 11.sp,
                            color = Color.White,
                            maxLines = 1
                        )
                    } else {
                        Text(order.summary, fontSize = 11.sp, color = Color.White, maxLines = 2)
                    }
                    val orderMeta = buildString {
                        if (!slimOrders) {
                            append("#")
                            append(order.orderId)
                        }
                        if (order.permId > 0L) {
                            if (isNotEmpty()) append(" · ")
                            append("perm ")
                            append(order.permId)
                        }
                    }
                    if (orderMeta.isNotEmpty()) {
                        Text(orderMeta, fontSize = 9.sp, color = TextSecondary)
                    }
                }
                Text(
                    order.status,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Medium,
                    color = Color(0xFFFFB74D)
                )
            }
        }
    }
}

@Composable
private fun LiveExecutionPanel(
    symbol: String,
    live: LiveExecutionUiState,
    broker: LiveBrokerUiState?,
    tradeDetail: SessionTradeDetailUiState?,
    onAdjustStop: (String, String) -> Unit,
    onClosePosition: (String) -> Unit
) {
    val panelTitle = when (live.state) {
        ExecutionState.FILLED -> "Active position"
        else -> "Active trade"
    }
    TouchTurnPanelGroup(
        title = panelTitle,
        testTag = "LiveTradePanel",
        compact = true
    ) {
        when (live.state) {
            ExecutionState.FILLED -> {
                val display = resolveLivePositionPnL(symbol, broker, live)
                val pnlColor = if (display.isPositive) GainGreen else LossRed
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.Top
                ) {
                    Row(
                        modifier = Modifier.weight(1f),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        tradeDetail?.let { detail ->
                            TradeSideBadge(
                                label = detail.sideLabel,
                                isLong = detail.isLong,
                                modifier = Modifier.testTag("LiveSessionTradeSide")
                            )
                        }
                        Text(
                            live.headline,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = Color.White,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.testTag("LiveTradeHeadline")
                        )
                    }
                    Column(horizontalAlignment = Alignment.End) {
                        Text("Unrealized", fontSize = 9.sp, color = TextSecondary)
                        Text(
                            display.pnlText,
                            fontSize = 26.sp,
                            fontWeight = FontWeight.Bold,
                            color = pnlColor,
                            modifier = Modifier.testTag("LiveTradingPositionPnLValue")
                        )
                    }
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    TradeMetricCell("Entry", live.entryPrice ?: "—", Modifier.weight(1f), testTag = "LiveTradeEntry")
                    if (live.canManagePosition) {
                        Column(modifier = Modifier.weight(1f)) {
                            LiveStopEditor(
                                stopPriceInput = live.stopPriceInput,
                                onApply = { stopText -> onAdjustStop(live.instanceId, stopText) },
                                inline = true
                            )
                        }
                    } else {
                        TradeMetricCell("Stop", live.stopPrice ?: "—", Modifier.weight(1f), testTag = "LiveTradeStop")
                    }
                    TradeMetricCell(
                        "Target",
                        live.targetPrice ?: "—",
                        Modifier.weight(1f),
                        valueColor = GainGreen,
                        testTag = "LiveTradeTarget"
                    )
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    live.formattedRisk?.let { risk ->
                        TradePnLChip(
                            label = "Risk at stop",
                            value = risk,
                            positive = false,
                            modifier = Modifier.weight(1f)
                        )
                    }
                    live.formattedUpside?.let { upside ->
                        TradePnLChip(
                            label = "Upside",
                            value = upside,
                            positive = true,
                            modifier = Modifier.weight(1f)
                        )
                    }
                }
                live.riskPercentOfMax?.let { pct ->
                    Text(pct, fontSize = 10.sp, color = TextSecondary)
                }
                if (live.canManagePosition) {
                    ClosePositionButton(
                        live = live,
                        onClosePosition = { onClosePosition(live.instanceId) }
                    )
                }
            }
            ExecutionState.WORKING -> {
                Text(
                    live.headline,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = Color.White,
                    modifier = Modifier.testTag("LiveTradeHeadline")
                )
                Text(
                    "Order working — bracket levels appear after fill.",
                    fontSize = 11.sp,
                    color = TextSecondary
                )
            }
            ExecutionState.FLAT -> {
                Text(
                    live.headline,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = Color.White,
                    modifier = Modifier.testTag("LiveTradeHeadline")
                )
                Text("Watching for next signal.", fontSize = 11.sp, color = TextSecondary)
            }
        }
    }
}

@Composable
private fun LiveStopEditor(
    stopPriceInput: String,
    onApply: (String) -> Unit,
    inline: Boolean = false
) {
    var stopText by remember(stopPriceInput) { mutableStateOf(stopPriceInput) }
    val isValid = stopText.toDoubleOrNull() != null

    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        if (!inline) {
            Text("Stop", fontSize = 11.sp, color = TextSecondary, fontWeight = FontWeight.Medium)
        } else {
            Text("Stop", fontSize = 9.sp, color = TextSecondary)
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            OutlinedTextField(
                value = stopText,
                onValueChange = { stopText = it },
                singleLine = true,
                modifier = Modifier.weight(1f).testTag("StopPriceField"),
                textStyle = androidx.compose.ui.text.TextStyle(
                    fontSize = if (inline) 12.sp else 14.sp,
                    color = Color.White
                ),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedContainerColor = TableHeaderBg,
                    unfocusedContainerColor = TableHeaderBg,
                    focusedBorderColor = BrandRed,
                    unfocusedBorderColor = TableHeaderBg,
                    focusedTextColor = Color.White,
                    unfocusedTextColor = Color.White
                ),
                shape = RoundedCornerShape(6.dp)
            )
            Button(
                onClick = { onApply(stopText) },
                enabled = isValid,
                colors = ButtonDefaults.buttonColors(containerColor = BrandRed),
                shape = RoundedCornerShape(6.dp),
                modifier = Modifier.testTag("ApplyStopButton"),
                contentPadding = PaddingValues(horizontal = 10.dp, vertical = if (inline) 6.dp else 8.dp)
            ) {
                Text("Apply", fontSize = if (inline) 11.sp else 13.sp)
            }
        }
    }
}

@Composable
private fun ClosePositionButton(
    live: LiveExecutionUiState,
    onClosePosition: () -> Unit
) {
    var showConfirm by remember { mutableStateOf(false) }

    Button(
        onClick = { showConfirm = true },
        modifier = Modifier.fillMaxWidth().testTag("ClosePositionButton"),
        colors = ButtonDefaults.buttonColors(containerColor = BrandRed),
        shape = RoundedCornerShape(6.dp)
    ) {
        Text("Exit position", fontWeight = FontWeight.SemiBold)
    }

    if (showConfirm) {
        val pnlHint = live.formattedUnrealized?.let { "Estimated P&L: $it." } ?: ""
        AlertDialog(
            onDismissRequest = { showConfirm = false },
            containerColor = SurfaceDark,
            title = {
                Text("Exit position?", color = Color.White, fontWeight = FontWeight.Bold)
            },
            text = {
                Text(
                    "Exit this position at market? $pnlHint",
                    color = TextSecondary,
                    fontSize = 14.sp
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        showConfirm = false
                        onClosePosition()
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = BrandRed)
                ) {
                    Text("Exit at market")
                }
            },
            dismissButton = {
                TextButton(onClick = { showConfirm = false }) {
                    Text("Cancel", color = TextSecondary)
                }
            }
        )
    }
}

@Composable
private fun DeploymentsListHeader(
    closedSessionHistoryCount: Int,
    hasInProgressSessions: Boolean,
    onDeleteAllSessionHistory: () -> Unit
) {
    var showDeleteAllConfirm by remember { mutableStateOf(false) }

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.End,
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (closedSessionHistoryCount > 0) {
            TextButton(
                onClick = { showDeleteAllConfirm = true },
                modifier = Modifier.testTag("AllDeploymentsClearHistoryButton")
            ) {
                Icon(
                    imageVector = Icons.Default.Delete,
                    contentDescription = null,
                    tint = TextSecondary,
                    modifier = Modifier.size(16.dp)
                )
                Spacer(modifier = Modifier.width(4.dp))
                Text("Clear all history", color = TextSecondary, fontSize = 12.sp)
            }
        }
    }

    if (showDeleteAllConfirm) {
        val sessionLabel = if (closedSessionHistoryCount == 1) "session" else "sessions"
        AlertDialog(
            onDismissRequest = { showDeleteAllConfirm = false },
            containerColor = SurfaceDark,
            title = {
                Text("Clear all session history?", color = Color.White, fontWeight = FontWeight.Bold)
            },
            text = {
                Column {
                    Text(
                        "Remove all $closedSessionHistoryCount closed $sessionLabel from every deployment? This cannot be undone.",
                        color = TextSecondary,
                        fontSize = 14.sp
                    )
                    if (hasInProgressSessions) {
                        Text(
                            "In-progress sessions will be kept.",
                            color = TextSecondary,
                            fontSize = 13.sp,
                            modifier = Modifier.padding(top = 8.dp)
                        )
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        showDeleteAllConfirm = false
                        onDeleteAllSessionHistory()
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = BrandRed)
                ) {
                    Text("Clear all history")
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteAllConfirm = false }) {
                    Text("Cancel", color = TextSecondary)
                }
            }
        )
    }
}

@Composable
private fun PerformanceTab(
    sessionHistory: SessionHistoryUiState?,
    onSessionHistoryHeaderClick: (SessionHistorySortColumn) -> Unit,
    onSelectRun: (runId: String) -> Unit,
    onDeleteRun: (runId: String) -> Unit,
    onDeleteAllRuns: () -> Unit,
    modifier: Modifier = Modifier,
) {
    if (sessionHistory == null) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text("No session history.", color = TextSecondary, fontSize = 13.sp)
        }
        return
    }

    if (sessionHistory.rows.isEmpty()) {
        val message = sessionHistory.marketFilterLabel?.let { label ->
            "No sessions for $label in this deployment."
        } ?: "No session history."
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(message, color = TextSecondary, fontSize = 13.sp)
        }
        return
    }

    var showDeleteAllConfirm by remember { mutableStateOf(false) }
    val deletableCount = sessionHistory.rows.count { it.canDelete }
    val hasInProgress = sessionHistory.rows.any { it.isInProgress }

    Column(
        modifier = modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            SessionHistorySummaryBar(
                rollup30d = sessionHistory.rollup30d,
                winRate = sessionHistory.winRate,
                noTradeRate = sessionHistory.noTradeRate,
                modifier = Modifier.weight(1f)
            )
            if (deletableCount > 0) {
                TextButton(
                    onClick = { showDeleteAllConfirm = true },
                    modifier = Modifier.testTag("SessionHistoryDeleteAllButton")
                ) {
                    Icon(
                        imageVector = Icons.Default.Delete,
                        contentDescription = null,
                        tint = TextSecondary,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("Clear history", color = TextSecondary, fontSize = 12.sp)
                }
            }
        }

        SessionHistoryBlotterTable(
            sessionHistory = sessionHistory,
            onHeaderClick = onSessionHistoryHeaderClick,
            onSelectRun = onSelectRun,
            onDeleteRun = onDeleteRun,
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
        )
    }

    if (showDeleteAllConfirm) {
        val sessionLabel = if (deletableCount == 1) "session" else "sessions"
        AlertDialog(
            onDismissRequest = { showDeleteAllConfirm = false },
            containerColor = SurfaceDark,
            title = {
                Text("Clear session history?", color = Color.White, fontWeight = FontWeight.Bold)
            },
            text = {
                Column {
                    Text(
                        "Remove all $deletableCount closed $sessionLabel from this deployment? This cannot be undone.",
                        color = TextSecondary,
                        fontSize = 14.sp
                    )
                    if (hasInProgress) {
                        Text(
                            "The in-progress session will be kept.",
                            color = TextSecondary,
                            fontSize = 13.sp,
                            modifier = Modifier.padding(top = 8.dp)
                        )
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        showDeleteAllConfirm = false
                        onDeleteAllRuns()
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = BrandRed)
                ) {
                    Text("Clear history")
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteAllConfirm = false }) {
                    Text("Cancel", color = TextSecondary)
                }
            }
        )
    }
}

@Composable
private fun SessionHistorySummaryBar(
    rollup30d: String,
    winRate: String,
    noTradeRate: String,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(DarkBackground, RoundedCornerShape(8.dp))
            .padding(horizontal = 14.dp, vertical = 10.dp)
            .testTag("SessionHistorySummaryBar"),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("30D", fontSize = 10.sp, color = TextSecondary)
            Spacer(modifier = Modifier.width(6.dp))
            Text(rollup30d, fontSize = 15.sp, fontWeight = FontWeight.Bold, color = Color.White)
        }
        Row(
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Win", fontSize = 10.sp, color = TextSecondary)
                Spacer(modifier = Modifier.width(6.dp))
                Text(winRate, fontSize = 15.sp, fontWeight = FontWeight.Bold, color = Color.White)
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("No trade", fontSize = 10.sp, color = TextSecondary)
                Spacer(modifier = Modifier.width(6.dp))
                Text(noTradeRate, fontSize = 15.sp, fontWeight = FontWeight.Bold, color = Color.White)
            }
        }
    }
}

@Composable
private fun PerformanceStatCard(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
    valueColor: Color = Color.White
) {
    Column(
        modifier = modifier
            .background(DarkBackground, RoundedCornerShape(8.dp))
            .padding(16.dp)
    ) {
        Text(label, fontSize = 11.sp, color = TextSecondary)
        Spacer(modifier = Modifier.height(8.dp))
        Text(value, fontSize = 20.sp, fontWeight = FontWeight.Bold, color = valueColor)
    }
}

@Composable
internal fun ConfigField(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true
) {
    Column(modifier = modifier) {
        Text(label, fontSize = 11.sp, color = TextSecondary, fontWeight = FontWeight.Medium)
        Spacer(modifier = Modifier.height(4.dp))
        OutlinedTextField(
            value = value,
            onValueChange = onValueChange,
            enabled = enabled,
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
            colors = OutlinedTextFieldDefaults.colors(
                focusedContainerColor = DarkBackground,
                unfocusedContainerColor = DarkBackground,
                disabledContainerColor = DarkBackground,
                focusedBorderColor = TableHeaderBg,
                unfocusedBorderColor = TableHeaderBg,
                disabledBorderColor = TableHeaderBg,
                focusedTextColor = Color.White,
                unfocusedTextColor = Color.White,
                disabledTextColor = TextSecondary
            ),
            shape = RoundedCornerShape(6.dp)
        )
    }
}

@Composable
private fun ConfigDropdown(
    label: String,
    value: String,
    options: List<String>,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    var expanded by remember { mutableStateOf(false) }
    Column(modifier = modifier) {
        Text(label, fontSize = 11.sp, color = TextSecondary, fontWeight = FontWeight.Medium)
        Spacer(modifier = Modifier.height(4.dp))
        Box {
            OutlinedTextField(
                value = value,
                onValueChange = {},
                readOnly = true,
                singleLine = true,
                modifier = Modifier.fillMaxWidth().clickable { expanded = true },
                trailingIcon = {
                    Icon(Icons.Default.ArrowDropDown, contentDescription = null, tint = TextSecondary)
                },
                colors = OutlinedTextFieldDefaults.colors(
                    focusedContainerColor = DarkBackground,
                    unfocusedContainerColor = DarkBackground,
                    focusedBorderColor = TableHeaderBg,
                    unfocusedBorderColor = TableHeaderBg,
                    focusedTextColor = Color.White,
                    unfocusedTextColor = Color.White
                ),
                shape = RoundedCornerShape(6.dp)
            )
            DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                options.forEach { option ->
                    DropdownMenuItem(
                        text = { Text(option) },
                        onClick = {
                            onValueChange(option)
                            expanded = false
                        }
                    )
                }
            }
        }
    }
}

@Composable
internal fun AutoStartOnMarketOpenField(
    checked: Boolean,
    enabled: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("AutoStartOnMarketOpenField"),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Checkbox(
            checked = checked,
            onCheckedChange = onCheckedChange,
            enabled = enabled,
            colors = CheckboxDefaults.colors(
                checkedColor = BrandRed,
                checkmarkColor = Color.White
            ),
            modifier = Modifier.testTag("AutoStartOnMarketOpenCheckbox")
        )
        Column(modifier = Modifier.weight(1f)) {
            Text(
                "Auto-start when market opens",
                fontSize = 13.sp,
                color = if (enabled) Color.White else TextSecondary,
                fontWeight = FontWeight.Medium
            )
            Text(
                "Starts this deployment at RTH open in the deployment's market session.",
                fontSize = 11.sp,
                color = TextSecondary,
                lineHeight = 14.sp
            )
        }
    }
}

