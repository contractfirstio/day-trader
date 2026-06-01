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
import kotlinx.coroutines.delay
import daytrader.data.StrategyCatalog
import daytrader.domain.ExecutionState
import daytrader.domain.DeploymentStatus
import daytrader.domain.StrategyDeployment
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
import daytrader.domain.inProgressSession
import daytrader.domain.lastClosedTouchTurnSession
import daytrader.domain.touchTurnAnalysisSession
import daytrader.domain.touchTurnRecapSessionPnl
import daytrader.domain.touchTurnRecapSessionTrades
import kotlinx.coroutines.delay
import daytrader.presentation.Formatters
import daytrader.presentation.strategies.*
import daytrader.ui.theme.*

@Composable
fun StrategiesScreen(viewModel: StrategiesViewModel) {
    val uiState by viewModel.uiState.collectAsState()

    if (uiState.showAddDialog) {
        AddStrategyDeploymentDialog(
            onDismiss = viewModel::onDismissAddDialog,
            defaultMaxDollarsFor = viewModel::defaultMaxDollarsFor,
            onResolveSymbol = viewModel::resolveInstrumentForSymbol,
            onCreate = viewModel::onCreateDeployment
        )
    }

    uiState.startBlockedAlert?.let { alert ->
        StartBlockedByPositionDialog(
            alert = alert,
            onDismiss = viewModel::onDismissStartBlockedAlert
        )
    }

    Column(modifier = Modifier.fillMaxSize().padding(16.dp).testTag("StrategiesScreen")) {
        StrategiesHeader(
            searchQuery = uiState.searchQuery,
            onSearchChange = viewModel::onSearchChange,
            onClearSearch = {
                if (uiState.searchQuery.isNotEmpty()) viewModel.onSearchChange("")
            },
            onAddInstance = viewModel::onShowAddDialog
        )

        Spacer(modifier = Modifier.height(8.dp))

        StrategiesFilterPanel(
            searchQuery = uiState.searchQuery,
            deploymentFilter = uiState.deploymentFilter,
            strategyTypeFilter = uiState.strategyTypeFilter,
            selectedMarketLabel = uiState.selectedMarketLabel,
            filteredCount = uiState.filteredCount,
            totalCount = uiState.totalCount,
            hasActiveFilters = uiState.hasActiveFilters,
            onDeploymentFilterChange = viewModel::onDeploymentFilterChange,
            onStrategyTypeFilterChange = viewModel::onStrategyTypeFilterChange,
            onClearFilters = viewModel::onClearFilters
        )

        Spacer(modifier = Modifier.height(10.dp))

        HorizontalSplitPane(
            modifier = Modifier.fillMaxWidth().weight(1f),
            leftContent = {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .border(1.dp, TableHeaderBg, RoundedCornerShape(8.dp))
                        .background(SurfaceDark, RoundedCornerShape(8.dp))
                        .padding(horizontal = 8.dp, vertical = 6.dp)
                        .testTag("StrategyDeploymentList")
                ) {
                    Text(
                        if (uiState.hasActiveFilters) {
                            "Deployments (${uiState.filteredCount} of ${uiState.totalCount})"
                        } else {
                            "Deployments (${uiState.totalCount})"
                        },
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = Color.White
                    )
                    Spacer(modifier = Modifier.height(4.dp))

                    if (uiState.filteredRows.isEmpty()) {
                        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            Text(
                                "No deployments match your filter.",
                                color = TextSecondary,
                                fontSize = 13.sp
                            )
                        }
                    } else {
                        LazyColumn(
                            modifier = Modifier.fillMaxSize(),
                            verticalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            items(uiState.filteredRows, key = { it.id }) { row ->
                                StrategyDeploymentCard(
                                    row = row,
                                    isSelected = row.id == uiState.selectedDeploymentId,
                                    onSelect = { viewModel.onSelectDeployment(row.id) },
                                    onToggleSession = { viewModel.onToggleSession(row.id) },
                                    globalAutoStartEnabled = uiState.globalAutoStartEnabled,
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
            },
            rightContent = {
                StrategyDeploymentDetailPanel(
                    selectedDeployment = uiState.selectedDeployment,
                    cardPresentation = uiState.selectedCardPresentation,
                    detailTab = uiState.detailTab,
                    globalAutoStartEnabled = uiState.globalAutoStartEnabled,
                    sessionHistory = uiState.sessionHistory,
                    liveExecution = uiState.liveExecution,
                    liveBroker = uiState.liveBroker,
                    liveSessionTrades = uiState.liveSessionTrades,
                    touchTurnLiveOrderChart = uiState.touchTurnLiveOrderChart,
                    touchTurnFormingBarPriceChart = uiState.touchTurnFormingBarPriceChart,
                    touchTurnPipelineGraph = uiState.touchTurnPipelineGraph,
                    touchTurnOrderLifecycle = uiState.touchTurnOrderLifecycle,
                    tradingPanelShowsLastSessionRecap = uiState.tradingPanelShowsLastSessionRecap,
                    tradingPanelShowsLiveMarketQuotes = uiState.tradingPanelShowsLiveMarketQuotes,
                    onResetTradingPanel = viewModel::onResetTradingPanel,
                    onTabChange = viewModel::onDetailTabChange,
                    onResolveSymbol = viewModel::resolveInstrumentForSymbol,
                    onUpdateDeployment = viewModel::onUpdateDeployment,
                    onStartStop = viewModel::onToggleSession,
                    onSessionHistoryHeaderClick = viewModel::onSessionHistoryHeaderClick,
                    onSelectSessionHistory = viewModel::onSelectSessionHistory,
                    onDeleteSessionHistory = viewModel::onDeleteSessionHistory,
                    onAdjustStop = viewModel::onAdjustStop,
                    onClosePosition = viewModel::onClosePosition,
                    onDuplicate = viewModel::onDuplicateSelected,
                    onDelete = viewModel::onDeleteSelected,
                    modifier = Modifier.fillMaxSize()
                )
            }
        )
    }
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
    tradingPanelShowsLastSessionRecap: Boolean,
    tradingPanelShowsLiveMarketQuotes: Boolean,
    onResetTradingPanel: (String) -> Unit,
    onTabChange: (StrategyDetailTab) -> Unit,
    onResolveSymbol: (String, (Result<InstrumentResolution>) -> Unit) -> Unit,
    onUpdateDeployment: (String, (StrategyDeployment) -> StrategyDeployment) -> Unit,
    onStartStop: (String) -> Unit,
    onSessionHistoryHeaderClick: (SessionHistorySortColumn) -> Unit,
    onSelectSessionHistory: (String) -> Unit,
    onDeleteSessionHistory: (String, String) -> Unit,
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
                tradingPanelShowsLastSessionRecap = tradingPanelShowsLastSessionRecap,
                tradingPanelShowsLiveMarketQuotes = tradingPanelShowsLiveMarketQuotes,
                onResetTradingPanel = { onResetTradingPanel(selectedDeployment.id) },
                onTabChange = onTabChange,
                onResolveSymbol = onResolveSymbol,
                onUpdate = { transform -> onUpdateDeployment(selectedDeployment.id, transform) },
                onStartStop = { onStartStop(selectedDeployment.id) },
                onSessionHistoryHeaderClick = onSessionHistoryHeaderClick,
                onSelectSessionHistory = onSelectSessionHistory,
                onDeleteSessionHistory = onDeleteSessionHistory,
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
    searchQuery: String,
    deploymentFilter: DeploymentFilter,
    strategyTypeFilter: StrategyType?,
    selectedMarketLabel: String?,
    filteredCount: Int,
    totalCount: Int,
    hasActiveFilters: Boolean,
    onDeploymentFilterChange: (DeploymentFilter) -> Unit,
    onStrategyTypeFilterChange: (StrategyType?) -> Unit,
    onClearFilters: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(SurfaceDark, RoundedCornerShape(6.dp))
            .border(1.dp, TableHeaderBg, RoundedCornerShape(6.dp))
            .padding(horizontal = 8.dp, vertical = 5.dp)
            .testTag("StrategiesFilterPanel")
    ) {
        if (hasActiveFilters) {
            ActiveFiltersSummaryRow(
                searchQuery = searchQuery,
                deploymentFilter = deploymentFilter,
                strategyTypeFilter = strategyTypeFilter,
                selectedMarketLabel = selectedMarketLabel,
                filteredCount = filteredCount,
                totalCount = totalCount,
                onClearFilters = onClearFilters
            )
            HorizontalDivider(
                modifier = Modifier.padding(vertical = 4.dp),
                color = TableHeaderBg,
                thickness = 1.dp
            )
        }
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
            if (!hasActiveFilters) {
                Text(
                    "$totalCount total",
                    fontSize = 10.sp,
                    color = TextSecondary,
                    modifier = Modifier.padding(start = 6.dp)
                )
            }
        }
    }
}

@Composable
private fun ActiveFiltersSummaryRow(
    searchQuery: String,
    deploymentFilter: DeploymentFilter,
    strategyTypeFilter: StrategyType?,
    selectedMarketLabel: String?,
    filteredCount: Int,
    totalCount: Int,
    onClearFilters: () -> Unit
) {
    val parts = buildList {
        selectedMarketLabel?.let { add("$it market") }
        if (searchQuery.isNotBlank()) add("search \"$searchQuery\"")
        if (deploymentFilter != DeploymentFilter.ALL) {
            add(
                when (deploymentFilter) {
                    DeploymentFilter.RUNNING -> "active only"
                    DeploymentFilter.STOPPED -> "stopped only"
                    DeploymentFilter.ALL -> ""
                }
            )
        }
        strategyTypeFilter?.let { add(StrategyCatalog.displayName(it)) }
    }
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            buildString {
                append("$filteredCount of $totalCount")
                if (parts.isNotEmpty()) {
                    append(" · ")
                    append(parts.joinToString(", "))
                }
            },
            color = Color.White,
            fontSize = 10.sp,
            fontWeight = FontWeight.Medium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f)
        )
        TextButton(
            onClick = onClearFilters,
            contentPadding = PaddingValues(horizontal = 6.dp, vertical = 0.dp),
            modifier = Modifier.height(20.dp)
        ) {
            Text("Clear", color = GainGreen, fontSize = 10.sp)
        }
    }
}

