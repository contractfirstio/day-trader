package daytrader.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import daytrader.domain.TouchTurnClosePositionTriggerMode
import daytrader.domain.TouchTurnRuleCategory
import daytrader.domain.TouchTurnRuleConfig
import daytrader.domain.TouchTurnRuleFieldDefinition
import daytrader.domain.TouchTurnRuleFieldKind
import daytrader.domain.TouchTurnRuleFieldSubGroup
import daytrader.domain.TouchTurnRuleToggleDefinition
import daytrader.ui.theme.BrandRed
import daytrader.ui.theme.DarkBackground
import daytrader.ui.theme.GainGreen
import daytrader.ui.theme.LossRed
import daytrader.ui.theme.SurfaceDark
import daytrader.ui.theme.TableHeaderBg
import daytrader.ui.theme.TextSecondary

private val CompactFieldWidth = 92.dp

@Composable
fun TouchTurnRulesConfigDialog(
    initialRules: TouchTurnRuleConfig,
    enabled: Boolean,
    onDismiss: () -> Unit,
    onSave: (TouchTurnRuleConfig) -> Unit
) {
    var saveError by remember { mutableStateOf<String?>(null) }
    val fieldValues = remember(initialRules) {
        mutableStateMapOf<String, String>().apply {
            TouchTurnRuleConfig.fieldDefinitions.forEach { field ->
                put(field.key, TouchTurnRuleConfig.valueForField(initialRules, field.key))
            }
        }
    }
    val toggleValues = remember(initialRules) {
        mutableStateMapOf<String, Boolean>().apply {
            TouchTurnRuleConfig.toggleDefinitions.forEach { toggle ->
                put(toggle.key, TouchTurnRuleConfig.isToggleEnabled(initialRules, toggle.key))
            }
        }
    }

    val scrollState = rememberScrollState()

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            color = SurfaceDark,
            modifier = Modifier
                .fillMaxWidth(0.94f)
                .fillMaxHeight(0.88f)
                .testTag("TouchTurnRulesConfigDialog")
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 14.dp, vertical = 12.dp)
            ) {
                Text(
                    "Touch Turn rules",
                    color = Color.White,
                    fontWeight = FontWeight.Bold,
                    fontSize = 16.sp
                )
                Spacer(modifier = Modifier.height(8.dp))
                val invertSelected = toggleValues["invertTradeSide"] == true
                val categoryColumns = TouchTurnRuleCategory.entries.splitIntoColumns(columnCount = 2)
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .verticalScroll(scrollState)
                        .testTag("TouchTurnRulesConfigScroll")
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.Top
                    ) {
                        categoryColumns.forEach { columnCategories ->
                            Column(
                                modifier = Modifier.weight(1f),
                                verticalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                columnCategories.forEach { category ->
                                    TouchTurnRuleCategorySection(
                                        category = category,
                                        invertSelected = invertSelected,
                                        toggleValues = toggleValues,
                                        fieldValues = fieldValues,
                                        enabled = enabled,
                                        onToggleChange = { key, checked -> toggleValues[key] = checked },
                                        onFieldChange = { key, value -> fieldValues[key] = value }
                                    )
                                }
                            }
                        }
                    }
                }
                Spacer(modifier = Modifier.height(8.dp))
                saveError?.let { message ->
                    Text(
                        message,
                        color = LossRed,
                        fontSize = 11.sp,
                        lineHeight = 14.sp,
                        modifier = Modifier.testTag("TouchTurnRulesSaveError")
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    OutlinedButton(
                        onClick = {
                            TouchTurnRuleConfig.fieldDefinitions
                                .filter { it.defaultable }
                                .forEach { field ->
                                    fieldValues[field.key] =
                                        TouchTurnRuleConfig.valueForField(TouchTurnRuleConfig.DEFAULT, field.key)
                                }
                            TouchTurnRuleConfig.toggleDefinitions.forEach { toggle ->
                                toggleValues[toggle.key] =
                                    TouchTurnRuleConfig.isToggleEnabled(TouchTurnRuleConfig.DEFAULT, toggle.key)
                            }
                        },
                        enabled = enabled
                    ) {
                        Text("Reset defaults", fontSize = 12.sp, color = if (enabled) Color.White else TextSecondary)
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        TextButton(onClick = onDismiss) {
                            Text("Cancel", fontSize = 12.sp, color = TextSecondary)
                        }
                        TextButton(
                            onClick = {
                                saveError = null
                                var updated = initialRules
                                for (toggle in TouchTurnRuleConfig.toggleDefinitions) {
                                    if (toggle.key == "fiveMinuteConfirmation" &&
                                        (toggleValues["invertTradeSide"] == true)
                                    ) {
                                        continue
                                    }
                                    val checked = toggleValues[toggle.key] ?: true
                                    updated = TouchTurnRuleConfig.withToggleEnabled(updated, toggle.key, checked)
                                }
                                val invertTradeSide = toggleValues["invertTradeSide"] == true
                                for (field in TouchTurnRuleConfig.visibleFieldDefinitions(
                                    invertTradeSide,
                                    toggleValues
                                )) {
                                    val raw = fieldValues[field.key].orEmpty()
                                    val next = TouchTurnRuleConfig.withFieldValue(updated, field.key, raw)
                                    if (next == null) {
                                        saveError = "Invalid value for \"${field.label}\"."
                                        return@TextButton
                                    }
                                    updated = next
                                }
                                onSave(updated)
                            },
                            enabled = enabled,
                            modifier = Modifier.testTag("TouchTurnRulesSaveButton")
                        ) {
                            Text("Save", fontSize = 12.sp, color = if (enabled) Color.White else TextSecondary)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun TouchTurnRuleCategorySection(
    category: TouchTurnRuleCategory,
    invertSelected: Boolean,
    toggleValues: Map<String, Boolean>,
    fieldValues: Map<String, String>,
    enabled: Boolean,
    onToggleChange: (String, Boolean) -> Unit,
    onFieldChange: (String, String) -> Unit
) {
    var expanded by rememberSaveable(category.name) {
        mutableStateOf(category == TouchTurnRuleCategory.TRIGGERS)
    }
    val categoryToggles = TouchTurnRuleConfig.togglesForCategory(category)
    val primaryToggle = TouchTurnRuleConfig.toggleForCategory(category)
    val invertTradeSide = toggleValues["invertTradeSide"] == true
    val fieldGroups = TouchTurnRuleConfig.fieldGroupsForCategory(
        category = category,
        invertTradeSide = invertTradeSide,
        toggleValues = toggleValues
    )

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(DarkBackground, RoundedCornerShape(6.dp))
            .border(1.dp, TableHeaderBg, RoundedCornerShape(6.dp))
            .testTag("TouchTurnRuleCategory-${category.name}")
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { expanded = !expanded }
                .padding(horizontal = 8.dp, vertical = 5.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                category.label,
                fontSize = 11.sp,
                fontWeight = FontWeight.SemiBold,
                color = BrandRed,
                modifier = Modifier.weight(1f)
            )
            Text(
                text = if (expanded) "▾" else "▸",
                fontSize = 9.sp,
                color = TextSecondary
            )
        }
        if (expanded) {
            Column(
                modifier = Modifier.padding(start = 8.dp, end = 8.dp, bottom = 6.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                when (category) {
                    TouchTurnRuleCategory.TRIGGERS -> {
                        val openingBarTimingGroup = fieldGroups.firstOrNull {
                            it.testTagSuffix == TouchTurnRuleFieldSubGroup.BAR_TIMING.name.lowercase()
                        }
                        TouchTurnRuleToggleGroupPanel(
                            label = "15-minute opening bar",
                            description = "Evaluated when the first 15-minute RTH candle closes. Range, color, " +
                                "and close position (cp) triggers decide whether brackets are placed, skipped, " +
                                "or invert is switched to Touch Turn for this session.",
                            testTagSuffix = "opening_bar_15m"
                        ) {
                            categoryToggles.filter { it.key == "liquidityRangeDailyAtr" }.forEach { toggle ->
                                val toggleEnabled = toggleValues[toggle.key] ?: true
                                TouchTurnRuleToggleRow(
                                    toggle = toggle,
                                    checked = toggleEnabled,
                                    enabled = enabled,
                                    onCheckedChange = { onToggleChange(toggle.key, it) }
                                )
                                if (toggleEnabled) {
                                    fieldGroups.firstOrNull {
                                        it.testTagSuffix ==
                                            TouchTurnRuleFieldSubGroup.LIQUIDITY_THRESHOLD.name.lowercase()
                                    }?.let { group ->
                                        TouchTurnRuleFieldSubGroupPanel(
                                            label = group.label,
                                            fields = group.fields,
                                            fieldValues = fieldValues,
                                            enabled = enabled,
                                            testTagSuffix = group.testTagSuffix,
                                            onFieldChange = onFieldChange,
                                            nested = true
                                        )
                                    }
                                }
                            }
                            TouchTurnOpeningBarColorTriggersPanel(
                                enabled = enabled,
                                fieldValues = fieldValues,
                                onFieldChange = onFieldChange
                            )
                            categoryToggles.filter { it.key == "closePositionGate" }.forEach { toggle ->
                                val toggleEnabled = toggleValues[toggle.key] ?: false
                                TouchTurnRuleToggleRow(
                                    toggle = toggle,
                                    checked = toggleEnabled,
                                    enabled = enabled,
                                    onCheckedChange = { onToggleChange(toggle.key, it) }
                                )
                                if (toggleEnabled) {
                                    TouchTurnClosePositionTriggersPanel(
                                        enabled = enabled,
                                        fieldValues = fieldValues,
                                        onFieldChange = onFieldChange
                                    )
                                }
                            }
                            openingBarTimingGroup?.let { group ->
                                TouchTurnRuleFieldSubGroupPanel(
                                    label = group.label,
                                    fields = group.fields,
                                    fieldValues = fieldValues,
                                    enabled = enabled,
                                    testTagSuffix = group.testTagSuffix,
                                    onFieldChange = onFieldChange,
                                    nested = true
                                )
                            }
                        }
                        categoryToggles.filter { it.key == "fiveMinuteConfirmation" }.forEach { toggle ->
                            TouchTurnRuleToggleGroupPanel(
                                label = "5-minute confirmation",
                                testTagSuffix = "five_minute_confirmation"
                            ) {
                                TouchTurnRuleToggleRow(
                                    toggle = toggle,
                                    checked = toggleValues[toggle.key] ?: true,
                                    enabled = enabled && !invertSelected,
                                    onCheckedChange = { onToggleChange(toggle.key, it) }
                                )
                                if (invertSelected) {
                                    Text(
                                        "Unavailable while continuation (invert) mode is on.",
                                        fontSize = 9.sp,
                                        color = TextSecondary,
                                        lineHeight = 12.sp,
                                        modifier = Modifier.testTag("TouchTurnFiveMinuteConfirmationUnavailableHint")
                                    )
                                }
                            }
                        }
                        fieldGroups.filter {
                            val suffix = it.testTagSuffix
                            suffix != TouchTurnRuleFieldSubGroup.LIQUIDITY_THRESHOLD.name.lowercase() &&
                                suffix != TouchTurnRuleFieldSubGroup.BAR_TIMING.name.lowercase() &&
                                suffix != TouchTurnRuleFieldSubGroup.OPENING_BAR_CLOSE_POSITION.name.lowercase()
                        }.forEach { group ->
                            TouchTurnRuleFieldSubGroupPanel(
                                label = group.label,
                                fields = group.fields,
                                fieldValues = fieldValues,
                                enabled = enabled,
                                testTagSuffix = group.testTagSuffix,
                                onFieldChange = onFieldChange
                            )
                        }
                    }
                    TouchTurnRuleCategory.EXECUTION -> {
                        fieldGroups.firstOrNull {
                            it.testTagSuffix == TouchTurnRuleFieldSubGroup.REVERSAL_ENTRY.name.lowercase()
                        }?.let { group ->
                            TouchTurnRuleFieldSubGroupPanel(
                                label = group.label,
                                fields = group.fields,
                                fieldValues = fieldValues,
                                enabled = enabled,
                                testTagSuffix = group.testTagSuffix,
                                onFieldChange = onFieldChange
                            )
                        }
                        categoryToggles.filter { it.key == "invertTradeSide" }.forEach { toggle ->
                            TouchTurnRuleToggleRow(
                                toggle = toggle,
                                checked = toggleValues[toggle.key] ?: true,
                                enabled = enabled,
                                onCheckedChange = { onToggleChange(toggle.key, it) }
                            )
                        }
                        fieldGroups.firstOrNull {
                            it.testTagSuffix == TouchTurnRuleFieldSubGroup.INVERT_ENTRY.name.lowercase()
                        }?.let { group ->
                            TouchTurnRuleFieldSubGroupPanel(
                                label = group.label,
                                fields = group.fields,
                                fieldValues = fieldValues,
                                enabled = enabled,
                                testTagSuffix = group.testTagSuffix,
                                onFieldChange = onFieldChange
                            )
                        }
                        fieldGroups.firstOrNull {
                            it.testTagSuffix == TouchTurnRuleFieldSubGroup.TAKE_PROFIT_AND_RISK.name.lowercase()
                        }?.let { group ->
                            TouchTurnRuleFieldSubGroupPanel(
                                label = group.label,
                                fields = group.fields,
                                fieldValues = fieldValues,
                                enabled = enabled,
                                testTagSuffix = group.testTagSuffix,
                                onFieldChange = onFieldChange
                            )
                        }
                    }
                    else -> {
                        primaryToggle?.let { definition ->
                            TouchTurnRuleToggleRow(
                                toggle = definition,
                                checked = toggleValues[definition.key] ?: true,
                                enabled = enabled,
                                onCheckedChange = { onToggleChange(definition.key, it) }
                            )
                        }
                        if (category.fieldsAlwaysVisible || primaryToggle != null) {
                            fieldGroups.forEach { group ->
                                TouchTurnRuleFieldSubGroupPanel(
                                    label = group.label,
                                    fields = group.fields,
                                    fieldValues = fieldValues,
                                    enabled = enabled,
                                    testTagSuffix = group.testTagSuffix,
                                    onFieldChange = onFieldChange
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun TouchTurnRuleToggleGroupPanel(
    label: String,
    testTagSuffix: String,
    description: String? = null,
    content: @Composable () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 2.dp)
            .background(DarkBackground.copy(alpha = 0.55f), RoundedCornerShape(4.dp))
            .border(1.dp, BrandRed.copy(alpha = 0.35f), RoundedCornerShape(4.dp))
            .padding(horizontal = 6.dp, vertical = 5.dp)
            .testTag("TouchTurnRuleToggleGroup-$testTagSuffix"),
        verticalArrangement = Arrangement.spacedBy(3.dp)
    ) {
        Text(
            label,
            fontSize = 10.sp,
            fontWeight = FontWeight.SemiBold,
            color = BrandRed,
            modifier = Modifier.testTag("TouchTurnRuleToggleGroupLabel-$testTagSuffix")
        )
        description?.let { text ->
            Text(
                text,
                fontSize = 9.sp,
                color = TextSecondary,
                lineHeight = 12.sp,
                modifier = Modifier.testTag("TouchTurnRuleToggleGroupDescription-$testTagSuffix")
            )
        }
        content()
    }
}

@Composable
private fun TouchTurnRuleFieldSubGroupPanel(
    label: String,
    fields: List<TouchTurnRuleFieldDefinition>,
    fieldValues: Map<String, String>,
    enabled: Boolean,
    testTagSuffix: String,
    onFieldChange: (String, String) -> Unit,
    nested: Boolean = false
) {
    if (fields.isEmpty()) return
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = if (nested) 0.dp else 2.dp)
            .then(
                if (nested) {
                    Modifier.padding(start = 4.dp)
                } else {
                    Modifier
                        .background(DarkBackground.copy(alpha = 0.55f), RoundedCornerShape(4.dp))
                        .border(1.dp, BrandRed.copy(alpha = 0.35f), RoundedCornerShape(4.dp))
                        .padding(horizontal = 6.dp, vertical = 5.dp)
                }
            )
            .testTag("TouchTurnRuleFieldSubGroup-$testTagSuffix"),
        verticalArrangement = Arrangement.spacedBy(3.dp)
    ) {
        Text(
            label,
            fontSize = if (nested) 9.sp else 10.sp,
            fontWeight = FontWeight.SemiBold,
            color = if (nested) TextSecondary else BrandRed,
            modifier = Modifier.testTag("TouchTurnRuleFieldSubGroupLabel-$testTagSuffix")
        )
        fields.forEach { field ->
            TouchTurnRuleFieldEditor(
                field = field,
                value = fieldValues[field.key].orEmpty(),
                enabled = enabled,
                onValueChange = { onFieldChange(field.key, it) }
            )
        }
    }
}

@Composable
private fun TouchTurnRuleToggleRow(
    toggle: TouchTurnRuleToggleDefinition,
    checked: Boolean,
    enabled: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("TouchTurnRuleToggle-${toggle.key}"),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            toggle.label,
            fontSize = 11.sp,
            color = Color.White,
            fontWeight = FontWeight.Medium,
            modifier = Modifier
                .weight(1f)
                .padding(end = 8.dp)
        )
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            enabled = enabled,
            modifier = Modifier.scale(0.82f),
            colors = SwitchDefaults.colors(
                checkedThumbColor = Color.White,
                checkedTrackColor = GainGreen,
                uncheckedThumbColor = Color.White,
                uncheckedTrackColor = BrandRed,
                uncheckedBorderColor = LossRed,
                disabledCheckedThumbColor = Color.White.copy(alpha = 0.5f),
                disabledCheckedTrackColor = GainGreen.copy(alpha = 0.4f),
                disabledUncheckedThumbColor = Color.White.copy(alpha = 0.5f),
                disabledUncheckedTrackColor = BrandRed.copy(alpha = 0.4f)
            )
        )
    }
}

@Composable
private fun TouchTurnRuleFieldEditor(
    field: TouchTurnRuleFieldDefinition,
    value: String,
    enabled: Boolean,
    onValueChange: (String) -> Unit
) {
    if (field.kind == TouchTurnRuleFieldKind.CLOSE_POSITION_TRIGGER_MODE) {
        TouchTurnClosePositionTriggerModeEditor(
            field = field,
            value = value,
            enabled = enabled,
            onValueChange = onValueChange
        )
        return
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("TouchTurnRuleFieldGroup-${field.key}"),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            field.label,
            fontSize = 11.sp,
            color = Color.White,
            modifier = Modifier.weight(1f)
        )
        if (!field.defaultable) {
            Text(
                "Req",
                fontSize = 8.sp,
                color = TextSecondary,
                modifier = Modifier.testTag("TouchTurnRuleFieldRequired-${field.key}")
            )
        }
        OutlinedTextField(
            value = value,
            onValueChange = onValueChange,
            enabled = enabled,
            singleLine = true,
            modifier = Modifier
                .width(CompactFieldWidth)
                .testTag("TouchTurnRuleField-${field.key}"),
            textStyle = androidx.compose.ui.text.TextStyle(fontSize = 12.sp),
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
            placeholder = {
                Text(
                    when (field.kind) {
                        TouchTurnRuleFieldKind.RATIO -> "0.25"
                        TouchTurnRuleFieldKind.OPTIONAL_RATIO -> "0.60"
                        TouchTurnRuleFieldKind.PRICE -> "0"
                        TouchTurnRuleFieldKind.INTEGER -> "14"
                        TouchTurnRuleFieldKind.MILLISECONDS -> "3000"
                        TouchTurnRuleFieldKind.CLOSE_POSITION_TRIGGER_MODE -> "OFF"
                    },
                    color = TextSecondary,
                    fontSize = 11.sp
                )
            }
        )
    }
}

@Composable
private fun TouchTurnOpeningBarColorTriggersPanel(
    enabled: Boolean,
    fieldValues: Map<String, String>,
    onFieldChange: (String, String) -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 8.dp, top = 4.dp)
            .testTag("TouchTurnOpeningBarColorTriggersPanel"),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text(
            "Bar color triggers",
            fontSize = 9.sp,
            fontWeight = FontWeight.SemiBold,
            color = TextSecondary
        )
        listOf(
            "Green liquidity bar" to "greenLiquidityBarAction",
            "Red liquidity bar" to "redLiquidityBarAction"
        ).forEach { (label, actionKey) ->
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("TouchTurnColorTrigger-$actionKey"),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Text(label, fontSize = 11.sp, color = Color.White)
                TouchTurnClosePositionTriggerModeChips(
                    actionKey = actionKey,
                    value = fieldValues[actionKey].orEmpty(),
                    enabled = enabled,
                    onValueChange = { onFieldChange(actionKey, it) }
                )
            }
        }
    }
}

@Composable
private fun TouchTurnClosePositionTriggersPanel(
    enabled: Boolean,
    fieldValues: Map<String, String>,
    onFieldChange: (String, String) -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 8.dp, top = 4.dp)
            .testTag("TouchTurnClosePositionTriggersPanel"),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text(
            "Close position (cp) triggers",
            fontSize = 9.sp,
            fontWeight = FontWeight.SemiBold,
            color = TextSecondary
        )
        listOf(
            Triple("Green bar — cp at or below", "greenSkipClosePositionBelow", "greenClosePositionBelowAction"),
            Triple("Green bar — cp at or above", "greenSkipClosePositionAbove", "greenClosePositionAboveAction"),
            Triple("Red bar — cp at or below", "redSkipClosePositionBelow", "redClosePositionBelowAction"),
            Triple("Red bar — cp at or above", "redSkipClosePositionAbove", "redClosePositionAboveAction")
        ).forEach { (label, thresholdKey, actionKey) ->
            TouchTurnClosePositionTriggerRow(
                label = label,
                thresholdKey = thresholdKey,
                actionKey = actionKey,
                thresholdValue = fieldValues[thresholdKey].orEmpty(),
                actionValue = fieldValues[actionKey].orEmpty(),
                enabled = enabled,
                onThresholdChange = { onFieldChange(thresholdKey, it) },
                onActionChange = { onFieldChange(actionKey, it) }
            )
        }
    }
}

