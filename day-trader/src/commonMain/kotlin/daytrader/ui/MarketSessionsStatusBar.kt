package daytrader.ui

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import daytrader.presentation.markets.MarketSessionStatusUi
import daytrader.presentation.markets.MarketSessionStatusUiMapper
import daytrader.ui.theme.BrandRed
import daytrader.ui.theme.DarkBackground
import daytrader.ui.theme.GainGreen
import daytrader.ui.theme.MarketOpenBorder
import daytrader.ui.theme.MarketOpenGlow
import daytrader.ui.theme.MarketOpenSurface
import daytrader.ui.theme.SelectionBorder
import daytrader.ui.theme.TextSecondary
import kotlinx.coroutines.delay

private val CardShape = RoundedCornerShape(8.dp)
private val ClosedBorder = Color(0xFF3D4454)
private val ClosedSurface = Color(0xFF181B24)
private val CountdownAmber = Color(0xFFFFB300)

@Composable
fun MarketSessionsStatusBar(
    selectedMarketZoneId: String?,
    onMarketClick: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    var tick by remember { mutableIntStateOf(0) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(1_000)
            tick++
        }
    }
    val markets = remember(tick) { MarketSessionStatusUiMapper.all() }

    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(DarkBackground)
            .padding(horizontal = 16.dp, vertical = 6.dp)
            .testTag("MarketSessionsStatusBar"),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        markets.forEach { market ->
            MarketSessionCard(
                market = market,
                isFilterSelected = market.zoneId == selectedMarketZoneId,
                onClick = { onMarketClick(market.zoneId) },
                modifier = Modifier.weight(1f)
            )
        }
    }
}

@Composable
private fun MarketSessionCard(
    market: MarketSessionStatusUi,
    isFilterSelected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val sessionBorderColor = if (market.isOpen) MarketOpenBorder else ClosedBorder
    val borderColor = if (isFilterSelected) SelectionBorder else sessionBorderColor
    val borderWidth = if (isFilterSelected) 2.dp else 1.dp
    val surfaceBrush = if (market.isOpen) {
        Brush.verticalGradient(listOf(MarketOpenSurface, MarketOpenGlow))
    } else {
        Brush.verticalGradient(listOf(ClosedSurface, Color(0xFF12141C)))
    }

    Column(
        modifier = modifier
            .clickable(onClick = onClick)
            .border(
                width = borderWidth,
                color = borderColor.copy(
                    alpha = when {
                        isFilterSelected -> 1f
                        market.isOpen -> 0.85f
                        else -> 0.55f
                    }
                ),
                shape = CardShape
            )
            .background(surfaceBrush, CardShape)
            .padding(horizontal = 8.dp, vertical = 5.dp)
            .testTag("MarketSessionCard-${market.label}")
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(5.dp),
                modifier = Modifier.weight(1f)
            ) {
                MarketLiveIndicator(isLive = market.isOpen)
                Text(
                    text = market.label,
                    color = Color.White,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            MarketStatusPill(
                label = market.headline,
                isOpen = market.isOpen,
                modifier = Modifier.testTag("MarketSessionStatus-${market.label}")
            )
        }
        Text(
            text = if (market.isOpen) "Elapsed ${market.subline}" else "Opens in ${market.subline}",
            color = if (market.isOpen) Color(0xFF80E8A8) else CountdownAmber,
            fontSize = 13.sp,
            fontWeight = FontWeight.Bold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 2.dp)
                .testTag("MarketSessionCountdown-${market.label}")
        )
    }
}

@Composable
private fun MarketStatusPill(
    label: String,
    isOpen: Boolean,
    modifier: Modifier = Modifier
) {
    val pillColor = if (isOpen) GainGreen else TextSecondary
    val backgroundColor = if (isOpen) GainGreen.copy(alpha = 0.15f) else Color(0xFF2A2F3A)
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(4.dp))
            .background(backgroundColor)
            .border(1.dp, pillColor.copy(alpha = 0.5f), RoundedCornerShape(4.dp))
            .padding(horizontal = 5.dp, vertical = 1.dp)
    ) {
        Text(
            text = label,
            color = pillColor,
            fontSize = 9.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = 0.5.sp
        )
    }
}

@Composable
private fun MarketLiveIndicator(isLive: Boolean) {
    Box(
        modifier = Modifier.size(10.dp),
        contentAlignment = Alignment.Center
    ) {
        if (isLive) {
            val transition = rememberInfiniteTransition(label = "livePulse")
            val pulse by transition.animateFloat(
                initialValue = 0.35f,
                targetValue = 1f,
                animationSpec = infiniteRepeatable(animation = tween(900), repeatMode = RepeatMode.Reverse),
                label = "pulseAlpha"
            )
            Box(
                modifier = Modifier
                    .size(9.dp)
                    .clip(CircleShape)
                    .background(GainGreen.copy(alpha = pulse * 0.35f))
            )
            Box(
                modifier = Modifier
                    .size(5.dp)
                    .clip(CircleShape)
                    .background(GainGreen)
            )
        } else {
            Box(
                modifier = Modifier
                    .size(5.dp)
                    .clip(CircleShape)
                    .background(ClosedBorder)
            )
        }
    }
}
