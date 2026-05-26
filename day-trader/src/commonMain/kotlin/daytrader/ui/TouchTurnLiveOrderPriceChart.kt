package daytrader.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import daytrader.presentation.Formatters
import daytrader.presentation.strategies.TouchTurnLiveOrderChartUiState
import daytrader.presentation.strategies.TouchTurnOrderLevelKind
import daytrader.presentation.strategies.TouchTurnOrderLevelUi
import daytrader.ui.theme.DarkBackground
import daytrader.ui.theme.GainGreen
import daytrader.ui.theme.LossRed
import daytrader.ui.theme.TableHeaderBg
import daytrader.ui.theme.TextSecondary
import kotlin.math.max

private val EntryLevelColor = Color(0xFF42A5F5)

@Composable
fun TouchTurnLiveOrderPriceChart(
    chart: TouchTurnLiveOrderChartUiState,
    modifier: Modifier = Modifier
) {
    val priceSeries = remember(chart.priceHistory, chart.currentPrice) {
        buildPriceSeries(chart.priceHistory, chart.currentPrice)
    }
    val priceRange = remember(priceSeries, chart.levels) {
        orderChartPriceRange(priceSeries, chart.levels)
    }
    val textMeasurer = rememberTextMeasurer()
    val labelStyle = androidx.compose.ui.text.TextStyle(color = TextSecondary, fontSize = 9.sp)
    val density = LocalDensity.current
    val levelStrokePx = with(density) { 1.5.dp.toPx() }
    val priceStrokePx = with(density) { 2.dp.toPx() }
    val currentDotRadiusPx = with(density) { 3.5.dp.toPx() }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(TableHeaderBg, RoundedCornerShape(8.dp))
            .padding(horizontal = 12.dp, vertical = 10.dp)
            .testTag("TouchTurnLiveOrderPriceChart")
    ) {
        val lastLabel = chart.currentPrice?.let { Formatters.moneyPlain(it, chart.currencyCode) }
            ?: priceSeries.lastOrNull()?.let { Formatters.moneyPlain(it, chart.currencyCode) }
        Text(
            text = buildString {
                append("Live price · ${chart.symbol}")
                if (lastLabel != null) {
                    append(" · ")
                    append(lastLabel)
                }
            },
            fontSize = 11.sp,
            color = TextSecondary
        )

        if (priceSeries.isEmpty() && chart.levels.isEmpty()) {
            Text(
                text = "Waiting for live prices…",
                fontSize = 12.sp,
                color = TextSecondary,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp)
                    .testTag("TouchTurnLiveOrderPriceChartEmpty")
            )
            return
        }

        Canvas(
            modifier = Modifier
                .fillMaxWidth()
                .height(148.dp)
                .padding(top = 6.dp)
                .testTag("TouchTurnLiveOrderPriceChartCanvas")
        ) {
            val labelPadLeft = 44f
            val labelPadRight = 8f
            val labelPadTop = 6f
            val labelPadBottom = 14f
            val plotLeft = labelPadLeft
            val plotRight = size.width - labelPadRight
            val plotTop = labelPadTop
            val plotBottom = size.height - labelPadBottom
            val plotWidth = (plotRight - plotLeft).coerceAtLeast(1f)
            val plotHeight = (plotBottom - plotTop).coerceAtLeast(1f)

            fun yFor(price: Double): Float {
                val fraction = ((priceRange.priceMax - price) / priceRange.priceSpan).toFloat().coerceIn(0f, 1f)
                return plotTop + fraction * plotHeight
            }

            fun xFor(index: Int, count: Int): Float {
                if (count <= 1) return plotRight
                return plotLeft + (index.toFloat() / (count - 1).coerceAtLeast(1)) * plotWidth
            }

            val gridColor = TextSecondary.copy(alpha = 0.22f)
            for (i in 0..3) {
                val y = plotTop + (plotHeight * i / 3f)
                drawLine(
                    color = gridColor,
                    start = Offset(plotLeft, y),
                    end = Offset(plotRight, y),
                    strokeWidth = 1f
                )
            }

            drawRect(
                color = DarkBackground.copy(alpha = 0.35f),
                topLeft = Offset(plotLeft, plotTop),
                size = androidx.compose.ui.geometry.Size(plotWidth, plotHeight)
            )

            val dashEffect = PathEffect.dashPathEffect(floatArrayOf(8f, 6f), 0f)
            chart.levels.forEach { level ->
                val y = yFor(level.price)
                val color = levelColor(level.kind)
                drawLine(
                    color = color.copy(alpha = 0.85f),
                    start = Offset(plotLeft, y),
                    end = Offset(plotRight, y),
                    strokeWidth = levelStrokePx,
                    pathEffect = dashEffect
                )
                val label = "${level.label} ${Formatters.moneyPlain(level.price, chart.currencyCode)}"
                val layout = textMeasurer.measure(label, labelStyle.copy(color = color))
                drawText(
                    textLayoutResult = layout,
                    topLeft = Offset(
                        (plotLeft - layout.size.width - 6f).coerceAtLeast(0f),
                        (y - layout.size.height / 2f).coerceIn(0f, size.height - layout.size.height)
                    )
                )
            }

            if (priceSeries.size >= 2) {
                val path = Path()
                priceSeries.forEachIndexed { index, price ->
                    val point = Offset(xFor(index, priceSeries.size), yFor(price))
                    if (index == 0) path.moveTo(point.x, point.y) else path.lineTo(point.x, point.y)
                }
                drawPath(
                    path = path,
                    color = Color.White.copy(alpha = 0.9f),
                    style = Stroke(width = priceStrokePx, cap = StrokeCap.Round)
                )
            }

            chart.currentPrice?.takeIf { it > 0.0 }?.let { current ->
                val x = plotRight
                val y = yFor(current)
                drawCircle(
                    color = Color.White,
                    radius = currentDotRadiusPx,
                    center = Offset(x, y)
                )
                drawCircle(
                    color = Color.White.copy(alpha = 0.25f),
                    radius = currentDotRadiusPx * 2.2f,
                    center = Offset(x, y)
                )
            }

            listOf(priceRange.priceMax, priceRange.priceMin).forEachIndexed { index, price ->
                val y = if (index == 0) plotTop else plotBottom
                val label = Formatters.moneyPlain(price, chart.currencyCode)
                val layout = textMeasurer.measure(label, labelStyle)
                drawText(
                    textLayoutResult = layout,
                    topLeft = Offset(
                        (plotLeft - layout.size.width - 6f).coerceAtLeast(0f),
                        (y - layout.size.height / 2f).coerceIn(0f, size.height - layout.size.height)
                    )
                )
            }
        }

        if (chart.levels.isNotEmpty()) {
            Row(
                modifier = Modifier.padding(top = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                LiveOrderChartLegendDot("Entry", EntryLevelColor)
                LiveOrderChartLegendDot("TP", GainGreen)
                LiveOrderChartLegendDot("SL", LossRed)
                Text(
                    "Levels drop off when their order fills",
                    fontSize = 9.sp,
                    color = TextSecondary.copy(alpha = 0.85f)
                )
            }
        }
    }
}

