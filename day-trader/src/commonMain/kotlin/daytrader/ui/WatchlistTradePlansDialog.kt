package daytrader.ui

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import daytrader.domain.PlanSizingMode
import daytrader.domain.ProximityThresholdMode
import daytrader.domain.StrategyType
import daytrader.domain.TradeSide
import daytrader.domain.WatchlistLabels
import daytrader.presentation.watchlist.WatchlistEntryChartsUi
import daytrader.presentation.watchlist.WatchlistLabelUi
import daytrader.presentation.watchlist.WatchlistStrategyUi
import daytrader.presentation.watchlist.WatchlistPlanEditorUi
import daytrader.presentation.watchlist.WatchlistPlanField
import daytrader.presentation.watchlist.WatchlistPlanOutcomeUi
import daytrader.presentation.watchlist.WatchlistTradePlansEditorUi
import daytrader.ui.theme.*
import kotlinx.coroutines.delay

@Composable
internal fun WatchlistEntryDetailPanel(
    editor: WatchlistTradePlansEditorUi,
    charts: WatchlistEntryChartsUi?,
    onBack: () -> Unit,
    onSave: () -> Unit,
    onSideChange: (String, TradeSide) -> Unit,
    onSizingModeChange: (String, PlanSizingMode) -> Unit,
    onProximityEnabledChange: (String, Boolean) -> Unit,
    onProximityModeChange: (String, ProximityThresholdMode) -> Unit,
    onFieldChange: (String, WatchlistPlanField, String) -> Unit,
    onGroupInputChange: (String) -> Unit,
    onAddGroup: (String?) -> Unit,
    onRemoveGroup: (String) -> Unit,
    onCreateStrategyDeployment: (StrategyType) -> Unit,
    onRemoveStrategy: (String) -> Unit,
    onPlaceBracket: (String) -> Unit,
    onReactivatePlan: (String) -> Unit,
    onOpenDiary: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .testTag("WatchlistEntryDetailPanel")
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            IconButton(
                onClick = onBack,
                modifier = Modifier.testTag("WatchlistEntryDetailBack")
            ) {
                Icon(
                    Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = "Back to watchlist",
                    tint = Color.White
                )
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    "${editor.symbol} — Trade plans",
                    color = Color.White,
                    fontWeight = FontWeight.Bold,
                    fontSize = 18.sp
                )
                Text(
                    "${editor.companyName} · Last scanned ${editor.formattedLast}",
                    color = TextSecondary,
                    fontSize = 13.sp
                )
            }
            Button(
                onClick = onSave,
                colors = ButtonDefaults.buttonColors(containerColor = BrandRed),
                modifier = Modifier.testTag("SaveWatchlistTradePlansButton")
            ) {
                Text("Save", color = Color.White)
            }
        }

        HorizontalSplitPane(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth(),
            initialLeftFraction = 0.48f,
            minLeftFraction = 0.32f,
            maxLeftFraction = 0.68f,
            leftContent = {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .border(1.dp, SurfaceDark, RoundedCornerShape(8.dp))
                        .background(SurfaceDark, RoundedCornerShape(8.dp))
                        .verticalScroll(rememberScrollState())
                        .padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    WatchlistGroupsEditor(
                        assignedLabels = editor.assignedLabels,
                        availableLabels = editor.availableLabels,
                        newGroupInput = editor.newGroupInput,
                        onGroupInputChange = onGroupInputChange,
                        onAddGroup = { onAddGroup(null) },
                        onQuickAddGroup = { onAddGroup(it) },
                        onRemoveGroup = onRemoveGroup
                    )
                    WatchlistStrategiesEditor(
                        symbol = editor.symbol,
                        assignedStrategies = editor.assignedStrategies,
                        onCreateStrategyDeployment = onCreateStrategyDeployment,
                        onRemoveStrategy = onRemoveStrategy
                    )
                    editor.plans.forEach { plan ->
                        TradePlanCard(
                            plan = plan,
                            isNearEntry = plan.isNearEntry,
                            canPlaceBracket = plan.orderPlacedLabel == null &&
                                plan.outcome?.let { it.errors.isEmpty() && it.quantityLabel != null } == true,
                            onPlaceBracket = { onPlaceBracket(plan.planId) },
                            onSideChange = { onSideChange(plan.planId, it) },
                            onSizingModeChange = { onSizingModeChange(plan.planId, it) },
                            onProximityEnabledChange = { onProximityEnabledChange(plan.planId, it) },
                            onProximityModeChange = { onProximityModeChange(plan.planId, it) },
                            onFieldChange = { field, value -> onFieldChange(plan.planId, field, value) },
                            onReactivatePlan = { onReactivatePlan(plan.planId) },
                            onOpenDiary = { onOpenDiary(plan.planId) }
                        )
                    }
                }
            },
            rightContent = {
                charts?.let { chartState ->
                    WatchlistEntryChartsPanel(
                        charts = chartState,
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(start = 4.dp)
                    )
                }
            }
        )
    }
}

