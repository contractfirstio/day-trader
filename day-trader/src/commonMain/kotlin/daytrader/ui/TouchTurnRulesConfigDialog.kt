package daytrader.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import daytrader.domain.TouchTurnRuleCategory
import daytrader.domain.TouchTurnRuleConfig
import daytrader.domain.TouchTurnRuleFieldDefinition
import daytrader.domain.TouchTurnRuleFieldKind
import daytrader.domain.TouchTurnRuleToggleDefinition
import daytrader.ui.theme.BrandRed
import daytrader.ui.theme.DarkBackground
import daytrader.ui.theme.GainGreen
import daytrader.ui.theme.LossRed
import daytrader.ui.theme.SurfaceDark
import daytrader.ui.theme.TableHeaderBg
import daytrader.ui.theme.TextSecondary

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

    Dialog(onDismissRequest = onDismiss) {
        Surface(
            color = SurfaceDark,
            modifier = Modifier
                .widthIn(min = 520.dp, max = 760.dp)
                .testTag("TouchTurnRulesConfigDialog")
        ) {
            Column(modifier = Modifier.padding(20.dp)) {
                Text(
                    "Touch Turn rules",
                    color = Color.White,
                    fontWeight = FontWeight.Bold,
                    fontSize = 18.sp
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    "Rules and thresholds are grouped by category. Defaultable thresholds appear first; " +
                        "broker-specific fields stay below. Changes apply on the next session start.",
                    fontSize = 12.sp,
                    color = TextSecondary,
                    lineHeight = 16.sp
                )
                Spacer(modifier = Modifier.height(12.dp))
                val invertSelected = toggleValues["invertTradeSide"] == true
                val allRulesEnabled = TouchTurnRuleConfig.toggleDefinitions.all { toggle ->
                    if (toggle.key == "fiveMinuteConfirmation" && invertSelected) return@all true
                    toggleValues[toggle.key] ?: true
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End
                ) {
                    OutlinedButton(
                        onClick = {
                            val enableAll = !allRulesEnabled
                            TouchTurnRuleConfig.toggleDefinitions.forEach { toggle ->
                                if (toggle.key == "fiveMinuteConfirmation" && toggleValues["invertTradeSide"] == true) {
                                    return@forEach
                                }
                                toggleValues[toggle.key] = enableAll
                            }
                        },
                        enabled = enabled,
                        modifier = Modifier.testTag("TouchTurnRulesToggleAllButton")
                    ) {
                        Text(
                            if (allRulesEnabled) "Disable all rules" else "Enable all rules",
                            color = if (enabled) Color.White else TextSecondary
                        )
                    }
                }
                Spacer(modifier = Modifier.height(8.dp))
                val visibleCategories = TouchTurnRuleCategory.entries.filter { category ->
                    category != TouchTurnRuleCategory.CONFIRMATION || !invertSelected
                }
                val columnSplit = (visibleCategories.size + 1) / 2
                Column(
                    modifier = Modifier
                        .heightIn(max = 480.dp)
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        verticalAlignment = Alignment.Top
                    ) {
                        Column(
                            modifier = Modifier.weight(1f),
                            verticalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            visibleCategories.take(columnSplit).forEach { category ->
                                TouchTurnRuleCategorySection(
                                    category = category,
                                    toggleValues = toggleValues,
                                    fieldValues = fieldValues,
                                    enabled = enabled,
                                    onToggleChange = { key, checked -> toggleValues[key] = checked },
                                    onFieldChange = { key, value -> fieldValues[key] = value }
                                )
                            }
                        }
                        Column(
                            modifier = Modifier.weight(1f),
                            verticalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            visibleCategories.drop(columnSplit).forEach { category ->
                                TouchTurnRuleCategorySection(
                                    category = category,
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
                Spacer(modifier = Modifier.height(16.dp))
                saveError?.let { message ->
                    Text(
                        message,
                        color = LossRed,
                        fontSize = 12.sp,
                        lineHeight = 16.sp,
                        modifier = Modifier.testTag("TouchTurnRulesSaveError")
                    )
                    Spacer(modifier = Modifier.height(8.dp))
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
                        Text("Reset defaults", color = if (enabled) Color.White else TextSecondary)
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TextButton(onClick = onDismiss) {
                            Text("Cancel", color = TextSecondary)
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
                                for (field in TouchTurnRuleConfig.fieldDefinitions) {
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
                            Text("Save", color = if (enabled) Color.White else TextSecondary)
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
    toggleValues: Map<String, Boolean>,
    fieldValues: Map<String, String>,
    enabled: Boolean,
    onToggleChange: (String, Boolean) -> Unit,
    onFieldChange: (String, String) -> Unit
) {
    var expanded by rememberSaveable(category.name) { mutableStateOf(true) }
    val toggle = TouchTurnRuleConfig.toggleForCategory(category)
    val toggleEnabled = toggle?.let { toggleValues[it.key] ?: true } ?: true
    val fields = TouchTurnRuleConfig.fieldsForCategoryDisplay(category)
    val showFields = category.fieldsAlwaysVisible || toggleEnabled
    val defaultableFields = fields.filter { it.defaultable }
    val mandatoryFields = fields.filter { !it.defaultable }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(DarkBackground, RoundedCornerShape(8.dp))
            .border(1.dp, TableHeaderBg, RoundedCornerShape(8.dp))
            .testTag("TouchTurnRuleCategory-${category.name}")
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { expanded = !expanded }
                .padding(horizontal = 10.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                category.label,
                fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold,
                color = BrandRed,
                modifier = Modifier.weight(1f)
            )
            Text(
                text = if (expanded) "▾" else "▸",
                fontSize = 10.sp,
                color = TextSecondary
            )
        }
        if (expanded) {
            Column(
                modifier = Modifier.padding(start = 10.dp, end = 10.dp, bottom = 10.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                toggle?.let { definition ->
                    TouchTurnRuleToggleRow(
                        toggle = definition,
                        checked = toggleEnabled,
                        enabled = enabled,
                        onCheckedChange = { onToggleChange(definition.key, it) }
                    )
                }
                if (showFields && fields.isNotEmpty()) {
                    if (toggle != null) {
                        HorizontalDivider(color = TableHeaderBg)
                    }
                    defaultableFields.forEach { field ->
                        TouchTurnRuleFieldEditor(
                            field = field,
                            value = fieldValues[field.key].orEmpty(),
                            enabled = enabled,
                            onValueChange = { onFieldChange(field.key, it) }
                        )
                    }
                    if (mandatoryFields.isNotEmpty() && defaultableFields.isNotEmpty()) {
                        HorizontalDivider(color = TableHeaderBg.copy(alpha = 0.6f))
                    }
                    mandatoryFields.forEach { field ->
                        TouchTurnRuleFieldEditor(
                            field = field,
                            value = fieldValues[field.key].orEmpty(),
                            enabled = enabled,
                            onValueChange = { onFieldChange(field.key, it) }
                        )
                    }
                }
            }
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
        verticalAlignment = Alignment.Top
    ) {
        Column(modifier = Modifier.weight(1f).padding(end = 12.dp)) {
            Text(toggle.label, fontSize = 12.sp, color = Color.White, fontWeight = FontWeight.SemiBold)
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                toggle.description,
                fontSize = 11.sp,
                color = TextSecondary,
                lineHeight = 15.sp
            )
        }
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            enabled = enabled,
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
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("TouchTurnRuleFieldGroup-${field.key}")
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(field.label, fontSize = 12.sp, color = Color.White, fontWeight = FontWeight.SemiBold)
            if (!field.defaultable) {
                Text(
                    "Required",
                    fontSize = 9.sp,
                    color = TextSecondary,
                    modifier = Modifier.testTag("TouchTurnRuleFieldRequired-${field.key}")
                )
            }
        }
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            field.description,
            fontSize = 11.sp,
            color = TextSecondary,
            lineHeight = 15.sp
        )
        Spacer(modifier = Modifier.height(6.dp))
        OutlinedTextField(
            value = value,
            onValueChange = onValueChange,
            enabled = enabled,
            singleLine = true,
            modifier = Modifier
                .fillMaxWidth()
                .testTag("TouchTurnRuleField-${field.key}"),
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
                        TouchTurnRuleFieldKind.RATIO -> "e.g. 0.25"
                        TouchTurnRuleFieldKind.PRICE -> "e.g. 0.05"
                        TouchTurnRuleFieldKind.INTEGER -> "e.g. 14"
                        TouchTurnRuleFieldKind.MILLISECONDS -> "e.g. 60000"
                    },
                    color = TextSecondary,
                    fontSize = 12.sp
                )
            }
        )
    }
}
