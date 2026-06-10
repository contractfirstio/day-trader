package daytrader.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import daytrader.presentation.Formatters
import daytrader.presentation.strategies.TouchTurnQuoteStripUi
import daytrader.ui.theme.GainGreen
import daytrader.ui.theme.TextSecondary

@Composable
fun TouchTurnQuoteStrip(
    strip: TouchTurnQuoteStripUi,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .testTag("TouchTurnQuoteStrip"),
        verticalArrangement = Arrangement.spacedBy(2.dp)
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            QuoteStripField(
                label = "Bid",
                value = strip.bid?.let { Formatters.listingPricePlain(it, strip.currencyCode, strip.listingExch) },
                testTag = "TouchTurnQuoteStripBid"
            )
            QuoteStripField(
                label = "Ask",
                value = strip.ask?.let { Formatters.listingPricePlain(it, strip.currencyCode, strip.listingExch) },
                testTag = "TouchTurnQuoteStripAsk"
            )
            QuoteStripField(
                label = "Last",
                value = strip.last?.let { Formatters.listingPricePlain(it, strip.currencyCode, strip.listingExch) },
                testTag = "TouchTurnQuoteStripLast"
            )
        }
        strip.entryPrice?.let { entry ->
            val gapLabel = strip.fillGapLabel
            val gapColor = when {
                strip.isFillable -> GainGreen
                else -> TextSecondary
            }
            Text(
                text = buildString {
                    append("Entry ")
                    append(Formatters.listingPricePlain(entry, strip.currencyCode, strip.listingExch))
                    if (gapLabel != null) {
                        append(" · Δ ")
                        append(gapLabel)
                    }
                    strip.closestApproach?.let { best ->
                        append(" · Best ")
                        append(best.gapLabel(strip.currencyCode, strip.listingExch))
                        append(" @ ")
                        append(Formatters.listingPricePlain(best.fillPrice, strip.currencyCode, strip.listingExch))
                    }
                },
                fontSize = 9.sp,
                color = gapColor,
                modifier = Modifier.testTag("TouchTurnQuoteStripEntryGap")
            )
        }
    }
}

@Composable
private fun QuoteStripField(
    label: String,
    value: String?,
    testTag: String
) {
    Text(
        text = "$label ${value ?: "—"}",
        fontSize = 9.sp,
        color = TextSecondary,
        modifier = Modifier.testTag(testTag)
    )
}