@Composable
private fun animatedCardPulseAlpha(accent: DeploymentCardAccent): Float {
    if (!accent.isPulsing) return 1f
    val transition = rememberInfiniteTransition(label = "instanceCardPulse")
    val alpha by transition.animateFloat(
        initialValue = 0.28f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(animation = tween(750), repeatMode = RepeatMode.Reverse),
        label = "instanceCardPulseAlpha"
    )
    return alpha
}

@Composable
private fun InstanceCardChrome(
    accent: DeploymentCardAccent,
    isSelected: Boolean = false,
    shape: RoundedCornerShape = RoundedCornerShape(8.dp),
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit
) {
    val style = instanceCardStyle(accent)
    val pulseAlpha = animatedCardPulseAlpha(accent)
    val accentBorder = style.borderColor.copy(alpha = style.borderColor.alpha * pulseAlpha)
    val borderColor = if (isSelected) BrandRed else accentBorder
    val borderWidth = if (isSelected) 2.dp else style.borderWidth
    Box(
        modifier = modifier
            .border(borderWidth, borderColor, shape)
            .background(style.surfaceBrush, shape)
    ) {
        content()
    }
}

private data class InstanceCardStyle(
    val borderWidth: Dp,
    val borderColor: Color,
    val surfaceBrush: Brush
)

private fun instanceCardStyle(accent: DeploymentCardAccent): InstanceCardStyle = when (accent) {
    DeploymentCardAccent.ERROR -> InstanceCardStyle(
        2.dp,
        SessionErrorBorder.copy(alpha = 0.9f),
        Brush.verticalGradient(listOf(SessionErrorSurface, SessionErrorGlow))
    )
    DeploymentCardAccent.STOPPED_IDLE -> InstanceCardStyle(
        1.dp,
        TableHeaderBg,
        Brush.linearGradient(listOf(SurfaceDark, SurfaceDark))
    )
    DeploymentCardAccent.STOPPED_NEUTRAL -> InstanceCardStyle(
        2.dp,
        TradeNeutralBorder,
        Brush.verticalGradient(listOf(TradeNeutralSurface, TradeNeutralGlow))
    )
    DeploymentCardAccent.STOPPED_WIN -> InstanceCardStyle(
        2.dp,
        MarketOpenBorder.copy(alpha = 0.9f),
        Brush.verticalGradient(listOf(MarketOpenSurface, MarketOpenGlow))
    )
    DeploymentCardAccent.STOPPED_LOSS -> InstanceCardStyle(
        2.dp,
        TradeRedBorder.copy(alpha = 0.9f),
        Brush.verticalGradient(listOf(TradeRedSurface, TradeRedGlow))
    )
    DeploymentCardAccent.RUNNING_FLAT -> InstanceCardStyle(
        2.dp,
        TradeBlueBorder.copy(alpha = 0.85f),
        Brush.verticalGradient(listOf(TradeBlueSurface, TradeBlueGlow))
    )
    DeploymentCardAccent.RUNNING_IN_THE_MONEY -> InstanceCardStyle(
        2.dp,
        MarketOpenBorder,
        Brush.verticalGradient(listOf(MarketOpenSurface, MarketOpenGlow))
    )
    DeploymentCardAccent.RUNNING_OUT_OF_THE_MONEY -> InstanceCardStyle(
        2.dp,
        TradeRedBorder,
        Brush.verticalGradient(listOf(TradeRedSurface, TradeRedGlow))
    )
    DeploymentCardAccent.OPEN_ORDERS -> InstanceCardStyle(
        2.dp,
        OpenOrdersBrownBorder,
        Brush.verticalGradient(listOf(OpenOrdersBrownSurface, OpenOrdersBrownGlow))
    )
}