@Composable
private fun WatchlistStrategiesEditor(
    symbol: String,
    assignedStrategies: List<WatchlistStrategyUi>,
    onCreateStrategyDeployment: (StrategyType) -> Unit,
    onRemoveStrategy: (String) -> Unit
) {
    val assignedTypes = assignedStrategies.map { it.strategyType }.toSet()
    val linkableTypes = StrategyType.entries.filter { it !in assignedTypes }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .border(1.dp, TableHeaderBg, RoundedCornerShape(8.dp))
            .background(DarkBackground, RoundedCornerShape(8.dp))
            .padding(12.dp)
            .testTag("WatchlistStrategiesEditor"),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Text("Strategies", color = Color.White, fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
        Text(
            "Pick a strategy to deploy for $symbol. Symbol and market are filled in automatically. Removing a link deletes that deployment.",
            color = TextSecondary,
            fontSize = 12.sp,
            lineHeight = 16.sp
        )

        if (assignedStrategies.isNotEmpty()) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                assignedStrategies.forEach { strategy ->
                    AssignedStrategyChip(
                        strategy = strategy,
                        onRemove = { onRemoveStrategy(strategy.deploymentId) }
                    )
                }
            }
        }

        when {
            linkableTypes.isEmpty() -> {
                Text(
                    "All available strategies are linked to this symbol.",
                    color = TextSecondary,
                    fontSize = 12.sp,
                    lineHeight = 16.sp
                )
            }
            else -> {
                Text("Link strategy", color = TextSecondary, fontSize = 12.sp, fontWeight = FontWeight.Medium)
                linkableTypes.forEach { strategyType ->
                    StrategyTypePickerCard(
                        strategyType = strategyType,
                        selected = false,
                        onSelect = { onCreateStrategyDeployment(strategyType) },
                        modifier = Modifier.testTag("LinkWatchlistStrategy-${strategyType.name}")
                    )
                }
            }
        }
    }
}

@Composable
private fun AssignedStrategyChip(strategy: WatchlistStrategyUi, onRemove: () -> Unit) {
    Row(
        modifier = Modifier
            .background(GainGreen.copy(alpha = 0.18f), RoundedCornerShape(10.dp))
            .border(1.dp, GainGreen.copy(alpha = 0.45f), RoundedCornerShape(10.dp))
            .padding(start = 8.dp, end = 2.dp, top = 2.dp, bottom = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(2.dp)
    ) {
        Text(strategy.label, color = Color.White, fontSize = 12.sp, maxLines = 1)
        IconButton(onClick = onRemove, modifier = Modifier.size(20.dp)) {
            Icon(Icons.Default.Close, contentDescription = "Remove strategy", tint = TextSecondary, modifier = Modifier.size(14.dp))
        }
    }
}

@Composable
private fun WatchlistGroupsEditor(
    assignedLabels: List<WatchlistLabelUi>,
    availableLabels: List<WatchlistLabelUi>,
    newGroupInput: String,
    onGroupInputChange: (String) -> Unit,
    onAddGroup: () -> Unit,
    onQuickAddGroup: (String) -> Unit,
    onRemoveGroup: (String) -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .border(1.dp, TableHeaderBg, RoundedCornerShape(8.dp))
            .background(DarkBackground, RoundedCornerShape(8.dp))
            .padding(12.dp)
            .testTag("WatchlistGroupsEditor"),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Text("Groups", color = Color.White, fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
        Text(
            "Pick an existing group from the list as you type to avoid typos.",
            color = TextSecondary,
            fontSize = 12.sp,
            lineHeight = 16.sp
        )

        if (assignedLabels.isNotEmpty()) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                assignedLabels.forEach { label ->
                    AssignedGroupChip(label = label, onRemove = { onRemoveGroup(label.id) })
                }
            }
        }

        GroupAutocompleteField(
            value = newGroupInput,
            availableLabels = availableLabels,
            onValueChange = onGroupInputChange,
            onSuggestionSelected = onQuickAddGroup,
            onAdd = onAddGroup
        )
    }
}

