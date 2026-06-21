package daytrader.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import daytrader.domain.PlanSizingMode
import daytrader.presentation.Formatters
import daytrader.presentation.watchlist.WatchlistBracketOrderUi
import daytrader.presentation.watchlist.WatchlistPlanOutcomeUi
import daytrader.ui.theme.*

@Composable
internal fun WatchlistBracketOrderDialog(
    order: WatchlistBracketOrderUi,
    connectionLabel: String,
    onDismiss: () -> Unit,
    onSubmit: () -> Unit
) {
    AlertDialog(
        onDismissRequest = {
            if (!order.submitInProgress) onDismiss()
        },
        modifier = Modifier
            .fillMaxWidth(0.82f)
            .testTag("WatchlistBracketOrderDialog"),
        containerColor = SurfaceDark,
        title = {
            Column {
                Text(
                    "Confirm bracket order",
                    color = Color.White,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    "${order.symbol} · ${order.companyName} · ${order.planLabel}",
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
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text(
                    order.bracketOrderSummary.ifBlank {
                        "DAY limit entry with take-profit and stop children."
                    },
                    color = TextSecondary,
                    fontSize = 12.sp,
                    lineHeight = 16.sp
                )

                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .border(1.dp, TableHeaderBg, RoundedCornerShape(8.dp))
                        .background(DarkBackground, RoundedCornerShape(8.dp))
                        .padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    OrderDetailLine("Side", order.side.name.lowercase().replaceFirstChar { it.uppercase() })
                    OrderDetailLine(
                        "Entry type",
                        if (order.stopEntry) "Stop (STP)" else "Limit (LMT)"
                    )
                    OrderDetailLine(
                        "Stop type",
                        if (order.adjustableTrailingStop) "Adjustable trailing (STP→TRAIL)" else "Fixed (STP)"
                    )
                    order.entryPriceText.toDoubleOrNull()?.let {
                        OrderDetailLine("Entry", Formatters.moneyPlain(it, order.currencyCode))
                    }
                    order.stopPriceText.toDoubleOrNull()?.let {
                        OrderDetailLine("Stop", Formatters.moneyPlain(it, order.currencyCode))
                    }
                    order.targetPriceText.toDoubleOrNull()?.let {
                        OrderDetailLine("Target", Formatters.moneyPlain(it, order.currencyCode))
                    }
                    order.investmentAmountText.toDoubleOrNull()?.let {
                        OrderDetailLine(
                            label = if (order.sizingMode == PlanSizingMode.NOTIONAL) {
                                "Investment"
                            } else {
                                "Risk budget"
                            },
                            value = Formatters.moneyPlain(it, order.currencyCode)
                        )
                    }
                    order.quantityText.toIntOrNull()?.let { quantity ->
                        OrderDetailLine("Quantity", formatBracketQuantityLabel(order, quantity))
                    }
                    order.outcome?.let { outcome ->
                        BracketOrderOutcomeLines(outcome)
                    }
                }

                order.validationErrors.forEach { error ->
                    Text(error, color = LossRed, fontSize = 12.sp)
                }

                order.submitResultMessage?.let { message ->
                    Text(
                        message,
                        color = LossRed,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Medium,
                        modifier = Modifier.testTag("WatchlistBracketOrderResultMessage")
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
                Text(if (order.submitInProgress) "Placing…" else "Place")
            }
        },
        dismissButton = {
            TextButton(
                onClick = onDismiss,
                enabled = !order.submitInProgress,
                modifier = Modifier.testTag("CancelWatchlistBracketOrderButton")
            ) {
                Text("Cancel", color = TextSecondary)
            }
        }
    )
}

@Composable
private fun BracketOrderOutcomeLines(outcome: WatchlistPlanOutcomeUi) {
    HorizontalDivider(color = TableHeaderBg, modifier = Modifier.padding(vertical = 4.dp))
    outcome.quantityLabel?.let { OrderDetailLine("Sized quantity", it) }
    outcome.notionalLabel?.let { OrderDetailLine("Notional at entry", it) }
    outcome.profitAtTargetLabel?.let { OrderDetailLine("Profit at target", it, GainGreen) }
    outcome.lossAtStopLabel?.let { OrderDetailLine("Loss at stop", it, LossRed) }
    outcome.rMultipleLabel?.let { OrderDetailLine("R multiple", it) }
}

@Composable
private fun OrderDetailLine(label: String, value: String, valueColor: Color = Color.White) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(label, color = TextSecondary, fontSize = 13.sp)
        Text(value, color = valueColor, fontSize = 13.sp, fontWeight = FontWeight.Medium)
    }
}

private fun formatBracketQuantityLabel(order: WatchlistBracketOrderUi, quantity: Int): String {
    if (order.minOrderSize <= 1 && order.orderSizeIncrement <= 1) {
        return quantity.toString()
    }
    val lotCount = quantity / order.minOrderSize
    return "$quantity shares ($lotCount lots of ${order.minOrderSize})"
}
