package daytrader.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import daytrader.presentation.strategies.SessionTradeDetailUiState
import daytrader.presentation.strategies.SessionTradeFillUi
import daytrader.ui.theme.DarkBackground
import daytrader.ui.theme.GainGreen
import daytrader.ui.theme.LossRed
import daytrader.ui.theme.SurfaceDark
import daytrader.ui.theme.TableHeaderBg
import daytrader.ui.theme.TextSecondary

@Composable
fun SessionTradeDetailPanel(
    detail: SessionTradeDetailUiState,
    modifier: Modifier = Modifier,
    testTagPrefix: String = "SessionTradeDetail"
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .testTag("${testTagPrefix}Panel"),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        TradeDetailHero(detail, testTagPrefix)
        TradeDetailPnLStrip(detail, testTagPrefix)
        TradeDetailPriceStrip(detail, testTagPrefix)
        if (detail.fills.isNotEmpty()) {
            TradeFillsTable(detail.fills, testTagPrefix)
        }
    }
}

@Composable
private fun TradeDetailHero(detail: SessionTradeDetailUiState, testTagPrefix: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.Top
    ) {
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                TradeSideBadge(
                    label = detail.sideLabel,
                    isLong = detail.isLong,
                    modifier = Modifier.testTag("${testTagPrefix}Side")
                )
                Text(
                    text = detail.headline,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = Color.White,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.testTag("${testTagPrefix}Headline")
                )
            }
            detail.lifecycleLabel?.let { label ->
                Text(
                    text = label,
                    fontSize = 10.sp,
                    color = Color(0xFFFFB74D),
                    modifier = Modifier.testTag("${testTagPrefix}Lifecycle")
                )
            }
            if (!detail.showPriceStrip && detail.detailLine.isNotBlank()) {
                Text(
                    text = detail.detailLine,
                    fontSize = 11.sp,
                    color = TextSecondary,
                    modifier = Modifier.testTag("${testTagPrefix}DetailLine")
                )
            }
        }
        Column(horizontalAlignment = Alignment.End) {
            Text(
                text = if (detail.isOpen) "Unrealized" else "Realized",
                fontSize = 9.sp,
                color = TextSecondary
            )
            Text(
                text = primaryPnLText(detail),
                fontSize = 22.sp,
                fontWeight = FontWeight.Bold,
                color = primaryPnLColor(detail),
                modifier = Modifier.testTag("${testTagPrefix}PrimaryPnL")
            )
        }
    }
}

@Composable
private fun TradeDetailPnLStrip(detail: SessionTradeDetailUiState, testTagPrefix: String) {
    val chips = buildList {
        if (detail.isOpen && detail.formattedRealizedPnL.isNotBlank()) {
            add(Triple("Realized", detail.formattedRealizedPnL, detail.isPositiveRealizedPnL))
        }
        if (!detail.isOpen && detail.formattedUnrealizedPnL != null) {
            add(
                Triple(
                    "Unrealized",
                    detail.formattedUnrealizedPnL,
                    (detail.unrealizedPnL ?: 0.0) >= 0
                )
            )
        }
        detail.formattedSessionPnL?.let { session ->
            if (detail.isOpen || detail.sessionPnL != detail.realizedPnL) {
                add(Triple("Session", session, detail.isPositiveSessionPnL == true))
            }
        }
    }
    if (chips.isEmpty()) return

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        chips.forEach { (label, value, positive) ->
            TradePnLChip(
                label = label,
                value = value,
                positive = positive,
                modifier = Modifier
                    .weight(1f)
                    .testTag("${testTagPrefix}Chip_$label")
            )
        }
    }
}

@Composable
private fun TradeDetailPriceStrip(detail: SessionTradeDetailUiState, testTagPrefix: String) {
    if (!detail.showPriceStrip) return
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        detail.formattedEntryPrice?.let { entry ->
            TradeMetricCell("Entry", entry, Modifier.weight(1f), testTag = "${testTagPrefix}Entry")
        }
        when {
            detail.formattedExitPrice != null -> {
                TradeMetricCell(
                    "Exit",
                    detail.formattedExitPrice,
                    Modifier.weight(1f),
                    testTag = "${testTagPrefix}Exit"
                )
            }
            detail.isOpen -> {
                TradeMetricCell(
                    "Status",
                    "Open",
                    Modifier.weight(1f),
                    valueColor = Color(0xFFFFB74D),
                    testTag = "${testTagPrefix}Status"
                )
            }
        }
        if (!detail.isOpen && detail.formattedExitPrice == null && detail.formattedEntryPrice != null) {
            detail.formattedRealizedPnL.let { pnl ->
                TradeMetricCell(
                    "P&L",
                    pnl,
                    Modifier.weight(1f),
                    valueColor = if (detail.isPositiveRealizedPnL) GainGreen else LossRed,
                    testTag = "${testTagPrefix}StripPnL"
                )
            }
        }
    }
}

