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
import androidx.compose.foundation.layout.height
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
import daytrader.ui.theme.TextSecondary
import kotlinx.coroutines.delay

private val CardHeight = 156.dp
private val CardShape = RoundedCornerShape(12.dp)
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

    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(DarkBackground)
            .testTag("MarketSessionsStatusBar")
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 14.dp),
            horizontalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            markets.forEach { market ->
                MarketSessionCard(
                    market = market,
                    isFilterSelected = market.zoneId == selectedMarketZoneId,
                    onClick = { onMarketClick(market.zoneId) },
                    modifier = Modifier
                        .weight(1f)
                        .height(CardHeight)
                )
            }
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
    val borderColor = if (isFilterSelected) BrandRed else sessionBorderColor
    val borderWidth = if (isFilterSelected) 3.dp else 2.dp
    val surfaceBrush = if (market.isOpen) {
        Brush.verticalGradient(listOf(MarketOpenSurface, MarketOpenGlow))
    } else {
        Brush.verticalGradient(listOf(ClosedSurface, Color(0xFF12141C)))
    }

    Box(
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
            .testTag("MarketSessionCard-${market.label}")
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    MarketLiveIndicator(isLive = market.isOpen)
                    Column {
                        Text(
                            text = market.label,
                            color = Color.White,
                            fontSize = 18.sp,
                            fontWeight = FontWeight.Bold,
                            letterSpacing = 1.sp
                        )
                        Text(
                            text = market.zoneAbbrev,
                            color = TextSecondary,
                            fontSize = 11.sp
                        )
                    }
                }
                MarketStatusPill(
                    label = market.headline,
                    isOpen = market.isOpen,
                    modifier = Modifier.testTag("MarketSessionStatus-${market.label}")
                )
            }

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(72.dp),
                verticalArrangement = Arrangement.Center
            ) {
                Text(
                    text = if (market.isOpen) "SESSION ELAPSED" else "OPENS IN",
                    color = TextSecondary,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.SemiBold,
                    letterSpacing = 1.2.sp
                )
                Text(
                    text = market.subline,
                    color = if (market.isOpen) Color(0xFF80E8A8) else CountdownAmber,
                    fontSize = 28.sp,
                    fontWeight = FontWeight.Bold,
                    lineHeight = 32.sp,
                    modifier = Modifier
                        .testTag("MarketSessionCountdown-${market.label}")
                        .padding(top = 4.dp)
                )
            }
        }
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
            .clip(RoundedCornerShape(6.dp))
            .background(backgroundColor)
            .border(1.dp, pillColor.copy(alpha = 0.5f), RoundedCornerShape(6.dp))
            .padding(horizontal = 10.dp, vertical = 5.dp)
    ) {
        Text(
            text = label,
            color = pillColor,
            fontSize = 12.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = 1.sp
        )
    }
}

@Composable
private fun MarketLiveIndicator(isLive: Boolean) {
    Box(
        modifier = Modifier.size(16.dp),
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
                    .size(14.dp)
                    .clip(CircleShape)
                    .background(GainGreen.copy(alpha = pulse * 0.35f))
            )
            Box(
                modifier = Modifier
                    .size(8.dp)
                    .clip(CircleShape)
                    .background(GainGreen)
            )
        } else {
            Box(
                modifier = Modifier
                    .size(8.dp)
                    .clip(CircleShape)
                    .background(ClosedBorder)
            )
        }
    }
}
