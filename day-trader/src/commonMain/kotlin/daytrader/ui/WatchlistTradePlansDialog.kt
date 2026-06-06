package daytrader.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
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
import daytrader.domain.TradeSide
import daytrader.domain.WatchlistLabels
import daytrader.presentation.watchlist.WatchlistLabelUi
import daytrader.presentation.watchlist.WatchlistPlanEditorUi
import daytrader.presentation.watchlist.WatchlistPlanField
import daytrader.presentation.watchlist.WatchlistPlanOutcomeUi
import daytrader.presentation.watchlist.WatchlistTradePlansEditorUi
import daytrader.ui.theme.*
import kotlinx.coroutines.delay

@Composable
internal fun WatchlistTradePlansDialog(
    editor: WatchlistTradePlansEditorUi,
    onDismiss: () -> Unit,
    onSave: () -> Unit,
    onSideChange: (String, TradeSide) -> Unit,
    onSizingModeChange: (String, PlanSizingMode) -> Unit,
    onProximityEnabledChange: (String, Boolean) -> Unit,
    onProximityModeChange: (String, ProximityThresholdMode) -> Unit,
    onFieldChange: (String, WatchlistPlanField, String) -> Unit,
    onGroupInputChange: (String) -> Unit,
    onAddGroup: (String?) -> Unit,
    onRemoveGroup: (String) -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        modifier = Modifier
            .fillMaxWidth(0.92f)
            .testTag("WatchlistTradePlansDialog"),
        containerColor = SurfaceDark,
        title = {
            Column {
                Text(
                    "${editor.symbol} — Trade plans",
                    color = Color.White,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    "${editor.companyName} · Last scanned ${editor.formattedLast}",
                    color = TextSecondary,
                    fontSize = 13.sp
                )
            }
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 560.dp)
                    .verticalScroll(rememberScrollState()),
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
                editor.plans.forEach { plan ->
                    TradePlanCard(
                        plan = plan,
                        onSideChange = { onSideChange(plan.planId, it) },
                        onSizingModeChange = { onSizingModeChange(plan.planId, it) },
                        onProximityEnabledChange = { onProximityEnabledChange(plan.planId, it) },
                        onProximityModeChange = { onProximityModeChange(plan.planId, it) },
                        onFieldChange = { field, value -> onFieldChange(plan.planId, field, value) }
                    )
                }
            }
        },
        confirmButton = {
            Button(
                onClick = onSave,
                colors = ButtonDefaults.buttonColors(containerColor = BrandRed),
                modifier = Modifier.testTag("SaveWatchlistTradePlansButton")
            ) {
                Text("Save", color = Color.White)
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
    onSideChange: (TradeSide) -> Unit,
    onSizingModeChange: (PlanSizingMode) -> Unit,
    onProximityEnabledChange: (Boolean) -> Unit,
    onProximityModeChange: (ProximityThresholdMode) -> Unit,
    onFieldChange: (WatchlistPlanField, String) -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .border(1.dp, TableHeaderBg, RoundedCornerShape(8.dp))
            .background(DarkBackground, RoundedCornerShape(8.dp))
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Text(plan.label, color = Color.White, fontWeight = FontWeight.SemiBold, fontSize = 15.sp)

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