@Composable
private fun TradeFillsTable(fills: List<SessionTradeFillUi>, testTagPrefix: String) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(SurfaceDark, RoundedCornerShape(6.dp))
            .padding(8.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Text("Fills", fontSize = 10.sp, fontWeight = FontWeight.Bold, color = TextSecondary)
        Row(modifier = Modifier.fillMaxWidth()) {
            Text("Leg", fontSize = 8.sp, color = TextSecondary, modifier = Modifier.width(44.dp))
            Text("Fill", fontSize = 8.sp, color = TextSecondary, modifier = Modifier.weight(1f))
            Text("Time", fontSize = 8.sp, color = TextSecondary, modifier = Modifier.width(52.dp))
            Text(
                "P&L",
                fontSize = 8.sp,
                color = TextSecondary,
                modifier = Modifier.width(56.dp),
                textAlign = TextAlign.End
            )
        }
        HorizontalDivider(color = TableHeaderBg)
        fills.forEachIndexed { index, fill ->
            if (index > 0) {
                HorizontalDivider(color = TableHeaderBg.copy(alpha = 0.5f))
            }
            TradeFillTableRow(fill, testTagPrefix)
        }
    }
}

@Composable
private fun TradeFillTableRow(fill: SessionTradeFillUi, testTagPrefix: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("${testTagPrefix}Fill_${fill.execId}"),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            fill.roleLabel,
            fontSize = 9.sp,
            fontWeight = FontWeight.Bold,
            color = Color(0xFF90CAF9),
            modifier = Modifier.width(44.dp)
        )
        Text(
            "${fill.sideLabel} ${fill.quantity} @ ${fill.formattedPrice}",
            fontSize = 10.sp,
            color = Color.White,
            modifier = Modifier.weight(1f),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        Text(
            fill.formattedTime,
            fontSize = 9.sp,
            color = TextSecondary,
            modifier = Modifier.width(52.dp),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        Text(
            text = fill.formattedRealizedPnL ?: "—",
            fontSize = 9.sp,
            fontWeight = FontWeight.Medium,
            color = when {
                fill.formattedRealizedPnL == null -> TextSecondary
                fill.isPositivePnL -> GainGreen
                else -> LossRed
            },
            modifier = Modifier.width(56.dp),
            textAlign = TextAlign.End
        )
    }
}

@Composable
fun TradeSideBadge(label: String, isLong: Boolean, modifier: Modifier = Modifier) {
    val color = if (isLong) GainGreen else LossRed
    Text(
        text = label.uppercase(),
        fontSize = 9.sp,
        fontWeight = FontWeight.Bold,
        color = color,
        modifier = modifier
            .background(color.copy(alpha = 0.15f), RoundedCornerShape(4.dp))
            .padding(horizontal = 6.dp, vertical = 2.dp)
    )
}

@Composable
fun TradeMetricCell(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
    valueColor: Color = Color.White,
    testTag: String? = null
) {
    Column(
        modifier = modifier
            .background(DarkBackground, RoundedCornerShape(6.dp))
            .padding(horizontal = 8.dp, vertical = 6.dp)
            .then(if (testTag != null) Modifier.testTag(testTag) else Modifier)
    ) {
        Text(label, fontSize = 9.sp, color = TextSecondary, maxLines = 1)
        Text(
            value,
            fontSize = 13.sp,
            fontWeight = FontWeight.SemiBold,
            color = valueColor,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
fun TradePnLChip(
    label: String,
    value: String,
    positive: Boolean,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .background(DarkBackground, RoundedCornerShape(6.dp))
            .padding(horizontal = 8.dp, vertical = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(label, fontSize = 9.sp, color = TextSecondary)
        Text(
            value,
            fontSize = 12.sp,
            fontWeight = FontWeight.SemiBold,
            color = if (positive) GainGreen else LossRed,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

private val SessionTradeDetailUiState.showPriceStrip: Boolean
    get() = formattedEntryPrice != null

private fun primaryPnLText(detail: SessionTradeDetailUiState): String =
    if (detail.isOpen) {
        detail.formattedUnrealizedPnL ?: detail.formattedRealizedPnL
    } else {
        detail.formattedRealizedPnL
    }

private fun primaryPnLColor(detail: SessionTradeDetailUiState): Color = when {
    detail.isOpen -> if ((detail.unrealizedPnL ?: 0.0) >= 0) GainGreen else LossRed
    else -> if (detail.isPositiveRealizedPnL) GainGreen else LossRed
}
