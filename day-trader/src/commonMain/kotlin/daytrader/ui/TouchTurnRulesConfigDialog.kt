package daytrader.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import daytrader.domain.TouchTurnRuleConfig
import daytrader.domain.TouchTurnRuleFieldDefinition
import daytrader.domain.TouchTurnRuleFieldKind
import daytrader.ui.theme.DarkBackground
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
    val fieldValues = remember(initialRules) {
        mutableStateMapOf<String, String>().apply {
            TouchTurnRuleConfig.fieldDefinitions.forEach { field ->
                put(field.key, TouchTurnRuleConfig.valueForField(initialRules, field.key))
            }
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = SurfaceDark,
        title = {
            Text("Touch Turn rules", color = Color.White, fontWeight = FontWeight.Bold)
        },
        text = {
            Column(
                modifier = Modifier
                    .heightIn(max = 520.dp)
                    .verticalScroll(rememberScrollState())
                    .testTag("TouchTurnRulesConfigDialog"),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                Text(
                    "Thresholds for liquidity, volume, turn confirmation, live tape, and bracket sizing. " +
                        "Changes apply on the next session start.",
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
        },
        confirmButton = {
            TextButton(
                onClick = {
                    var updated = initialRules
                    for (field in TouchTurnRuleConfig.fieldDefinitions) {
                        val raw = fieldValues[field.key].orEmpty()
                        updated = TouchTurnRuleConfig.withFieldValue(updated, field.key, raw) ?: return@TextButton
                    }
                    onSave(updated)
                },
                enabled = enabled,
                modifier = Modifier.testTag("TouchTurnRulesSaveButton")
            ) {
                Text("Save", color = if (enabled) Color.White else TextSecondary)
            }
        },
        dismissButton = {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(
                    onClick = {
                        TouchTurnRuleConfig.fieldDefinitions.forEach { field ->
                            fieldValues[field.key] =
                                TouchTurnRuleConfig.valueForField(TouchTurnRuleConfig.DEFAULT, field.key)
                        }
                    },
                    enabled = enabled
                ) {
                    Text("Reset defaults", color = if (enabled) Color.White else TextSecondary)
                }
                TextButton(onClick = onDismiss) {
                    Text("Cancel", color = TextSecondary)
                }
            }
        }
    )
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