private fun instanceChipColor(accent: DeploymentCardAccent): Color = when (accent) {
    DeploymentCardAccent.ERROR -> SessionErrorBorder
    DeploymentCardAccent.STOPPED_IDLE -> TextSecondary
    DeploymentCardAccent.STOPPED_NEUTRAL -> TradeNeutralBorder
    DeploymentCardAccent.STOPPED_WIN,
    DeploymentCardAccent.RUNNING_IN_THE_MONEY -> MarketOpenBorder
    DeploymentCardAccent.RUNNING_FLAT -> TradeBlueBorder
    DeploymentCardAccent.STOPPED_LOSS,
    DeploymentCardAccent.RUNNING_OUT_OF_THE_MONEY -> TradeRedBorder
    DeploymentCardAccent.OPEN_ORDERS -> OpenOrdersBrownBorder
}

@Composable
private fun InstanceStateChip(
    label: String,
    accent: DeploymentCardAccent,
    compact: Boolean = false
) {
    val baseColor = instanceChipColor(accent)
    val pulseAlpha = animatedCardPulseAlpha(accent)
    val color = baseColor.copy(alpha = baseColor.alpha * pulseAlpha)
    val dotSize = if (compact) 5.dp else 8.dp
    val fontSize = if (compact) 9.sp else 12.sp
    val spacing = if (compact) 4.dp else 6.dp
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(spacing)) {
        Box(
            modifier = Modifier
                .size(dotSize)
                .background(color, RoundedCornerShape(50))
        )
        Text(
            label,
            color = color,
            fontSize = fontSize,
            fontWeight = FontWeight.Medium,
            maxLines = 1
        )
    }
}

@Composable
private fun StrategiesHeader(
    searchQuery: String,
    onSearchChange: (String) -> Unit,
    onClearSearch: () -> Unit,
    onAddInstance: () -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            "Strategies",
            fontSize = 18.sp,
            fontWeight = FontWeight.SemiBold,
            color = Color.White
        )

        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            OutlinedTextField(
                value = searchQuery,
                onValueChange = onSearchChange,
                placeholder = {
                    Text("Search…", color = TextSecondary, fontSize = 11.sp)
                },
                leadingIcon = {
                    Icon(
                        Icons.Default.Search,
                        contentDescription = "Search",
                        tint = TextSecondary,
                        modifier = Modifier.size(14.dp)
                    )
                },
                trailingIcon = {
                    if (searchQuery.isNotEmpty()) {
                        IconButton(
                            onClick = onClearSearch,
                            modifier = Modifier.size(24.dp)
                        ) {
                            Icon(
                                Icons.Default.Close,
                                contentDescription = "Clear search",
                                tint = TextSecondary,
                                modifier = Modifier.size(14.dp)
                            )
                        }
                    }
                },
                singleLine = true,
                textStyle = TextStyle(fontSize = 11.sp, color = Color.White),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedContainerColor = DarkBackground,
                    unfocusedContainerColor = DarkBackground,
                    focusedBorderColor = Color.Transparent,
                    unfocusedBorderColor = Color.Transparent
                ),
                shape = RoundedCornerShape(8.dp),
                modifier = Modifier
                    .width(200.dp)
                    .height(32.dp)
                    .testTag("StrategySearchField")
            )
            Button(
                onClick = onAddInstance,
                colors = ButtonDefaults.buttonColors(containerColor = BrandRed),
                shape = RoundedCornerShape(6.dp),
                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 0.dp),
                modifier = Modifier
                    .height(32.dp)
                    .testTag("AddStrategyDeploymentButton")
            ) {
                Icon(
                    Icons.Default.Add,
                    contentDescription = "Add",
                    tint = Color.White,
                    modifier = Modifier.size(14.dp)
                )
                Spacer(modifier = Modifier.width(4.dp))
                Text("Deploy", color = Color.White, fontSize = 11.sp)
            }
        }
    }
}

@Composable
private fun FilterChip(label: String, selected: Boolean, onClick: () -> Unit) {
    val bg = if (selected) BrandRed.copy(alpha = 0.25f) else DarkBackground
    val borderColor = if (selected) BrandRed else TableHeaderBg
    Text(
        text = label,
        modifier = Modifier
            .clickable(onClick = onClick)
            .background(bg, RoundedCornerShape(10.dp))
            .border(1.dp, borderColor, RoundedCornerShape(10.dp))
            .padding(horizontal = 8.dp, vertical = 3.dp),
        color = if (selected) Color.White else TextSecondary,
        fontSize = 10.sp,
        fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
        maxLines = 1
    )
}

@Composable
private fun CompactAutoStartToggle(
    checked: Boolean,
    enabled: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier
) {
    val tint = when {
        !enabled -> TextSecondary.copy(alpha = 0.4f)
        checked -> GainGreen
        else -> TextSecondary
    }
    IconButton(
        onClick = { onCheckedChange(!checked) },
        enabled = enabled,
        modifier = modifier
            .size(20.dp)
            .testTag("AutoStartToggle")
    ) {
        Icon(
            imageVector = Icons.Default.Schedule,
            contentDescription = if (checked) "Auto-start on" else "Auto-start off",
            tint = tint,
            modifier = Modifier.size(14.dp)
        )
    }
}

@Composable
private fun StrategyDeploymentCard(
    row: StrategyDeploymentRowUi,
    isSelected: Boolean,
    globalAutoStartEnabled: Boolean,
    onSelect: () -> Unit,
    onToggleSession: () -> Unit,
    onAutoStartChange: (Boolean) -> Unit
) {
    val cardShape = RoundedCornerShape(6.dp)
    InstanceCardChrome(
        accent = row.cardAccent,
        isSelected = isSelected,
        shape = cardShape,
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onSelect)
            .testTag("StrategyDeploymentCard-${row.id}")
    ) {
        Column(modifier = Modifier.padding(horizontal = 6.dp, vertical = 5.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        row.instrumentName,
                        color = Color.White,
                        fontWeight = FontWeight.Bold,
                        fontSize = 12.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    if (row.instrumentName != row.name) {
                        Text(
                            row.name,
                            color = TextSecondary,
                            fontSize = 10.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
                CompactAutoStartToggle(
                    checked = row.autoStartOnMarketOpen,
                    enabled = globalAutoStartEnabled,
                    onCheckedChange = onAutoStartChange
                )
                IconButton(
                    onClick = onToggleSession,
                    modifier = Modifier.size(24.dp)
                ) {
                    Icon(
                        imageVector = if (row.status == DeploymentStatus.RUNNING) {
                            Icons.Default.Stop
                        } else {
                            Icons.Default.PlayArrow
                        },
                        contentDescription = if (row.status == DeploymentStatus.RUNNING) "Stop" else "Start",
                        tint = if (row.status == DeploymentStatus.RUNNING) LossRed else GainGreen,
                        modifier = Modifier.size(16.dp)
                    )
                }
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                InstanceStateChip(
                    label = row.statusChipLabel,
                    accent = row.cardAccent,
                    compact = true
                )
                Spacer(modifier = Modifier.weight(1f))
                CompactInstanceStat(
                    label = "Win %",
                    value = row.formattedWinRate,
                    valueColor = winRateColor(row.winRateIsPositive)
                )
                CompactInstanceStat(
                    label = "Net P&L",
                    value = row.formattedTotalPnL,
                    valueColor = if (row.isPositiveTotalPnL) GainGreen else LossRed
                )
            }
        }
    }
}

private fun winRateColor(winRateIsPositive: Boolean?): Color = when (winRateIsPositive) {
    true -> MarketOpenBorder
    false -> TradeRedBorder
    null -> TextSecondary
}

@Composable
private fun CompactInstanceStat(
    label: String,
    value: String,
    valueColor: Color
) {
    Column(horizontalAlignment = Alignment.End) {
        Text(label, fontSize = 8.sp, color = TextSecondary, lineHeight = 9.sp)
        Text(
            value,
            fontSize = 10.sp,
            fontWeight = FontWeight.SemiBold,
            color = valueColor,
            maxLines = 1,
            lineHeight = 11.sp
        )
    }
}

@Composable
private fun InstanceRollupRow(row: StrategyDeploymentRowUi) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        InstanceRollupCell("7D", row.formattedRollup7d, row.isPositiveRollup7d)
        InstanceRollupCell("14D", row.formattedRollup14d, row.isPositiveRollup14d)
        InstanceRollupCell("30D", row.formattedRollup30d, row.isPositiveRollup30d)
        InstanceRollupCell("Win %", row.formattedWinRate)
    }
}

