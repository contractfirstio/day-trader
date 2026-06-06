package daytrader.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import daytrader.domain.TradeSide
import daytrader.presentation.watchlist.WatchlistBracketOrderField
import daytrader.presentation.watchlist.WatchlistBracketOrderUi
import daytrader.presentation.watchlist.WatchlistPlanOutcomeUi
import daytrader.ui.theme.*

@Composable
internal fun WatchlistBracketOrderDialog(
    order: WatchlistBracketOrderUi,
    connectionLabel: String,
    onDismiss: () -> Unit,
    onSideChange: (TradeSide) -> Unit,
    onFieldChange: (WatchlistBracketOrderField, String) -> Unit,
    onSubmit: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        modifier = Modifier
            .fillMaxWidth(0.88f)
            .testTag("WatchlistBracketOrderDialog"),
        containerColor = SurfaceDark,
        title = {
            Column {
                Text(
                    "Bracket order — ${order.symbol}",
                    color = Color.White,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    "${order.companyName} · ${order.planLabel}",
                    color = TextSecondary,
                    fontSize = 13.sp
                )
                Text(
                    connectionLabel,
                    color = TextSecondary,
                    fontSize = 12.sp
                )
            }
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 480.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text(
                    "Review and adjust the bracket before sending to the broker. Entry is a DAY limit order with take-profit and stop children.",
                    color = TextSecondary,
                    fontSize = 12.sp,
                    lineHeight = 16.sp
                )

                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(
                        selected = order.side == TradeSide.LONG,
                        onClick = { onSideChange(TradeSide.LONG) },
                        label = { Text("Long") }
                    )
                    FilterChip(
                        selected = order.side == TradeSide.SHORT,
                        onClick = { onSideChange(TradeSide.SHORT) },
                        label = { Text("Short") }
                    )
                }

                ConfigField(
                    label = "Entry price (limit)",
                    value = order.entryPriceText,
                    onValueChange = { onFieldChange(WatchlistBracketOrderField.ENTRY, it) }
                )
                ConfigField(
                    label = "Stop price",
                    value = order.stopPriceText,
                    onValueChange = { onFieldChange(WatchlistBracketOrderField.STOP, it) }
                )
                ConfigField(
                    label = "Target price",
                    value = order.targetPriceText,
                    onValueChange = { onFieldChange(WatchlistBracketOrderField.TARGET, it) }
                )
                ConfigField(
                    label = "Quantity (shares)",
                    value = order.quantityText,
                    onValueChange = { onFieldChange(WatchlistBracketOrderField.QUANTITY, it) }
                )

                order.validationErrors.forEach { error ->
                    Text(error, color = LossRed, fontSize = 12.sp)
                }

                order.outcome?.let { outcome ->
                    BracketOrderPreview(outcome)
                }

                order.submitResultMessage?.let { message ->
                    Text(
                        message,
                        color = if (message.startsWith("Bracket failed")) LossRed else TradeBlueBorder,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Medium
                    )
                }
            }
        },
        confirmButton = {
            Button(
                onClick = onSubmit,
                enabled = order.canSubmit,
                colors = ButtonDefaults.buttonColors(containerColor = BrandRed),
                modifier = Modifier.testTag("SubmitWatchlistBracketOrderButton")
            ) {
                Text(if (order.submitInProgress) "Submitting…" else "Submit bracket")
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
private fun BracketOrderPreview(outcome: WatchlistPlanOutcomeUi) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .border(1.dp, TableHeaderBg, RoundedCornerShape(8.dp))
            .background(DarkBackground, RoundedCornerShape(8.dp))
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Text("Order preview", color = Color.White, fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
        outcome.quantityLabel?.let { PreviewLine("Quantity", it) }
        outcome.notionalLabel?.let { PreviewLine("Notional at entry", it) }
        outcome.profitAtTargetLabel?.let { PreviewLine("Profit at target", it, GainGreen) }
        outcome.lossAtStopLabel?.let { PreviewLine("Loss at stop", it, LossRed) }
        outcome.rMultipleLabel?.let { PreviewLine("R multiple", it) }
    }
}

@Composable
private fun PreviewLine(label: String, value: String, valueColor: Color = Color.White) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(label, color = TextSecondary, fontSize = 13.sp)
        Text(value, color = valueColor, fontSize = 13.sp, fontWeight = FontWeight.Medium)
    }
}