@Composable
private fun GroupAutocompleteField(
    value: String,
    availableLabels: List<WatchlistLabelUi>,
    onValueChange: (String) -> Unit,
    onSuggestionSelected: (String) -> Unit,
    onAdd: () -> Unit
) {
    var inputFocused by remember { mutableStateOf(false) }
    var suggestionsVisible by remember { mutableStateOf(false) }
    val suggestions = remember(availableLabels, value) {
        WatchlistLabels.filterSuggestions(
            candidates = availableLabels.map { daytrader.domain.WatchlistLabel(it.id, it.name, 0L) },
            query = value
        ).map { WatchlistLabelUi(it.id, it.name) }
    }

    LaunchedEffect(inputFocused) {
        if (inputFocused) {
            suggestionsVisible = true
        } else {
            delay(250)
            suggestionsVisible = false
        }
    }

    val showSuggestions = suggestionsVisible && suggestions.isNotEmpty()

    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            OutlinedTextField(
                value = value,
                onValueChange = {
                    onValueChange(it)
                    suggestionsVisible = true
                },
                modifier = Modifier
                    .weight(1f)
                    .onFocusChanged { inputFocused = it.isFocused }
                    .testTag("WatchlistGroupInput"),
                singleLine = true,
                placeholder = { Text("Add group", color = TextSecondary, fontSize = 13.sp) },
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { onAdd() }),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedContainerColor = SurfaceDark,
                    unfocusedContainerColor = SurfaceDark,
                    focusedBorderColor = TableHeaderBg,
                    unfocusedBorderColor = TableHeaderBg,
                    focusedTextColor = Color.White,
                    unfocusedTextColor = Color.White
                ),
                shape = RoundedCornerShape(6.dp)
            )
            TextButton(
                onClick = onAdd,
                enabled = value.isNotBlank()
            ) {
                Text("Add", color = if (value.isNotBlank()) BrandRed else TextSecondary)
            }
        }

        if (showSuggestions) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .border(1.dp, TableHeaderBg, RoundedCornerShape(6.dp))
                    .background(SurfaceDark, RoundedCornerShape(6.dp))
                    .testTag("WatchlistGroupSuggestions")
            ) {
                suggestions.forEachIndexed { index, suggestion ->
                    if (index > 0) {
                        HorizontalDivider(color = TableHeaderBg, thickness = 1.dp)
                    }
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                onSuggestionSelected(suggestion.id)
                                suggestionsVisible = false
                            }
                            .padding(horizontal = 12.dp, vertical = 10.dp)
                            .testTag("WatchlistGroupSuggestion-${suggestion.id}")
                    ) {
                        Text(
                            text = suggestion.name,
                            color = Color.White,
                            fontSize = 13.sp
                        )
                    }
                }
            }
        } else if (inputFocused && availableLabels.isEmpty() && value.isBlank()) {
            Text(
                "No existing groups yet — type a name to create one.",
                color = TextSecondary,
                fontSize = 11.sp
            )
        }
    }
}

