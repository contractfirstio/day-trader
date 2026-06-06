package daytrader.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import daytrader.presentation.strategies.LiveBrokerUiState
import daytrader.ui.theme.BrandRed
import daytrader.ui.theme.GainGreen
import daytrader.ui.theme.LossRed
import daytrader.ui.theme.TableHeaderBg
import daytrader.ui.theme.TextSecondary
import kotlinx.coroutines.delay

@Composable
fun TradingTabLiveMarketStrip(broker: LiveBrokerUiState) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(TableHeaderBg)
            .padding(horizontal = 16.dp, vertical = 6.dp)
            .testTag("TradingTabLiveMarketStrip")
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "Live · ${broker.symbol}",
                fontSize = 10.sp,
                fontWeight = FontWeight.SemiBold,
                color = BrandRed
            )
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                CompactQuoteField(
                    label = "Bid",
                    formattedValue = broker.formattedBid,
                    numericValue = broker.bid,
                    quoteKind = QuoteKind.BID,
                    testTag = "TradingTabLiveMarketBid"
                )
                QuoteSeparator()
                CompactQuoteField(
                    label = "Ask",
                    formattedValue = broker.formattedAsk,
                    numericValue = broker.ask,
                    quoteKind = QuoteKind.ASK,
                    testTag = "TradingTabLiveMarketAsk"
                )
                QuoteSeparator()
                CompactQuoteField(
                    label = "Last",
                    formattedValue = broker.formattedLast,
                    numericValue = broker.last,
                    quoteKind = QuoteKind.LAST,
                    testTag = "TradingTabLiveMarketLast"
                )
            }
        }
        broker.fillReadinessHint?.let { hint ->
            Text(
                text = hint,
                fontSize = 9.sp,
                color = TextSecondary,
                modifier = Modifier.padding(top = 2.dp)
            )
        }
    }
}

@Composable
private fun QuoteSeparator() {
    Text(
        text = "·",
        fontSize = 9.sp,
        color = TextSecondary.copy(alpha = 0.45f)
    )
}

private enum class QuoteKind {
    BID,
    ASK,
    LAST
}

private enum class QuoteTickDirection {
    UP,
    DOWN,
    NONE
}

@Composable
private fun CompactQuoteField(
    label: String,
    formattedValue: String?,
    numericValue: Double?,
    quoteKind: QuoteKind,
    testTag: String
) {
    var previousValue by remember { mutableStateOf<Double?>(null) }
    var flashActive by remember { mutableStateOf(false) }
    var tickDirection by remember { mutableStateOf(QuoteTickDirection.NONE) }

    LaunchedEffect(numericValue) {
        val previous = previousValue
        if (numericValue != null && previous != null && numericValue != previous) {
            tickDirection = when {
                numericValue > previous -> QuoteTickDirection.UP
                numericValue < previous -> QuoteTickDirection.DOWN
                else -> QuoteTickDirection.NONE
            }
            flashActive = true
            delay(400)
            flashActive = false
        }
        previousValue = numericValue
    }

    val restColor = when (quoteKind) {
        QuoteKind.BID -> GainGreen
        QuoteKind.ASK -> LossRed
        QuoteKind.LAST -> Color.White
    }
    val flashColor = when (quoteKind) {
        QuoteKind.BID,
        QuoteKind.ASK -> Color.White
        QuoteKind.LAST -> when (tickDirection) {
            QuoteTickDirection.UP -> GainGreen
            QuoteTickDirection.DOWN -> LossRed
            QuoteTickDirection.NONE -> Color.White
        }
    }
    val valueColor by animateColorAsState(
        targetValue = if (flashActive) flashColor else restColor,
        animationSpec = tween(durationMillis = if (flashActive) 80 else 320),
        label = "quoteValueColor"
    )

    Row(
        horizontalArrangement = Arrangement.spacedBy(3.dp),
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.testTag(testTag)
    ) {
        Text(
            text = label,
            fontSize = 10.sp,
            fontWeight = FontWeight.Medium,
            color = TextSecondary
        )
        Text(
            text = formattedValue ?: "—",
            fontSize = 10.sp,
            fontWeight = FontWeight.SemiBold,
            color = valueColor
        )
    }
}
