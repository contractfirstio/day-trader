package daytrader.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import daytrader.domain.InstrumentOrderSizeRules
import daytrader.domain.InstrumentRelookup
import daytrader.ui.theme.GainGreen
import daytrader.ui.theme.LossRed
import daytrader.ui.theme.TextSecondary

@Composable
internal fun InstrumentLotSizePanel(
    listingLabel: String?,
    orderSizeRules: InstrumentOrderSizeRules,
    tickRuleLabel: String? = null,
    canRelookup: Boolean,
    relookupInProgress: Boolean,
    relookupMessage: String?,
    onRelookup: () -> Unit,
    modifier: Modifier = Modifier,
    testTagPrefix: String = "InstrumentLotSize",
    compact: Boolean = false,
    tableLayout: Boolean = false
) {
    val sectionSpacing = if (compact) 4.dp else 6.dp
    val labelSize = if (compact) 10.sp else 12.sp
    val bodySize = if (compact) 11.sp else 12.sp
    val listingSize = if (compact) 12.sp else 13.sp

    if (tableLayout) {
        ConfigTableRow(label = "Listing") {
            if (listingLabel != null) {
                ConfigTableValueText(listingLabel, emphasized = true)
            } else {
                ConfigTableValueText(
                    "No saved listing — refresh from IB to load board lot size.",
                    color = TextSecondary
                )
            }
        }
        ConfigTableRow(label = "Lot size") {
            ConfigTableValueText(
                InstrumentRelookup.lotSizeLabel(orderSizeRules),
                color = if (orderSizeRules.isUnitLot()) TextSecondary else GainGreen,
                testTag = "${testTagPrefix}LotSizeLabel"
            )
        }
        tickRuleLabel?.let { label ->
            ConfigTableRow(label = "Tick rule") {
                ConfigTableValueText(label, color = TextSecondary, testTag = "${testTagPrefix}TickRuleLabel")
            }
        }
        ConfigTableRow(label = "Refresh") {
            OutlinedButton(
                onClick = onRelookup,
                enabled = canRelookup && !relookupInProgress,
                colors = ButtonDefaults.outlinedButtonColors(contentColor = Color.White),
                contentPadding = if (compact) {
                    PaddingValues(horizontal = 10.dp, vertical = 4.dp)
                } else {
                    ButtonDefaults.ContentPadding
                },
                modifier = Modifier.testTag("${testTagPrefix}RelookupButton")
            ) {
                Text(
                    when {
                        relookupInProgress -> "Looking up…"
                        else -> "Refresh from IB"
                    },
                    fontSize = if (compact) 11.sp else 12.sp
                )
            }
        }
        if (!canRelookup) {
            ConfigTableRow(label = "Note", alignTop = true) {
                ConfigTableValueText(
                    "Connect Interactive Brokers (or Hybrid market data) to refresh listing details.",
                    color = TextSecondary
                )
            }
        }
        relookupMessage?.let { message ->
            ConfigTableRow(label = "Status", alignTop = true) {
                ConfigTableValueText(
                    message,
                    color = if (message.startsWith("Updated")) GainGreen else LossRed,
                    testTag = "${testTagPrefix}RelookupMessage"
                )
            }
        }
        return
    }

    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(sectionSpacing)
    ) {
        Text("Listing & lot size", fontSize = labelSize, color = TextSecondary, fontWeight = FontWeight.Medium)
        listingLabel?.let {
            Text(it, fontSize = listingSize, color = Color.White, fontWeight = FontWeight.Medium)
        } ?: Text(
            "No saved listing — refresh from IB to load board lot size.",
            fontSize = bodySize,
            color = TextSecondary,
            lineHeight = if (compact) 14.sp else 15.sp
        )
        Text(
            InstrumentRelookup.lotSizeLabel(orderSizeRules),
            fontSize = bodySize,
            color = if (orderSizeRules.isUnitLot()) TextSecondary else GainGreen,
            lineHeight = if (compact) 14.sp else 15.sp,
            modifier = Modifier.testTag("${testTagPrefix}LotSizeLabel")
        )
        tickRuleLabel?.let { label ->
            Text(
                label,
                fontSize = bodySize,
                color = TextSecondary,
                lineHeight = if (compact) 14.sp else 15.sp,
                modifier = Modifier.testTag("${testTagPrefix}TickRuleLabel")
            )
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(if (compact) 6.dp else 8.dp)
        ) {
            OutlinedButton(
                onClick = onRelookup,
                enabled = canRelookup && !relookupInProgress,
                colors = ButtonDefaults.outlinedButtonColors(contentColor = Color.White),
                contentPadding = if (compact) {
                    PaddingValues(horizontal = 10.dp, vertical = 4.dp)
                } else {
                    ButtonDefaults.ContentPadding
                },
                modifier = Modifier.testTag("${testTagPrefix}RelookupButton")
            ) {
                Text(
                    when {
                        relookupInProgress -> "Looking up…"
                        else -> "Refresh from IB"
                    },
                    fontSize = if (compact) 11.sp else 12.sp
                )
            }
        }
        if (!canRelookup) {
            Text(
                "Connect Interactive Brokers (or Hybrid market data) to refresh listing details.",
                fontSize = if (compact) 10.sp else 11.sp,
                color = TextSecondary,
                lineHeight = if (compact) 13.sp else 14.sp
            )
        }
        relookupMessage?.let { message ->
            Text(
                message,
                fontSize = bodySize,
                color = if (message.startsWith("Updated")) GainGreen else LossRed,
                lineHeight = if (compact) 14.sp else 15.sp,
                modifier = Modifier.testTag("${testTagPrefix}RelookupMessage")
            )
        }
    }
}
