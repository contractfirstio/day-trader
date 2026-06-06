package daytrader.ui

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.ui.Alignment
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
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
import daytrader.presentation.strategies.TouchTurnPriceChartContext
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
    val showThrob = chart.executedLevels.isNotEmpty()
    val throbTransition = rememberInfiniteTransition(label = "touchTurnLiveOrderThrob")
    val throbAlpha by throbTransition.animateFloat(
        initialValue = if (showThrob) 0.2f else 1f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(animation = tween(550), repeatMode = RepeatMode.Reverse),
        label = "touchTurnLiveOrderThrobAlpha"
    )
    val throbStrokeBoost by throbTransition.animateFloat(
        initialValue = if (showThrob) 0f else 1f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(animation = tween(550), repeatMode = RepeatMode.Reverse),
        label = "touchTurnLiveOrderThrobStroke"
    )

    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(TableHeaderBg, RoundedCornerShape(8.dp))
            .padding(horizontal = 12.dp, vertical = 10.dp)
            .testTag("TouchTurnLiveOrderPriceChart")
    ) {
        val lastLabel = chart.currentPrice?.let { Formatters.moneyPlain(it, chart.currencyCode) }
            ?: priceSeries.lastOrNull()?.let { Formatters.moneyPlain(it, chart.currencyCode) }
        val titlePrefix = when (chart.context) {
            TouchTurnPriceChartContext.OPENING_BAR_FORMING ->
                "Live stream · opening 15m bar"
            TouchTurnPriceChartContext.ORDERS_AND_POSITION ->
                "Live price"
        }
        Text(
            text = buildString {
                append(titlePrefix)
                append(" · ")
                append(chart.symbol)
                if (lastLabel != null) {
                    append(" · ")
                    append(lastLabel)
                }
            },
            fontSize = 11.sp,
            color = TextSecondary
        )
        chart.statusHint?.let { hint ->
            Text(
                text = hint,
                fontSize = 10.sp,
                color = TextSecondary,
                modifier = Modifier.padding(top = 2.dp)
            )
        }
        chart.quoteStrip?.let { strip ->
            TouchTurnQuoteStrip(
                strip = strip,
                modifier = Modifier.padding(top = 4.dp)
            )
        }

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

        BoxWithConstraints(
            modifier = Modifier
                .fillMaxWidth()
                .height(TouchTurnChartDimensions.liveOrderCanvasHeight)
                .padding(top = 6.dp)
        ) {
            val labelColumnWidth = TouchTurnChartDimensions.orderLevelLabelColumnWidth
            val chartWidth = (maxWidth - labelColumnWidth).coerceAtLeast(80.dp)
            val chartHeight = maxHeight
            val priceMin = priceRange.priceMin
            val priceMax = priceRange.priceMax

            Box(
                modifier = Modifier
                    .width(chartWidth)
                    .fillMaxHeight()
            ) {
                Canvas(
                    modifier = Modifier
                        .fillMaxWidth()
                        .fillMaxHeight()
                        .testTag("TouchTurnLiveOrderPriceChartCanvas")
                ) {
                val plot = TouchTurnChartDimensions.plotBounds(size.width, size.height, density)
                val plotLeft = plot.left
                val plotRight = plot.right
                val plotTop = plot.top
                val plotBottom = plot.bottom
                val plotWidth = plot.width
                val plotHeight = plot.height

                fun yFor(price: Double): Float = plot.yForPrice(price, priceMin, priceMax)

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
                    val executed = level.kind in chart.executedLevels
                    drawLine(
                        color = color.copy(alpha = if (executed) 0.45f else 0.85f),
                        start = Offset(plotLeft, y),
                        end = Offset(plotRight, y),
                        strokeWidth = levelStrokePx,
                        pathEffect = dashEffect
                    )
                }

                if (priceSeries.isNotEmpty()) {
                    val path = Path()
                    when (priceSeries.size) {
                        1 -> {
                            val y = yFor(priceSeries.single())
                            path.moveTo(plotLeft, y)
                            path.lineTo(plotRight, y)
                        }
                        else -> priceSeries.forEachIndexed { index, price ->
                            val point = Offset(xFor(index, priceSeries.size), yFor(price))
                            if (index == 0) path.moveTo(point.x, point.y) else path.lineTo(point.x, point.y)
                        }
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

                if (showThrob) {
                    Canvas(
                        modifier = Modifier
                            .fillMaxWidth()
                            .fillMaxHeight()
                            .testTag("TouchTurnLiveOrderPriceChartThrob")
                    ) {
                    val plot = TouchTurnChartDimensions.plotBounds(size.width, size.height, density)
                    val plotLeft = plot.left
                    val plotRight = plot.right

                    fun yFor(price: Double): Float = plot.yForPrice(price, priceMin, priceMax)

                    chart.levels
                        .filter { it.kind in chart.executedLevels }
                        .forEach { level ->
                            val y = yFor(level.price)
                            val color = levelColor(level.kind)
                            val strokePx = levelStrokePx * (1.6f + throbStrokeBoost * 1.4f)
                            val glowAlpha = throbAlpha * 0.35f
                            drawLine(
                                color = color.copy(alpha = glowAlpha),
                                start = Offset(plotLeft, y),
                                end = Offset(plotRight, y),
                                strokeWidth = strokePx * 2.2f
                            )
                            drawLine(
                                color = color.copy(alpha = throbAlpha),
                                start = Offset(plotLeft, y),
                                end = Offset(plotRight, y),
                                strokeWidth = strokePx
                            )
                        }
                    }
                }
            }

            Box(modifier = Modifier.fillMaxSize()) {
                chart.levels.sortedByDescending { it.price }.forEach { level ->
                    val lineY = TouchTurnChartDimensions.yForPrice(level.price, priceMin, priceMax, chartHeight)
                    val yOffset = TouchTurnChartDimensions.levelLabelYOffset(lineY, chartHeight)
                    val color = levelColor(level.kind)
                    val executed = level.kind in chart.executedLevels
                    val labelColor = when {
                        executed -> color.copy(alpha = throbAlpha)
                        else -> color.copy(alpha = 0.85f)
                    }
                    Row(
                        modifier = Modifier
                            .align(Alignment.TopStart)
                            .offset(x = chartWidth, y = yOffset)
                            .width(labelColumnWidth)
                            .background(TableHeaderBg.copy(alpha = 0.95f), RoundedCornerShape(4.dp))
                            .padding(horizontal = 5.dp, vertical = 2.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            level.label,
                            fontSize = 8.sp,
                            color = labelColor,
                            fontWeight = if (executed) FontWeight.SemiBold else FontWeight.Normal,
                            maxLines = 1,
                            modifier = Modifier.weight(1f)
                        )
                        Text(
                            Formatters.moneyPlain(level.price, chart.currencyCode),
                            fontSize = 9.sp,
                            fontWeight = FontWeight.Medium,
                            color = Color.White
                        )
                    }
                }
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
                    if (showThrob) "Pulsing lines = filled" else "Dashed lines = working orders",
                    fontSize = 9.sp,
                    color = TextSecondary.copy(alpha = 0.85f)
                )
            }
        }
    }
}

private fun buildPriceSeries(history: List<Double>, currentPrice: Double?): List<Double> {
    // Guard against malformed chart snapshots (e.g. nullable platform values crossing boundaries).
    val sanitized = history.mapNotNull { value ->
        value.takeIf { it.isFinite() && it > 0.0 }
    }
    if (sanitized.isEmpty()) {
        return currentPrice?.takeIf { it.isFinite() && it > 0.0 }?.let { listOf(it) } ?: emptyList()
    }
    val last = sanitized.lastOrNull()
    return if (currentPrice != null &&
        currentPrice.isFinite() &&
        currentPrice > 0.0 &&
        currentPrice != last
    ) {
        sanitized + currentPrice
    } else {
        sanitized
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