@Composable
private fun RowScope.InstanceRollupCell(label: String, value: String, positive: Boolean? = null) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.weight(1f)) {
        Text(label, fontSize = 10.sp, color = TextSecondary)
        Spacer(modifier = Modifier.height(2.dp))
        Text(
            value,
            fontSize = 11.sp,
            fontWeight = FontWeight.SemiBold,
            color = when (positive) {
                true -> GainGreen
                false -> LossRed
                null -> if (value == "—") TextSecondary else Color.White
            }
        )
    }
}

@Composable
private fun StrategyTypePill(label: String) {
    Text(
        text = label,
        modifier = Modifier
            .background(BrandRed.copy(alpha = 0.2f), RoundedCornerShape(4.dp))
            .padding(horizontal = 8.dp, vertical = 2.dp),
        color = BrandRed,
        fontSize = 10.sp,
        fontWeight = FontWeight.Medium
    )
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
    tradingPanelShowsLastSessionRecap: Boolean,
    tradingPanelShowsLiveMarketQuotes: Boolean,
    onResetTradingPanel: () -> Unit,
    onTabChange: (StrategyDetailTab) -> Unit,
    onResolveSymbol: (String, (Result<InstrumentResolution>) -> Unit) -> Unit,
    onUpdate: ((StrategyDeployment) -> StrategyDeployment) -> Unit,
    onStartStop: () -> Unit,
    onSessionHistoryHeaderClick: (SessionHistorySortColumn) -> Unit,
    onSelectSessionHistory: (String) -> Unit,
    onDeleteSessionHistory: (String, String) -> Unit,
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

        if (detailTab == StrategyDetailTab.LIVE && tradingPanelShowsLiveMarketQuotes) {
            liveBroker?.let { broker -> TradingTabLiveMarketStrip(broker) }
        }

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
                    onResolveSymbol = onResolveSymbol,
                    onUpdate = onUpdate
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
                    showLastSessionRecap = tradingPanelShowsLastSessionRecap,
                    onResetTradingPanel = onResetTradingPanel,
                    onAdjustStop = onAdjustStop,
                    onClosePosition = onClosePosition
                )
                StrategyDetailTab.SESSION_HISTORY -> PerformanceTab(
                    sessionHistory = sessionHistory,
                    onSessionHistoryHeaderClick = onSessionHistoryHeaderClick,
                    onSelectRun = onSelectSessionHistory,
                    onDeleteRun = { runId -> onDeleteSessionHistory(instance.id, runId) }
                )
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

@Composable
private fun ConfigurationTab(
    instance: StrategyDeployment,
    globalAutoStartEnabled: Boolean,
    onResolveSymbol: (String, (Result<InstrumentResolution>) -> Unit) -> Unit,
    onUpdate: ((StrategyDeployment) -> StrategyDeployment) -> Unit
) {
    val canEdit = instance.status != DeploymentStatus.RUNNING

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
            onUpdate = onUpdate
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
        ConfigField(
            label = "Risk budget (\$)",
            value = instance.maxDollars.toString(),
            enabled = canEdit,
            onValueChange = { value ->
                value.toIntOrNull()?.takeIf { it > 0 }?.let { max ->
                    onUpdate { it.copy(maxDollars = max) }
                }
            }
        )
        if (instance.strategyType == StrategyType.TOUCH_AND_TURN_SCALPER) {
            TouchTurnMarketOpenTimers(deployment = instance)
        }
    }
}