@Composable
private fun AssignedGroupChip(label: WatchlistLabelUi, onRemove: () -> Unit) {
    Row(
        modifier = Modifier
            .background(BrandRed.copy(alpha = 0.2f), RoundedCornerShape(10.dp))
            .border(1.dp, BrandRed.copy(alpha = 0.5f), RoundedCornerShape(10.dp))
            .padding(start = 8.dp, end = 2.dp, top = 2.dp, bottom = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(2.dp)
    ) {
        Text(label.name, color = Color.White, fontSize = 12.sp)
        IconButton(onClick = onRemove, modifier = Modifier.size(20.dp)) {
            Icon(Icons.Default.Close, contentDescription = "Remove group", tint = TextSecondary, modifier = Modifier.size(14.dp))
        }
    }
}

@Composable
private fun TradePlanCard(
    plan: WatchlistPlanEditorUi,
    isNearEntry: Boolean,
    canPlaceBracket: Boolean,
    onPlaceBracket: () -> Unit,
    onSideChange: (TradeSide) -> Unit,
    onSizingModeChange: (PlanSizingMode) -> Unit,
    onProximityEnabledChange: (Boolean) -> Unit,
    onProximityModeChange: (ProximityThresholdMode) -> Unit,
    onFieldChange: (WatchlistPlanField, String) -> Unit,
    onReactivatePlan: () -> Unit,
    onOpenDiary: () -> Unit
) {
    val cardShape = RoundedCornerShape(8.dp)
    val pulseTransition = rememberInfiniteTransition(label = "watchlistPlanNearEntryPulse")
    val pulseAlpha by pulseTransition.animateFloat(
        initialValue = 0.35f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(animation = tween(750), repeatMode = RepeatMode.Reverse),
        label = "watchlistPlanNearEntryPulseAlpha"
    )
    val borderColor = if (isNearEntry) {
        TradeBlueBorder.copy(alpha = pulseAlpha)
    } else {
        TableHeaderBg
    }
    val borderWidth = if (isNearEntry) 2.dp else 1.dp
    val backgroundColor = if (isNearEntry) TradeBlueSurface.copy(alpha = 0.65f) else DarkBackground

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .border(borderWidth, borderColor, cardShape)
            .background(backgroundColor, cardShape)
            .padding(12.dp)
            .testTag(if (isNearEntry) "WatchlistTradePlanNearEntry-${plan.planId}" else "WatchlistTradePlan-${plan.planId}"),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(plan.label, color = Color.White, fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
            when {
                plan.orderPlacedLabel != null -> {
                    Text(
                        "Order placed",
                        color = GainGreen,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                }
                isNearEntry -> {
                    Text(
                        "Near entry",
                        color = TradeBlueBorder,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }
        }

        plan.orderPlacedLabel?.let { label ->
            Text(label, color = GainGreen, fontSize = 12.sp)
            Text(
                "Clears the link to placed orders so entry alerts can resume. Open orders are not cancelled.",
                color = TextSecondary,
                fontSize = 11.sp,
                lineHeight = 14.sp
            )
            TextButton(
                onClick = onReactivatePlan,
                modifier = Modifier.testTag("ReactivateWatchlistPlan-${plan.planId}")
            ) {
                Text("Reactivate plan", color = BrandRed, fontSize = 13.sp)
            }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(
                selected = plan.side == TradeSide.LONG,
                onClick = { onSideChange(TradeSide.LONG) },
                label = { Text("Long") }
            )
            FilterChip(
                selected = plan.side == TradeSide.SHORT,
                onClick = { onSideChange(TradeSide.SHORT) },
                label = { Text("Short") }
            )
        }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(
                selected = plan.sizingMode == PlanSizingMode.NOTIONAL,
                onClick = { onSizingModeChange(PlanSizingMode.NOTIONAL) },
                label = { Text("Notional") }
            )
            FilterChip(
                selected = plan.sizingMode == PlanSizingMode.RISK_BUDGET,
                onClick = { onSizingModeChange(PlanSizingMode.RISK_BUDGET) },
                label = { Text("Risk budget") }
            )
        }

        ConfigField(
            label = "Entry price",
            value = plan.entryPriceText,
            onValueChange = { onFieldChange(WatchlistPlanField.ENTRY, it) }
        )
        ConfigField(
            label = "Stop price",
            value = plan.stopPriceText,
            onValueChange = { onFieldChange(WatchlistPlanField.STOP, it) }
        )
        ConfigField(
            label = "Target price",
            value = plan.targetPriceText,
            onValueChange = { onFieldChange(WatchlistPlanField.TARGET, it) }
        )
        ConfigField(
            label = if (plan.sizingMode == PlanSizingMode.NOTIONAL) {
                "Investment amount (notional)"
            } else {
                "Risk budget (max loss at stop)"
            },
            value = plan.investmentAmountText,
            onValueChange = { onFieldChange(WatchlistPlanField.INVESTMENT, it) }
        )

        HorizontalDivider(color = TableHeaderBg)

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("Alert when near entry", color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Medium)
            Switch(
                checked = plan.proximityAlertEnabled,
                onCheckedChange = onProximityEnabledChange
            )
        }

        if (plan.proximityAlertEnabled) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(
                    selected = plan.proximityThresholdMode == ProximityThresholdMode.PERCENT,
                    onClick = { onProximityModeChange(ProximityThresholdMode.PERCENT) },
                    label = { Text("Percent") }
                )
                FilterChip(
                    selected = plan.proximityThresholdMode == ProximityThresholdMode.ABSOLUTE,
                    onClick = { onProximityModeChange(ProximityThresholdMode.ABSOLUTE) },
                    label = { Text("Absolute $") }
                )
            }
            ConfigField(
                label = if (plan.proximityThresholdMode == ProximityThresholdMode.PERCENT) {
                    "Threshold (%)"
                } else {
                    "Threshold ($)"
                },
                value = plan.proximityThresholdValueText,
                onValueChange = { onFieldChange(WatchlistPlanField.PROXIMITY_THRESHOLD, it) }
            )
        }

        plan.outcome?.let { outcome ->
            TradePlanOutcomePanel(outcome)
        }

        OutlinedButton(
            onClick = onOpenDiary,
            modifier = Modifier
                .fillMaxWidth()
                .testTag("OpenWatchlistPlanDiary-${plan.planId}"),
            colors = ButtonDefaults.outlinedButtonColors(contentColor = Color.White)
        ) {
            val diaryLabel = buildString {
                append("Diary")
                if (plan.diaryEntryCount > 0) append(" (${plan.diaryEntryCount})")
                if (plan.pendingDiaryReminderCount > 0) {
                    append(" · ${plan.pendingDiaryReminderCount} reminder")
                    if (plan.pendingDiaryReminderCount > 1) append("s")
                }
            }
            Text(diaryLabel, fontSize = 13.sp)
        }

        Button(
            onClick = onPlaceBracket,
            enabled = canPlaceBracket,
            modifier = Modifier
                .fillMaxWidth()
                .testTag("PlaceWatchlistBracket-${plan.planId}"),
            colors = ButtonDefaults.buttonColors(
                containerColor = TradeBlueBorder,
                disabledContainerColor = TableHeaderBg
            )
        ) {
            Text(
                when {
                    plan.orderPlacedLabel != null -> "Order placed for this plan"
                    canPlaceBracket -> "Place bracket order…"
                    else -> "Complete plan to place bracket"
                },
                color = if (canPlaceBracket) Color.White else TextSecondary,
                fontSize = 13.sp
            )
        }
    }
}

