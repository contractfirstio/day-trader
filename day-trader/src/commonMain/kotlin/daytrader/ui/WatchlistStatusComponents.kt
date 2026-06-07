package daytrader.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Info
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TooltipBox
import androidx.compose.material3.TooltipDefaults
import androidx.compose.material3.rememberTooltipState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import daytrader.domain.MacroTrendState
import daytrader.presentation.watchlist.ReversalScoreProgressStepStatus
import daytrader.presentation.watchlist.ReversalScoreProgressUi
import daytrader.presentation.watchlist.WatchlistActivitySummaryUi
import daytrader.presentation.watchlist.WatchlistConnectionChipTone
import daytrader.presentation.watchlist.WatchlistConnectionChipUi
import daytrader.presentation.watchlist.WatchlistMacroRegimeCardUi
import daytrader.presentation.watchlist.WatchlistScanProgressUi
import daytrader.presentation.watchlist.WatchlistStatusStripUi
import daytrader.ui.theme.BrandRed
import daytrader.ui.theme.DarkBackground
import daytrader.ui.theme.GainGreen
import daytrader.ui.theme.SurfaceDark
import daytrader.ui.theme.TableHeaderBg
import daytrader.ui.theme.TextSecondary
import daytrader.ui.theme.TradeBlueBorder

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun WatchlistStatusStrip(
    strip: WatchlistStatusStripUi,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        strip.connectionChips.forEach { chip ->
            StatusChip(label = chip.label, tone = chip.tone)
        }
        PriceModeChip(
            label = strip.priceModeLabel,
            tooltip = strip.priceModeTooltip
        )
        strip.macroChipLabel?.let { label ->
            StatusChip(
                label = label,
                tone = strip.macroChipTone ?: WatchlistConnectionChipTone.CONNECTED
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PriceModeChip(label: String, tooltip: String) {
    val tooltipState = rememberTooltipState()
    TooltipBox(
        positionProvider = TooltipDefaults.rememberPlainTooltipPositionProvider(),
        tooltip = {
            Text(tooltip, modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp), fontSize = 12.sp)
        },
        state = tooltipState
    ) {
        Row(
            modifier = Modifier
                .clip(RoundedCornerShape(10.dp))
                .background(TableHeaderBg)
                .border(1.dp, DarkBackground, RoundedCornerShape(10.dp))
                .padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Text(label, color = TextSecondary, fontSize = 11.sp, fontWeight = FontWeight.Medium)
            Icon(
                Icons.Default.Info,
                contentDescription = "Price feed info",
                tint = TextSecondary,
                modifier = Modifier.size(12.dp)
            )
        }
    }
}

@Composable
private fun StatusChip(
    label: String,
    tone: WatchlistConnectionChipTone
) {
    val accent = chipAccent(tone)
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(10.dp))
            .background(accent.copy(alpha = 0.12f))
            .border(1.dp, accent.copy(alpha = 0.35f), RoundedCornerShape(10.dp))
            .padding(horizontal = 8.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Box(
            modifier = Modifier
                .size(6.dp)
                .clip(CircleShape)
                .background(accent)
        )
        Text(label, color = accent, fontSize = 11.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
    }
}

@Composable
internal fun WatchlistMacroRegimeCard(
    card: WatchlistMacroRegimeCardUi,
    modifier: Modifier = Modifier
) {
    val accent = when (card.trend) {
        MacroTrendState.BULL -> GainGreen
        MacroTrendState.BEAR -> BrandRed
        null -> TextSecondary
    }
    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(IntrinsicSize.Min)
            .clip(RoundedCornerShape(12.dp))
            .background(SurfaceDark)
            .border(1.dp, accent.copy(alpha = 0.35f), RoundedCornerShape(12.dp))
    ) {
        Box(
            modifier = Modifier
                .width(4.dp)
                .fillMaxHeight()
                .background(accent)
        )
        Column(
            modifier = Modifier
                .weight(1f)
                .padding(horizontal = 14.dp, vertical = 12.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.Top
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("MACRO REGIME", color = TextSecondary, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                    Text(
                        card.trendLabel,
                        color = accent,
                        fontSize = 22.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(top = 2.dp)
                    )
                    Text(
                        card.actionHint,
                        color = TextSecondary,
                        fontSize = 11.sp,
                        lineHeight = 15.sp,
                        modifier = Modifier.padding(top = 6.dp)
                    )
                }
                Column(horizontalAlignment = Alignment.End) {
                    Text("SPY · 200-day SMA", color = TextSecondary, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                    Text(
                        card.spyPriceLabel,
                        color = Color.White,
                        fontSize = 18.sp,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.padding(top = 2.dp)
                    )
                    Text(
                        card.distanceFromSmaLabel,
                        color = accent,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Medium,
                        modifier = Modifier.padding(top = 2.dp)
                    )
                }
            }
            Spacer(modifier = Modifier.height(8.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(card.scoredLabel, color = TextSecondary, fontSize = 11.sp)
                Text(card.calculatedAtLabel, color = TextSecondary, fontSize = 11.sp)
            }
        }
    }
}

@Composable
internal fun WatchlistActivitySummaryBar(
    summary: WatchlistActivitySummaryUi,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(TableHeaderBg)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        summary.proximityLabel?.let { label ->
            Text(
                label,
                color = if (summary.proximityHighlighted) TradeBlueBorder else TextSecondary,
                fontSize = 12.sp,
                fontWeight = if (summary.proximityHighlighted) FontWeight.SemiBold else FontWeight.Normal
            )
        }
        summary.reversalLabel?.let { label ->
            Text(label, color = TextSecondary, fontSize = 12.sp)
        }
    }
}

@Composable
internal fun WatchlistScanProgressPanel(
    progress: WatchlistScanProgressUi,
    modifier: Modifier = Modifier
) {
    Column(modifier = modifier.fillMaxWidth()) {
        LinearProgressIndicator(
            modifier = Modifier.fillMaxWidth(),
            color = GainGreen,
            trackColor = DarkBackground,
            progress = {
                if (progress.total <= 0) 0f
                else progress.completed.toFloat() / progress.total.toFloat()
            }
        )
        Text(
            "Scanning ${progress.completed}/${progress.total} · ${progress.symbol}",
            color = TextSecondary,
            fontSize = 12.sp,
            modifier = Modifier.padding(top = 4.dp)
        )
    }
}

@Composable
internal fun WatchlistReversalScoreProgressPanel(
    progress: ReversalScoreProgressUi,
    modifier: Modifier = Modifier
) {
    Column(modifier = modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            progress.steps.forEachIndexed { index, step ->
                if (index > 0) {
                    Text("→", color = TextSecondary, fontSize = 11.sp)
                }
                ReversalScoreStepChip(step.label, step.status)
            }
        }
        progress.detailLabel?.let { detail ->
            Text(detail, color = TextSecondary, fontSize = 12.sp, modifier = Modifier.padding(top = 6.dp))
        }
    }
}

@Composable
private fun ReversalScoreStepChip(label: String, status: ReversalScoreProgressStepStatus) {
    val accent = when (status) {
        ReversalScoreProgressStepStatus.COMPLETE -> GainGreen
        ReversalScoreProgressStepStatus.ACTIVE -> TradeBlueBorder
        ReversalScoreProgressStepStatus.PENDING -> TextSecondary
    }
    val prefix = when (status) {
        ReversalScoreProgressStepStatus.COMPLETE -> "✓ "
        ReversalScoreProgressStepStatus.ACTIVE -> "● "
        ReversalScoreProgressStepStatus.PENDING -> ""
    }
    Text(
        text = "$prefix$label",
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .background(accent.copy(alpha = 0.12f))
            .border(1.dp, accent.copy(alpha = 0.3f), RoundedCornerShape(8.dp))
            .padding(horizontal = 8.dp, vertical = 3.dp),
        color = accent,
        fontSize = 11.sp,
        fontWeight = FontWeight.SemiBold
    )
}

private fun chipAccent(tone: WatchlistConnectionChipTone): Color = when (tone) {
    WatchlistConnectionChipTone.CONNECTED -> GainGreen
    WatchlistConnectionChipTone.CONNECTING -> TradeBlueBorder
    WatchlistConnectionChipTone.DISCONNECTED -> TextSecondary
    WatchlistConnectionChipTone.ERROR -> BrandRed
}

@Composable
internal fun ReversalScorePill(score: Int, stale: Boolean, modifier: Modifier = Modifier) {
    val accent = reversalScoreAccent(score)
    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        if (stale) {
            Box(
                modifier = Modifier
                    .size(5.dp)
                    .clip(CircleShape)
                    .background(TextSecondary.copy(alpha = 0.7f))
            )
        }
        Text(
            text = score.toString(),
            modifier = Modifier
                .clip(RoundedCornerShape(8.dp))
                .background(accent.copy(alpha = 0.18f))
                .border(1.dp, accent.copy(alpha = 0.45f), RoundedCornerShape(8.dp))
                .padding(horizontal = 8.dp, vertical = 2.dp),
            color = accent,
            fontWeight = FontWeight.Bold,
            fontSize = 13.sp
        )
    }
}