@Composable
private fun TouchTurnMarketOpenTimers(deployment: daytrader.domain.StrategyDeployment) {
    val marketZone = DeploymentMarket.effectiveZoneId(deployment)
    var tick by remember { mutableIntStateOf(0) }
    LaunchedEffect(deployment.id, marketZone) {
        while (true) {
            delay(1_000)
            tick++
        }
    }
    val timers = remember(marketZone, tick) { TouchTurnScreenLabels.marketOpenTimers(marketZone) }
    Column(
        modifier = Modifier
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
            session.candle != null -> {
                var tick by remember { mutableIntStateOf(0) }
                LaunchedEffect(session.candle.time, session.marketZoneId) {
                    while (true) {
                        delay(1_000)
                        tick++
                    }
                }
                val closeStatus = remember(session, tick) { session.candleCloseStatus() }
                val liquidityEval = remember(session, tick) { session.liquidityEvaluation() }
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
                    val orderSetup = remember(session, tick) {
                        session.setup?.takeIf { it.isLiquidityCandle }
                            ?: TouchTurnLogic.computeBracketSetup(candle, session.rangeThreshold)
                    }
                    TouchTurnPanelGroup(
                        title = "Order preview (not sent)",
                        testTag = "TouchTurnOrderPreviewGroup",
                        compact = true
                    ) {
                        Text(
                            "Preview only — ${TouchTurnLogic.orderPreviewSummary(orderSetup)}",
                            fontSize = 10.sp,
                            color = TextSecondary,
                            lineHeight = 13.sp
                        )
                        if (orderSetup.isActionable) {
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
    content: @Composable ColumnScope.() -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(SurfaceDark, RoundedCornerShape(6.dp))
            .border(1.dp, TableHeaderBg, RoundedCornerShape(6.dp))
            .padding(if (compact) 8.dp else 12.dp)
            .testTag(testTag),
        verticalArrangement = Arrangement.spacedBy(if (compact) 6.dp else 10.dp)
    ) {
        Text(
            title,
            fontSize = if (compact) 11.sp else 12.sp,
            fontWeight = FontWeight.SemiBold,
            color = BrandRed
        )
        if (!compact) {
            HorizontalDivider(color = TableHeaderBg)
        }
        content()
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
    val prices = listOf(
        candle.high,
        candle.low,
        candle.open,
        candle.close,
        setup.entry,
        setup.stopLoss,
        setup.takeProfit
    )
    val pad = candle.range * 0.15
    val priceTop = prices.max() + pad
    val priceBottom = prices.min() - pad
    val span = (priceTop - priceBottom).coerceAtLeast(0.0001)
    fun yFraction(price: Double): Float =
        ((priceTop - price) / span).toFloat().coerceIn(0f, 1f)

    val levels = listOf(
        TouchTurnPriceLevel(candle.high, "High", TextSecondary.copy(alpha = 0.55f), 1f),
        TouchTurnPriceLevel(candle.low, "Low", TextSecondary.copy(alpha = 0.55f), 1f),
        TouchTurnPriceLevel(
            setup.entry,
            "Entry (${TouchTurnLogic.tradeSideLabel(setup.side)})",
            entryColor,
            2.5f,
            TouchTurnOrderLevelKind.ENTRY
        ),
        TouchTurnPriceLevel(
            setup.takeProfit,
            "Take profit (${TouchTurnLogic.takeProfitFibLabel(setup.candleColor)})",
            GainGreen,
            2.5f,
            TouchTurnOrderLevelKind.TAKE_PROFIT
        ),
        TouchTurnPriceLevel(
            setup.stopLoss,
            "Stop loss",
            LossRed,
            2.5f,
            TouchTurnOrderLevelKind.STOP_LOSS
        )
    )

    BoxWithConstraints(
        modifier = modifier
            .fillMaxWidth()
            .height(148.dp)
    ) {
        val chartHeight = maxHeight
        val labelColumnWidth = 96.dp
        val chartWidth = (maxWidth - labelColumnWidth).coerceAtLeast(80.dp)

        Canvas(
            modifier = Modifier
                .width(chartWidth)
                .fillMaxHeight()
        ) {
            val candleLeft = size.width * 0.34f
            val candleWidth = size.width * 0.28f
            val centerX = candleLeft + candleWidth / 2f
            fun y(price: Double): Float {
                val fraction = ((priceTop - price) / span).toFloat()
                return (fraction * size.height).coerceIn(0f, size.height)
            }

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
                if (executed) return@forEach
                drawLine(
                    color = level.color.copy(alpha = 0.55f),
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
                fun y(price: Double): Float {
                    val fraction = ((priceTop - price) / span).toFloat()
                    return (fraction * size.height).coerceIn(0f, size.height)
                }
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
                val yOffset = chartHeight * yFraction(level.price) - 9.dp
                val executed = level.kind != null && level.kind in executedLevels
                val labelColor = when {
                    executed -> level.color.copy(alpha = throbAlpha)
                    level.kind != null -> level.color.copy(alpha = 0.55f)
                    else -> level.color
                }
                Row(
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .offset(x = chartWidth, y = yOffset)
                        .width(labelColumnWidth)
                        .padding(start = 4.dp),
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
    lastClosedRun: StrategySession?,
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
    val recapSessionTrades = remember(instance.id, instance.sessionHistory.size) {
        instance.touchTurnRecapSessionTrades()
    }
    val recapSessionPnl = remember(instance.id, instance.sessionHistory.size, recapSessionTrades) {
        instance.touchTurnRecapSessionPnl()
            ?: liveSessionTrades?.tradeDetail?.realizedPnL
    }
    TouchTurnPipelineDetailPanel(
        selectedNodeId = selectedNodeId,
        graph = pipelineGraph
    ) { nodeId ->
        when (nodeId) {
            TouchTurnPipelineNodeId.Start ->
                TouchTurnPipelineSectionStart(
                    instance = instance,
                    graph = pipelineGraph,
                    lastClosedRun = lastClosedRun
                )
            TouchTurnPipelineNodeId.Data ->
                TouchTurnPipelineSectionData(session = analysisSession, symbol = instance.symbol)
            TouchTurnPipelineNodeId.Bar ->
                TouchTurnPipelineSectionBar(
                    session = analysisSession,
                    formingBarPriceChart = if (sessionEnded) null else touchTurnFormingBarPriceChart
                )
            TouchTurnPipelineNodeId.Liquidity ->
                TouchTurnPipelineSectionLiquidity(session = analysisSession)
            TouchTurnPipelineNodeId.Confirmation ->
                TouchTurnPipelineSectionConfirmation(session = analysisSession)
            TouchTurnPipelineNodeId.Orders -> {
                val lifecycle = orderLifecycle
                if (!sessionEnded && touchTurnLiveOrderChart != null) {
                    TouchTurnPipelineLiveOrderChart(chart = touchTurnLiveOrderChart)
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
                        sessionTrades = if (sessionEnded) recapSessionTrades else emptyList(),
                        sessionPnl = if (sessionEnded) recapSessionPnl else null
                    )
                }
            }
            TouchTurnPipelineNodeId.Position -> {
                if (!sessionEnded && touchTurnLiveOrderChart != null) {
                    TouchTurnPipelineLiveOrderChart(chart = touchTurnLiveOrderChart)
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
            TouchTurnPipelineNodeId.NoTrade ->
                TouchTurnPipelineSectionNoTrade(session = analysisSession, graph = pipelineGraph)
            TouchTurnPipelineNodeId.Close ->
                if (sessionEnded) {
                    TouchTurnPipelineSectionClose(closedRun = lastClosedRun, graph = pipelineGraph)
                } else {
                    TouchTurnSessionAutoStopStatus(instance = instance)
                }
        }
    }
}

@Composable
private fun TouchTurnSessionAutoStopStatus(instance: StrategyDeployment) {
    var tick by remember(instance.id) { mutableIntStateOf(0) }
    LaunchedEffect(instance.id) {
        while (true) {
            delay(1_000)
            tick++
        }
    }
    val autoStop = remember(instance, tick) { TouchTurnScreenLabels.autoStopStatus(instance) } ?: return
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
                    autoStop.remainingLabel!!
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
    showLastSessionRecap: Boolean,
    onResetTradingPanel: () -> Unit,
    onAdjustStop: (String, String) -> Unit,
    onClosePosition: (String) -> Unit
) {
    val inActiveTrade = liveExecution?.state == ExecutionState.FILLED && liveExecution.showPanel
    val isTouchTurn = instance.strategyType == StrategyType.TOUCH_AND_TURN_SCALPER
    val isRunning = instance.status == DeploymentStatus.RUNNING
    val sessionEnded = isTouchTurn && !isRunning && showLastSessionRecap
    val touchTurnInstance = instance.takeIf { isTouchTurn }
    val lastClosedTouchTurnRun = remember(instance.id, instance.sessionHistory.size, showLastSessionRecap) {
        if (showLastSessionRecap) instance.lastClosedTouchTurnSession() else null
    }
    val analysisSession = remember(
        instance.id,
        instance.touchTurnSession,
        instance.sessionHistory.size,
        showLastSessionRecap,
    ) {
        if (!isRunning && !showLastSessionRecap) null
        else instance.touchTurnAnalysisSession()
    }
    val orderLifecycle = touchTurnOrderLifecycle
    var selectedPipelineNode by rememberSaveable(instance.id, showLastSessionRecap) {
        mutableStateOf<TouchTurnPipelineNodeId?>(null)
    }
    var lastTrackedCurrentNode by remember(instance.id, showLastSessionRecap) {
        mutableStateOf<TouchTurnPipelineNodeId?>(null)
    }
    val pipelineLiveSessionTrades = if (isRunning || showLastSessionRecap) liveSessionTrades else null
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
            showLastSessionRecap -> lastClosedTouchTurnRun?.id
            else -> null
        }
        activeSessionId?.let { sessionId ->
            SessionLogReference(
                deploymentId = instance.id,
                sessionId = sessionId,
                modifier = Modifier.padding(bottom = 2.dp)
            )
        }
        if (isTouchTurn && !isRunning && showLastSessionRecap) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    "Showing last session",
                    fontSize = 12.sp,
                    color = TextSecondary,
                    modifier = Modifier.testTag("TradingPanelLastSessionLabel"),
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
        if (isTouchTurn && !isRunning && !showLastSessionRecap) {
            Text(
                "Ready for the next session. Past runs stay on the Session history tab.",
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
                lastClosedRun = lastClosedTouchTurnRun,
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
private fun LiveMarketQuotesBar(
    broker: LiveBrokerUiState,
    modifier: Modifier = Modifier,
    prominent: Boolean = false
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(if (prominent) 12.dp else 8.dp)
    ) {
        LiveMarketQuoteCell(
            label = "Bid",
            value = broker.formattedBid ?: "—",
            modifier = Modifier.weight(1f),
            prominent = prominent
        )
        LiveMarketQuoteCell(
            label = "Ask",
            value = broker.formattedAsk ?: "—",
            modifier = Modifier.weight(1f),
            prominent = prominent
        )
        LiveMarketQuoteCell(
            label = "Last",
            value = broker.formattedLast ?: "—",
            modifier = Modifier.weight(1f),
            prominent = prominent
        )
    }
}

@Composable
private fun LiveMarketQuoteCell(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
    prominent: Boolean = false
) {
    Column(modifier = modifier) {
        Text(
            label,
            fontSize = if (prominent) 10.sp else 9.sp,
            color = TextSecondary,
            fontWeight = FontWeight.Medium,
            maxLines = 1
        )
        Text(
            value,
            fontSize = if (prominent) 16.sp else 11.sp,
            fontWeight = FontWeight.SemiBold,
            color = Color.White,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
private fun TradingTabLiveMarketStrip(broker: LiveBrokerUiState) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(TableHeaderBg)
            .padding(horizontal = 20.dp, vertical = 10.dp)
            .testTag("TradingTabLiveMarketStrip")
    ) {
        Text(
            "Live market · ${broker.symbol}",
            fontSize = 11.sp,
            fontWeight = FontWeight.SemiBold,
            color = BrandRed
        )
        Spacer(modifier = Modifier.height(6.dp))
        LiveMarketQuotesBar(broker = broker, prominent = true)
        broker.fillReadinessHint?.let { hint ->
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = hint,
                fontSize = 10.sp,
                color = TextSecondary
            )
        }
    }
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
    var breadcrumbTick by remember { mutableIntStateOf(0) }
    LaunchedEffect(touchTurnInstance?.id, touchTurnInstance?.touchTurnSession?.candle?.time) {
        if (touchTurnInstance == null || pipelineGraphOverride != null) return@LaunchedEffect
        while (true) {
            delay(1_000)
            breadcrumbTick++
        }
    }
    val hasOpenOrders = broker?.openOrders?.isNotEmpty() == true
    val pipelineGraph = pipelineGraphOverride ?: remember(
        touchTurnInstance,
        display.hasOpenPosition,
        hasOpenOrders,
        breadcrumbTick
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
            .padding(horizontal = 14.dp, vertical = 12.dp)
            .testTag("LiveTradingPositionPnLHeader"),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        if (pipelineGraph != null) {
            TouchTurnPipelineGraphView(
                graph = pipelineGraph,
                compact = style == LiveTradingHeaderStyle.BreadcrumbOnly,
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
            TouchTurnPanelGroup(
                title = "Broker position (${broker.symbol})",
                testTag = "LiveBrokerPositionGroup",
                compact = true
            ) {
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
        }

        val ordersTitle = if (slimOrders) "Working bracket" else "Open orders (${broker.symbol})"
        TouchTurnPanelGroup(
            title = ordersTitle,
            testTag = "LiveBrokerOrdersGroup",
            compact = true
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
private fun PerformanceTab(
    sessionHistory: SessionHistoryUiState?,
    onSessionHistoryHeaderClick: (SessionHistorySortColumn) -> Unit,
    onSelectRun: (runId: String) -> Unit,
    onDeleteRun: (runId: String) -> Unit
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

    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        SessionHistorySummaryBar(
            rollup30d = sessionHistory.rollup30d,
            winRate = sessionHistory.winRate
        )

        SessionHistoryBlotterTable(
            sessionHistory = sessionHistory,
            onHeaderClick = onSessionHistoryHeaderClick,
            onSelectRun = onSelectRun,
            onDeleteRun = onDeleteRun,
            modifier = Modifier.fillMaxWidth()
        )
    }
}

@Composable
private fun SessionHistorySummaryBar(
    rollup30d: String,
    winRate: String,
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
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Win", fontSize = 10.sp, color = TextSecondary)
            Spacer(modifier = Modifier.width(6.dp))
            Text(winRate, fontSize = 15.sp, fontWeight = FontWeight.Bold, color = Color.White)
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
private fun ConfigField(
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
private fun AutoStartOnMarketOpenField(
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

@Composable
private fun StartBlockedByPositionDialog(
    alert: StartBlockedByPositionAlert,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        modifier = Modifier.testTag("StartBlockedByPositionDialog"),
        containerColor = SurfaceDark,
        title = {
            Text("Cannot start deployment", color = Color.White, fontWeight = FontWeight.Bold)
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(alert.summary, color = Color.White, fontSize = 14.sp, lineHeight = 20.sp)
                Text(alert.positionDetails, color = TextSecondary, fontSize = 13.sp, lineHeight = 18.sp)
                Text(alert.reason, color = TextSecondary, fontSize = 13.sp, lineHeight = 18.sp)
            }
        },
        confirmButton = {
            Button(onClick = onDismiss) {
                Text("OK")
            }
        }
    )
}

@Composable
private fun AddStrategyDeploymentDialog(
    onDismiss: () -> Unit,
    defaultMaxDollarsFor: (StrategyType) -> Int,
    onResolveSymbol: (String, (Result<InstrumentResolution>) -> Unit) -> Unit,
    onCreate: (
        StrategyType,
        String,
        String,
        String,
        MarketSource,
        String?,
        InstrumentIdentity?,
        Int,
        Boolean
    ) -> Unit
) {
    var selectedStrategyType by remember { mutableStateOf(StrategyType.TOUCH_AND_TURN_SCALPER) }
    var symbol by remember { mutableStateOf("") }
    var maxDollarsText by remember {
        mutableStateOf(defaultMaxDollarsFor(StrategyType.TOUCH_AND_TURN_SCALPER).toString())
    }
    var autoStartOnMarketOpen by remember { mutableStateOf(false) }
    var resolving by remember { mutableStateOf(false) }
    var candidates by remember { mutableStateOf<List<ResolvedInstrument>>(emptyList()) }
    var selectedResolved by remember { mutableStateOf<ResolvedInstrument?>(null) }
    var selectedMarketZoneId by remember { mutableStateOf<String?>(null) }
    var marketSource by remember { mutableStateOf(MarketSource.SYMBOL_INFERRED) }
    var userEditedMarket by remember { mutableStateOf(false) }

    LaunchedEffect(symbol) {
        val trimmed = symbol.trim()
        if (trimmed.isBlank() || trimmed.length < 2) {
            resolving = false
            candidates = emptyList()
            selectedResolved = null
            selectedMarketZoneId = null
            return@LaunchedEffect
        }
        delay(400)
        resolving = true
        onResolveSymbol(trimmed) { result ->
            resolving = false
            result.onSuccess { resolution ->
                candidates = InstrumentListingCandidates.prepareForUi(resolution.candidates)
                selectedResolved = when {
                    candidates.size == 1 -> candidates.first()
                    else -> null
                }
                if (!userEditedMarket) {
                    when {
                        candidates.size == 1 ->
                            selectedResolved?.let { suggestion ->
                                selectedMarketZoneId = suggestion.marketZoneId
                                marketSource = suggestion.source
                            }
                        candidates.size > 1 -> selectedMarketZoneId = null
                    }
                }
                InstrumentResolveLog.uiReceived(
                    symbol = trimmed,
                    uiCount = candidates.size,
                    selected = selectedResolved?.let { InstrumentListingCandidates.listingLabel(it) }
                )
            }.onFailure { error ->
                candidates = emptyList()
                selectedResolved = null
                if (!userEditedMarket) selectedMarketZoneId = null
                InstrumentResolveLog.resolveFinished(
                    symbol = trimmed,
                    success = false,
                    rawCount = 0,
                    uiCount = 0,
                    listings = emptyList(),
                    error = error.message
                )
            }
        }
    }

    val resolved = selectedResolved

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = SurfaceDark,
        title = {
            Text("Deploy strategy", color = Color.White, fontWeight = FontWeight.Bold)
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("Choose strategy", fontSize = 12.sp, color = TextSecondary, fontWeight = FontWeight.Medium)
                StrategyType.entries.forEach { type ->
                    StrategyTypePickerCard(
                        strategyType = type,
                        selected = selectedStrategyType == type,
                        onSelect = {
                            selectedStrategyType = type
                            maxDollarsText = defaultMaxDollarsFor(type).toString()
                        }
                    )
                }
                HorizontalDivider(color = TableHeaderBg)
                ConfigField(
                    label = "Symbol",
                    value = symbol,
                    onValueChange = {
                        symbol = it
                        userEditedMarket = false
                        selectedResolved = null
                    }
                )
                val resolvedCompanyName = resolved?.companyName?.takeIf { it.isNotBlank() }
                if (!resolving && resolvedCompanyName != null) {
                    Text(
                        text = resolvedCompanyName,
                        fontSize = 14.sp,
                        color = Color.White,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.testTag("ResolvedCompanyName")
                    )
                } else if (resolving && symbol.isNotBlank()) {
                    Text(
                        text = "Resolving company name…",
                        fontSize = 12.sp,
                        color = TextSecondary
                    )
                }
                if (!resolving && candidates.size > 1) {
                    InstrumentListingPicker(
                        candidates = candidates,
                        selected = selectedResolved,
                        onSelect = { picked ->
                            selectedResolved = picked
                            if (!userEditedMarket) {
                                selectedMarketZoneId = picked.marketZoneId
                                marketSource = picked.source
                            }
                        }
                    )
                }
                InstrumentResolutionPanel(
                    resolving = resolving,
                    resolved = resolved,
                    selectedMarketZoneId = selectedMarketZoneId,
                    persistedCompanyName = null,
                    persistedCurrencyCode = null,
                    canEditMarket = true,
                    onMarketSelected = { zoneId ->
                        userEditedMarket = true
                        selectedMarketZoneId = zoneId
                        marketSource = MarketSource.USER
                    }
                )
                ConfigField(
                    label = "Risk budget (\$)",
                    value = maxDollarsText,
                    onValueChange = { maxDollarsText = it },
                    enabled = selectedMarketZoneId != null
                )
                AutoStartOnMarketOpenField(
                    checked = autoStartOnMarketOpen,
                    enabled = selectedMarketZoneId != null,
                    onCheckedChange = { autoStartOnMarketOpen = it }
                )
            }
        },
        confirmButton = {
            val maxDollars = maxDollarsText.toIntOrNull() ?: 0
            val zoneId = selectedMarketZoneId
            val currency = when {
                zoneId == null -> "USD"
                marketSource == MarketSource.USER ->
                    DeploymentMarket.currencyForZone(zoneId)
                else -> resolved?.currencyCode ?: DeploymentMarket.currencyForZone(zoneId)
            }
            val companyName = resolved?.companyName?.takeIf { it.isNotBlank() }
            val listingChosen = candidates.isEmpty() || selectedResolved != null
            Button(
                onClick = {
                    if (zoneId != null) {
                        onCreate(
                            selectedStrategyType,
                            symbol.trim(),
                            zoneId,
                            currency,
                            marketSource,
                            companyName,
                            selectedResolved?.identity,
                            maxDollars,
                            autoStartOnMarketOpen
                        )
                    }
                },
                enabled = symbol.isNotBlank() && maxDollars > 0 && zoneId != null &&
                    listingChosen && !resolving,
                colors = ButtonDefaults.buttonColors(containerColor = BrandRed),
                modifier = Modifier.testTag("CreateStrategyDeploymentButton")
            ) {
                Text("Create")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel", color = TextSecondary)
            }
        }
    )
}

@Composable
private fun InstrumentListingPicker(
    candidates: List<ResolvedInstrument>,
    selected: ResolvedInstrument?,
    onSelect: (ResolvedInstrument) -> Unit
) {
    Column(
        verticalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.testTag("InstrumentListingPicker")
    ) {
        Text(
            "Listing / exchange",
            fontSize = 11.sp,
            color = TextSecondary,
            fontWeight = FontWeight.Medium
        )
        Text(
            if (selected == null) {
                "Multiple venues found — select one to continue."
            } else {
                "Selected listing:"
            },
            fontSize = 12.sp,
            color = if (selected == null) LossRed else TextSecondary,
            lineHeight = 15.sp
        )
        candidates.forEach { candidate ->
            val label = InstrumentListingCandidates.listingLabel(candidate)
            val picked = selected?.identity?.dedupeKey() == candidate.identity?.dedupeKey()
            val borderColor = if (picked) BrandRed else TableHeaderBg
            val background = if (picked) BrandRed.copy(alpha = 0.2f) else DarkBackground
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(6.dp))
                    .background(background)
                    .border(1.dp, borderColor, RoundedCornerShape(6.dp))
                    .clickable { onSelect(candidate) }
                    .padding(horizontal = 12.dp, vertical = 10.dp)
                    .testTag("InstrumentListingOption-${candidate.identity?.dedupeKey()}"),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                RadioButton(
                    selected = picked,
                    onClick = { onSelect(candidate) },
                    colors = RadioButtonDefaults.colors(
                        selectedColor = BrandRed,
                        unselectedColor = TextSecondary
                    )
                )
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        label,
                        fontSize = 14.sp,
                        color = Color.White,
                        fontWeight = FontWeight.SemiBold
                    )
                    candidate.companyName?.takeIf { it.isNotBlank() }?.let { name ->
                        Text(name, fontSize = 12.sp, color = TextSecondary, lineHeight = 15.sp)
                    }
                }
            }
        }
    }
}

@Composable
private fun InstrumentResolutionPanel(
    resolving: Boolean,
    resolved: ResolvedInstrument?,
    selectedMarketZoneId: String?,
    persistedCompanyName: String?,
    persistedCurrencyCode: String?,
    canEditMarket: Boolean,
    onMarketSelected: (String) -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Market & currency", fontSize = 12.sp, color = TextSecondary, fontWeight = FontWeight.Medium)
        when {
            resolving -> Text("Looking up instrument…", fontSize = 12.sp, color = TextSecondary)
            resolved == null && selectedMarketZoneId == null && persistedCompanyName.isNullOrBlank() ->
                Text("Enter a symbol to resolve market and currency.", fontSize = 12.sp, color = TextSecondary)
            else -> {
                val zoneId = selectedMarketZoneId ?: resolved?.marketZoneId
                val currency = persistedCurrencyCode
                    ?: resolved?.currencyCode
                    ?: zoneId?.let { DeploymentMarket.currencyForZone(it) }
                    ?: "—"
                val session = zoneId?.let { DeploymentMarket.sessionForZone(it) }
                val marketLabel = session?.let { DeploymentMarket.sessionDisplayLabel(it) } ?: "—"
                val companyName = persistedCompanyName?.takeIf { it.isNotBlank() }
                    ?: resolved?.companyName?.takeIf { it.isNotBlank() }
                if (companyName != null) {
                    Text(
                        text = companyName,
                        fontSize = 14.sp,
                        color = Color.White,
                        fontWeight = FontWeight.SemiBold,
                        lineHeight = 18.sp
                    )
                }
                Text(
                    text = resolved?.let { suggestion ->
                        val prefix = when (suggestion.source) {
                            MarketSource.IB -> "From IB"
                            MarketSource.SYMBOL_INFERRED -> "Estimated"
                            MarketSource.USER -> "Your selection"
                            MarketSource.LEGACY_INFERRED -> "Inferred"
                        }
                        "$prefix: ${suggestion.venueLabel}"
                    } ?: if (persistedCompanyName != null) {
                        "Saved market: $marketLabel · $currency"
                    } else {
                        "Select market below."
                    },
                    fontSize = 12.sp,
                    color = TextSecondary,
                    lineHeight = 15.sp
                )
                Text(
                    text = "Trading session: $marketLabel · $currency",
                    fontSize = 13.sp,
                    color = Color.White,
                    fontWeight = FontWeight.Medium
                )
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            RthMarketSessions.all.forEach { session ->
                val selected = selectedMarketZoneId == session.zoneId
                FilterChip(
                    selected = selected,
                    onClick = { if (canEditMarket) onMarketSelected(session.zoneId) },
                    enabled = canEditMarket,
                    label = {
                        Text(
                            DeploymentMarket.sessionDisplayLabel(session),
                            fontSize = 12.sp
                        )
                    },
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = BrandRed.copy(alpha = 0.35f),
                        selectedLabelColor = Color.White
                    )
                )
            }
        }
    }
}

@Composable
private fun DeploymentMarketSection(
    deployment: StrategyDeployment,
    canEdit: Boolean,
    onResolveSymbol: (String, (Result<InstrumentResolution>) -> Unit) -> Unit,
    onUpdate: ((StrategyDeployment) -> StrategyDeployment) -> Unit
) {
    var ibSuggestion by remember(deployment.id) { mutableStateOf<ResolvedInstrument?>(null) }
    var resolving by remember { mutableStateOf(false) }

    LaunchedEffect(deployment.symbol, canEdit) {
        if (!canEdit) return@LaunchedEffect
        resolving = true
        onResolveSymbol(deployment.symbol) { result ->
            resolving = false
            result.onSuccess { resolution ->
                val savedKey = deployment.instrument?.dedupeKey()
                ibSuggestion = resolution.candidates.firstOrNull { candidate ->
                    savedKey != null && candidate.identity?.dedupeKey() == savedKey
                } ?: resolution.singleOrNull() ?: resolution.candidates.firstOrNull()
                val suggestion = ibSuggestion
                if (canEdit &&
                    suggestion?.companyName != null &&
                    deployment.companyName.isNullOrBlank()
                ) {
                    onUpdate { it.copy(companyName = suggestion.companyName) }
                }
            }
        }
    }

    val effectiveZone = DeploymentMarket.effectiveZoneId(deployment)
    val effectiveCurrency = DeploymentMarket.effectiveCurrencyCode(deployment)
    val session = DeploymentMarket.sessionForZone(effectiveZone)
    val mismatch = ibSuggestion?.let { it.marketZoneId != effectiveZone } == true

    InstrumentResolutionPanel(
        resolving = resolving && canEdit,
        resolved = ibSuggestion,
        selectedMarketZoneId = effectiveZone,
        persistedCompanyName = deployment.companyName,
        persistedCurrencyCode = effectiveCurrency,
        canEditMarket = canEdit,
        onMarketSelected = { zoneId ->
            onUpdate {
                it.copy(
                    marketZoneId = zoneId,
                    currencyCode = DeploymentMarket.currencyForZone(zoneId),
                    marketSource = MarketSource.USER
                )
            }
        }
    )
    if (mismatch && ibSuggestion != null) {
        Text(
            "IB suggests ${DeploymentMarket.sessionDisplayLabel(
                DeploymentMarket.sessionForZone(ibSuggestion!!.marketZoneId)
            )} (${ibSuggestion!!.venueLabel}). This deployment uses ${DeploymentMarket.sessionDisplayLabel(session)}.",
            fontSize = 12.sp,
            color = LossRed,
            lineHeight = 15.sp
        )
    }
    deployment.instrument?.let { identity ->
        Text(
            "Saved listing: ${identity.primaryExch ?: identity.exchange} · ${identity.currency}",
            fontSize = 12.sp,
            color = TextSecondary,
            lineHeight = 15.sp,
            modifier = Modifier.testTag("SavedInstrumentListing")
        )
    }
}

@Composable
private fun StrategyTypePickerCard(
    strategyType: StrategyType,
    selected: Boolean,
    onSelect: () -> Unit
) {
    val borderColor = if (selected) BrandRed else TableHeaderBg
    val backgroundColor = if (selected) BrandRed.copy(alpha = 0.12f) else DarkBackground
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onSelect)
            .border(1.dp, borderColor, RoundedCornerShape(8.dp))
            .background(backgroundColor, RoundedCornerShape(8.dp))
            .padding(12.dp)
            .testTag("StrategyTypePicker-${strategyType.name}")
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                StrategyCatalog.displayName(strategyType),
                color = if (selected) Color.White else TextSecondary,
                fontWeight = FontWeight.SemiBold,
                fontSize = 14.sp
            )
            if (selected) {
                Icon(Icons.Default.CheckCircle, contentDescription = "Selected", tint = BrandRed, modifier = Modifier.size(18.dp))
            }
        }
        Spacer(modifier = Modifier.height(4.dp))
        Text(StrategyCatalog.description(strategyType), color = TextSecondary, fontSize = 12.sp)
    }
}
