package daytrader.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
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
    testTagPrefix: String = "InstrumentLotSize"
) {
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Text("Listing & lot size", fontSize = 12.sp, color = TextSecondary, fontWeight = FontWeight.Medium)
        listingLabel?.let {
            Text(it, fontSize = 13.sp, color = Color.White, fontWeight = FontWeight.Medium)
        } ?: Text(
            "No saved listing — refresh from IB to load board lot size.",
            fontSize = 12.sp,
            color = TextSecondary,
            lineHeight = 15.sp
        )
        Text(
            InstrumentRelookup.lotSizeLabel(orderSizeRules),
            fontSize = 12.sp,
            color = if (orderSizeRules.isUnitLot()) TextSecondary else GainGreen,
            lineHeight = 15.sp,
            modifier = Modifier.testTag("${testTagPrefix}LotSizeLabel")
        )
        tickRuleLabel?.let { label ->
            Text(
                label,
                fontSize = 12.sp,
                color = TextSecondary,
                lineHeight = 15.sp,
                modifier = Modifier.testTag("${testTagPrefix}TickRuleLabel")
            )
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            OutlinedButton(
                onClick = onRelookup,
                enabled = canRelookup && !relookupInProgress,
                colors = ButtonDefaults.outlinedButtonColors(contentColor = Color.White),
                modifier = Modifier.testTag("${testTagPrefix}RelookupButton")
            ) {
                Text(
                    when {
                        relookupInProgress -> "Looking up…"
                        else -> "Refresh from IB"
                    },
                    fontSize = 12.sp
                )
            }
        }
        if (!canRelookup) {
            Text(
                "Connect Interactive Brokers (or Hybrid market data) to refresh listing details.",
                fontSize = 11.sp,
                color = TextSecondary,
                lineHeight = 14.sp
            )
        }
        relookupMessage?.let { message ->
            Text(
                message,
                fontSize = 12.sp,
                color = if (message.startsWith("Updated")) GainGreen else LossRed,
                lineHeight = 15.sp,
                modifier = Modifier.testTag("${testTagPrefix}RelookupMessage")
            )
        }
    }
}