@Composable
private fun TouchTurnClosePositionTriggerRow(
    label: String,
    thresholdKey: String,
    actionKey: String,
    thresholdValue: String,
    actionValue: String,
    enabled: Boolean,
    onThresholdChange: (String) -> Unit,
    onActionChange: (String) -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("TouchTurnClosePositionTrigger-$thresholdKey"),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(label, fontSize = 11.sp, color = Color.White, modifier = Modifier.weight(1f))
            OutlinedTextField(
                value = thresholdValue,
                onValueChange = onThresholdChange,
                enabled = enabled,
                singleLine = true,
                modifier = Modifier
                    .width(CompactFieldWidth)
                    .testTag("TouchTurnRuleField-$thresholdKey"),
                textStyle = androidx.compose.ui.text.TextStyle(fontSize = 12.sp),
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
                placeholder = {
                    Text("0.15", color = TextSecondary, fontSize = 11.sp)
                }
            )
        }
        TouchTurnClosePositionTriggerModeChips(
            actionKey = actionKey,
            value = actionValue,
            enabled = enabled,
            onValueChange = onActionChange
        )
    }
}

@Composable
private fun TouchTurnClosePositionTriggerModeChips(
    actionKey: String,
    value: String,
    enabled: Boolean,
    onValueChange: (String) -> Unit
) {
    val selected = runCatching { TouchTurnClosePositionTriggerMode.valueOf(value) }
        .getOrDefault(TouchTurnClosePositionTriggerMode.OFF)
    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        TouchTurnClosePositionTriggerMode.entries.forEach { mode ->
            val active = mode == selected
            OutlinedButton(
                onClick = { onValueChange(mode.name) },
                enabled = enabled,
                modifier = Modifier.testTag("TouchTurnRuleField-$actionKey-${mode.name}"),
                colors = ButtonDefaults.outlinedButtonColors(
                    containerColor = if (active) TableHeaderBg else Color.Transparent,
                    contentColor = if (active) Color.White else TextSecondary
                ),
                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp)
            ) {
                Text(
                    when (mode) {
                        TouchTurnClosePositionTriggerMode.OFF -> "Off"
                        TouchTurnClosePositionTriggerMode.SKIP -> "Skip"
                        TouchTurnClosePositionTriggerMode.SWITCH_TO_TOUCH_TURN -> "Flip invert"
                    },
                    fontSize = 10.sp
                )
            }
        }
    }
}

@Composable
private fun TouchTurnClosePositionTriggerModeEditor(
    field: TouchTurnRuleFieldDefinition,
    value: String,
    enabled: Boolean,
    onValueChange: (String) -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("TouchTurnRuleFieldGroup-${field.key}"),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Text(field.label, fontSize = 11.sp, color = Color.White)
        TouchTurnClosePositionTriggerModeChips(
            actionKey = field.key,
            value = value,
            enabled = enabled,
            onValueChange = onValueChange
        )
    }
}

/** Split in decision-flow order: 4 categories → 2 per column. */
private fun <T> List<T>.splitIntoColumns(columnCount: Int): List<List<T>> {
    if (isEmpty() || columnCount <= 0) return emptyList()
    val columns = List(columnCount) { mutableListOf<T>() }
    val baseSize = size / columnCount
    val extraColumns = size % columnCount
    var start = 0
    for (columnIndex in 0 until columnCount) {
        val chunkSize = baseSize + if (columnIndex < extraColumns) 1 else 0
        columns[columnIndex].addAll(drop(start).take(chunkSize))
        start += chunkSize
    }
    return columns
}