private fun buildPriceSeries(history: List<Double>, currentPrice: Double?): List<Double> {
    if (history.isEmpty()) {
        return currentPrice?.takeIf { it > 0.0 }?.let { listOf(it) } ?: emptyList()
    }
    val last = history.last()
    return if (currentPrice != null && currentPrice > 0.0 && currentPrice != last) {
        history + currentPrice
    } else {
        history
    }
}

private data class OrderChartPriceRange(val priceMin: Double, val priceMax: Double) {
    val priceSpan: Double get() = (priceMax - priceMin).coerceAtLeast(0.0001)
}

private fun orderChartPriceRange(
    priceSeries: List<Double>,
    levels: List<TouchTurnOrderLevelUi>
): OrderChartPriceRange {
    val prices = priceSeries + levels.map { it.price }
    val rawMin = prices.minOrNull() ?: 0.0
    val rawMax = prices.maxOrNull() ?: rawMin
    val pad = max((rawMax - rawMin) * 0.08, rawMax * 0.0005)
    return OrderChartPriceRange(priceMin = rawMin - pad, priceMax = rawMax + pad)
}

@Composable
private fun LiveOrderChartLegendDot(label: String, color: Color) {
    Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        Box(
            modifier = Modifier
                .size(8.dp)
                .background(color, RoundedCornerShape(2.dp))
        )
        Text(label, fontSize = 9.sp, color = TextSecondary)
    }
}

private fun levelColor(kind: TouchTurnOrderLevelKind): Color = when (kind) {
    TouchTurnOrderLevelKind.ENTRY -> EntryLevelColor
    TouchTurnOrderLevelKind.TAKE_PROFIT -> GainGreen
    TouchTurnOrderLevelKind.STOP_LOSS -> LossRed
    TouchTurnOrderLevelKind.OTHER -> TextSecondary
}