@Composable
internal fun AlignmentBadgeChip(label: String, modifier: Modifier = Modifier) {
    val accent = alignmentBadgeAccent(label)
    Text(
        text = label,
        modifier = modifier
            .clip(RoundedCornerShape(8.dp))
            .background(accent.copy(alpha = 0.12f))
            .border(1.dp, accent.copy(alpha = 0.35f), RoundedCornerShape(8.dp))
            .padding(horizontal = 6.dp, vertical = 2.dp),
        color = accent,
        fontWeight = alignmentBadgeFontWeight(label),
        fontSize = 10.sp,
        maxLines = 1
    )
}

internal fun reversalScoreAccent(score: Int): Color = when {
    score <= 20 -> GainGreen
    score >= 80 -> BrandRed
    else -> Color.White
}

private val BadgeOrange = Color(0xFFFF9800)
private val BadgeOlive = Color(0xFFAFB42B)

internal fun alignmentBadgeAccent(label: String): Color = when (label) {
    "BUY THE DIP" -> GainGreen
    "SELL THE RIP" -> BrandRed
    "TREND EXHAUSTION" -> BadgeOrange
    "OVERSOLD BOUNCE" -> BadgeOlive
    else -> TextSecondary
}

internal fun alignmentBadgeFontWeight(label: String): FontWeight = when (label) {
    "BUY THE DIP", "SELL THE RIP" -> FontWeight.Bold
    else -> FontWeight.SemiBold
}