@Composable
private fun TradePlanOutcomePanel(outcome: WatchlistPlanOutcomeUi) {
    if (outcome.errors.isNotEmpty()) {
        outcome.errors.forEach { error ->
            Text(error, color = LossRed, fontSize = 12.sp)
        }
        return
    }

    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        outcome.quantityLabel?.let {
            OutcomeLine("Quantity", it)
        }
        outcome.notionalLabel?.let {
            OutcomeLine("Notional at entry", it)
        }
        outcome.profitAtTargetLabel?.let {
            OutcomeLine("Profit at target", it, GainGreen)
        }
        outcome.lossAtStopLabel?.let {
            OutcomeLine("Loss at stop", it, LossRed)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            outcome.rMultipleLabel?.let {
                Text("R: $it", color = TextSecondary, fontSize = 12.sp)
            }
            outcome.returnAtTargetLabel?.let {
                Text("Target return: $it", color = GainGreen, fontSize = 12.sp)
            }
            outcome.returnAtStopLabel?.let {
                Text("Stop return: $it", color = LossRed, fontSize = 12.sp)
            }
        }
    }
}

@Composable
private fun OutcomeLine(label: String, value: String, valueColor: Color = Color.White) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(label, color = TextSecondary, fontSize = 13.sp)
        Text(value, color = valueColor, fontSize = 13.sp, fontWeight = FontWeight.Medium)
    }
}
