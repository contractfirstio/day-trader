package daytrader.ui

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
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
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

private enum class TouchTurnRulesConfigTab(val label: String) {
    THRESHOLDS("Thresholds"),
    RULES("Rules")
}

@Composable
fun TouchTurnRulesConfigDialog(
    initialRules: TouchTurnRuleConfig,
    enabled: Boolean,
    onDismiss: () -> Unit,
    onSave: (TouchTurnRuleConfig) -> Unit
) {
    var selectedTab by remember { mutableIntStateOf(TouchTurnRulesConfigTab.THRESHOLDS.ordinal) }
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
                .widthIn(max = 560.dp)
                .testTag("TouchTurnRulesConfigDialog")
        ) {
            Column(modifier = Modifier.padding(20.dp)) {
                Text(
                    "Touch Turn rules",
                    color = Color.White,
                    fontWeight = FontWeight.Bold,
                    fontSize = 18.sp
                )
                Spacer(modifier = Modifier.height(12.dp))
                TabRow(
                    selectedTabIndex = selectedTab,
                    containerColor = TableHeaderBg,
                    contentColor = Color.White,
                    divider = { HorizontalDivider(color = DarkBackground) }
                ) {
                    TouchTurnRulesConfigTab.entries.forEachIndexed { index, tab ->
                        Tab(
                            selected = selectedTab == index,
                            onClick = { selectedTab = index },
                            text = {
                                Text(
                                    tab.label,
                                    fontSize = 13.sp,
                                    modifier = Modifier.testTag("TouchTurnRulesConfigTab-${tab.name}")
                                )
                            }
                        )
                    }
                }
                Spacer(modifier = Modifier.height(14.dp))
                Column(
                    modifier = Modifier
                        .heightIn(max = 460.dp)
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(14.dp)
                ) {
                    when (TouchTurnRulesConfigTab.entries[selectedTab]) {
                        TouchTurnRulesConfigTab.THRESHOLDS -> {
                            Text(
                                "Numeric thresholds for liquidity, volume, turn confirmation, live tape, and " +
                                    "bracket sizing. Changes apply on the next session start.",
                                fontSize = 12.sp,
                                color = TextSecondary,
                                lineHeight = 16.sp
                            )
                            TouchTurnRuleConfig.fieldDefinitions.forEach { field ->
                                TouchTurnRuleFieldEditor(
                                    field = field,
                                    value = fieldValues[field.key].orEmpty(),
                                    enabled = enabled,
                                    onValueChange = { fieldValues[field.key] = it }
                                )
                            }
                        }
                        TouchTurnRulesConfigTab.RULES -> {
                            Text(
                                "Enable or disable individual entry-gate rules. Disabled rules are skipped " +
                                    "(treated as passed). Changes apply on the next session start.",
                                fontSize = 12.sp,
                                color = TextSecondary,
                                lineHeight = 16.sp
                            )
                            TouchTurnRuleConfig.toggleDefinitions.forEach { toggle ->
                                TouchTurnRuleToggleRow(
                                    toggle = toggle,
                                    checked = toggleValues[toggle.key] ?: true,
                                    enabled = enabled,
                                    onCheckedChange = { toggleValues[toggle.key] = it }
                                )
                            }
                        }
                    }
                }
                Spacer(modifier = Modifier.height(16.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    OutlinedButton(
                        onClick = {
                            TouchTurnRuleConfig.fieldDefinitions.forEach { field ->
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
                                var updated = initialRules
                                for (field in TouchTurnRuleConfig.fieldDefinitions) {
                                    val raw = fieldValues[field.key].orEmpty()
                                    updated = TouchTurnRuleConfig.withFieldValue(updated, field.key, raw)
                                        ?: return@TextButton
                                }
                                for (toggle in TouchTurnRuleConfig.toggleDefinitions) {
                                    val checked = toggleValues[toggle.key] ?: true
                                    updated = TouchTurnRuleConfig.withToggleEnabled(updated, toggle.key, checked)
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
    Column(modifier = Modifier.fillMaxWidth()) {
        Text(field.label, fontSize = 12.sp, color = Color.White, fontWeight = FontWeight.SemiBold)
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
