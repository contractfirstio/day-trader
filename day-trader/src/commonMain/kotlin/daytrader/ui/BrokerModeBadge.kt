package daytrader.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import daytrader.gateway.BrokerId
import daytrader.gateway.BrokerKind
import daytrader.ui.theme.BrandRed
import daytrader.ui.theme.GainGreen
import daytrader.ui.theme.TradeBlueBorder
import daytrader.ui.theme.TradeBlueSurface

private val BadgeShape = RoundedCornerShape(6.dp)
private val LiveAccent = Color(0xFFFFB300)

@Composable
fun BrokerModeBadge(
    brokerId: BrokerId,
    brokerKind: BrokerKind = BrokerKind.EMULATOR,
    modifier: Modifier = Modifier
) {
    val (label, borderColor, surfaceColor, textColor) = when {
        brokerKind == BrokerKind.REPLAY -> BrokerModeBadgeStyle(
            label = "REPLAY · CAPTURED",
            borderColor = Color(0xFF80CBC4),
            surfaceColor = Color(0xFF142220),
            textColor = Color(0xFF80CBC4)
        )
        brokerKind == BrokerKind.EMULATOR_LIVE_IB_MARKET_DATA -> BrokerModeBadgeStyle(
            label = "PAPER · LIVE IB",
            borderColor = LiveAccent,
            surfaceColor = Color(0xFF1A2218),
            textColor = GainGreen
        )
        brokerId == BrokerId.INTERACTIVE_BROKERS -> BrokerModeBadgeStyle(
            label = "LIVE · IB",
            borderColor = BrandRed,
            surfaceColor = Color(0xFF2A1214),
            textColor = LiveAccent
        )
        else -> BrokerModeBadgeStyle(
            label = "SIM · EMULATOR",
            borderColor = TradeBlueBorder,
            surfaceColor = TradeBlueSurface,
            textColor = TradeBlueBorder
        )
    }

    Box(
        modifier = modifier
            .testTag("brokerModeBadge")
            .clip(BadgeShape)
            .background(surfaceColor)
            .border(2.dp, borderColor, BadgeShape)
            .padding(horizontal = 12.dp, vertical = 6.dp)
    ) {
        Text(
            text = label,
            color = textColor,
            fontSize = 13.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = 0.8.sp
        )
    }
}

private data class BrokerModeBadgeStyle(
    val label: String,
    val borderColor: Color,
    val surfaceColor: Color,
    val textColor: Color
)
