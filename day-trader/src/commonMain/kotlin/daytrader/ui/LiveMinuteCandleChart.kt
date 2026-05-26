package daytrader.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import daytrader.domain.FirstCandleColor
import daytrader.domain.OhlcBar
import daytrader.domain.TouchTurnLogic
import daytrader.presentation.Formatters
import daytrader.presentation.strategies.LiveMinuteCandleUi
import daytrader.presentation.strategies.LivePriceChartUiState
import daytrader.ui.theme.BrandRed
import daytrader.ui.theme.CandleGreen
import daytrader.ui.theme.CandleRed
import daytrader.ui.theme.DarkBackground
import daytrader.ui.theme.TableHeaderBg
import daytrader.ui.theme.TextSecondary
import kotlin.math.max

@Composable
fun LiveMinuteCandleChart(
    chart: LivePriceChartUiState,
    modifier: Modifier = Modifier
) {
    val candles = chart.candles
    val priceRange = remember(candles) { chartPriceRange(candles) }
    val minBodyPx = with(LocalDensity.current) { 4.dp.toPx() }
    val wickWidthPx = with(LocalDensity.current) { 1.5.dp.toPx() }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(TableHeaderBg, RoundedCornerShape(8.dp))
            .padding(horizontal = 12.dp, vertical = 10.dp)
            .testTag("LiveMinuteCandleChart")
    ) {
        val lastClose = candles.lastOrNull()?.bar?.close
        Text(
            text = buildString {
                append("1 min candles · ${chart.symbol}")
                if (lastClose != null) {
                    append(" · ")
                    append(Formatters.moneyPlain(lastClose, chart.currencyCode))
                }
            },
            fontSize = 11.sp,
            color = BrandRed,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )

        if (candles.isEmpty()) {
            Text(
                text = "Waiting for live prices…",
                fontSize = 12.sp,
                color = TextSecondary,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp, bottom = 4.dp)
                    .testTag("LiveMinuteCandleChartEmpty")
            )
            return
        }

        val textMeasurer = rememberTextMeasurer()
        val labelStyle = androidx.compose.ui.text.TextStyle(
            color = TextSecondary,
            fontSize = 9.sp
        )

        Canvas(
            modifier = Modifier
                .fillMaxWidth()
                .height(140.dp)
                .padding(top = 6.dp)
                .testTag("LiveMinuteCandleChartCanvas")
        ) {
            val labelPadLeft = 44f
            val labelPadRight = 6f
            val labelPadTop = 6f
            val labelPadBottom = 16f
            val plotLeft = labelPadLeft
            val plotRight = size.width - labelPadRight
            val plotTop = labelPadTop
            val plotBottom = size.height - labelPadBottom
            val plotWidth = (plotRight - plotLeft).coerceAtLeast(1f)
            val plotHeight = (plotBottom - plotTop).coerceAtLeast(1f)
            val slotWidth = plotWidth / candles.size.coerceAtLeast(1)

            fun yFor(price: Double): Float {
                val fraction = ((priceRange.max - price) / priceRange.span).toFloat()
                    .coerceIn(0f, 1f)
                return plotTop + fraction * plotHeight
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
                size = Size(plotWidth, plotHeight)
            )

            candles.forEachIndexed { index, candle ->
                val centerX = plotLeft + slotWidth * (index + 0.5f)
                val bodyWidth = (slotWidth * 0.62f).coerceAtMost(18f).coerceAtLeast(4f)
                val color = candleColor(TouchTurnLogic.firstCandleColor(candle.bar))
                val alpha = if (candle.isForming) 0.92f else 1f
                drawMinuteCandle(
                    bar = candle.bar,
                    centerX = centerX,
                    bodyWidth = bodyWidth,
                    bodyColor = color.copy(alpha = alpha),
                    yFor = ::yFor,
                    minBodyPx = minBodyPx,
                    wickWidthPx = wickWidthPx
                )
            }

            listOf(priceRange.max, priceRange.min).forEachIndexed { index, price ->
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

            val formingStart = candles.lastOrNull()?.takeIf { it.isForming }?.bucketStartMillis
            val timeLabels = buildList {
                if (formingStart != null) {
                    add(formingStart to "now")
                }
                val completedCount = if (formingStart != null) candles.size - 1 else candles.size
                if (completedCount >= 2) {
                    val start = candles.first().bucketStartMillis
                    add(start to "-${(completedCount - 1)}m")
                }
            }
            timeLabels.forEach { (bucketStart, label) ->
                val index = candles.indexOfFirst { it.bucketStartMillis == bucketStart }
                if (index < 0) return@forEach
                val x = plotLeft + slotWidth * (index + 0.5f)
                val layout = textMeasurer.measure(label, labelStyle)
                drawText(
                    textLayoutResult = layout,
                    topLeft = Offset(
                        (x - layout.size.width / 2f).coerceIn(0f, size.width - layout.size.width),
                        plotBottom + 3f
                    )
                )
            }
        }
    }
}

private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawMinuteCandle(
    bar: OhlcBar,
    centerX: Float,
    bodyWidth: Float,
    bodyColor: Color,
    yFor: (Double) -> Float,
    minBodyPx: Float,
    wickWidthPx: Float
) {
    val yHigh = yFor(bar.high)
    val yLow = yFor(bar.low)
    val yOpen = yFor(bar.open)
    val yClose = yFor(bar.close)
    fun drawWick(fromY: Float, toY: Float) {
        if (toY - fromY < 0.5f) return
        drawLine(
            color = bodyColor,
            start = Offset(centerX, fromY),
            end = Offset(centerX, toY),
            strokeWidth = wickWidthPx,
            cap = StrokeCap.Round
        )
    }

    val color = TouchTurnLogic.firstCandleColor(bar)
    when (color) {
        FirstCandleColor.DOJI -> {
            drawWick(yHigh, yClose)
            drawWick(yClose, yLow)
            val halfW = bodyWidth / 2f
            drawLine(
                color = bodyColor,
                start = Offset(centerX - halfW, yClose),
                end = Offset(centerX + halfW, yClose),
                strokeWidth = wickWidthPx,
                cap = StrokeCap.Round
            )
        }
        else -> {
            var bodyTop = minOf(yOpen, yClose)
            var bodyBottom = maxOf(yOpen, yClose)
            if (bodyBottom - bodyTop < minBodyPx) {
                val mid = (bodyTop + bodyBottom) / 2f
                bodyTop = mid - minBodyPx / 2f
                bodyBottom = mid + minBodyPx / 2f
            }
            bodyTop = bodyTop.coerceAtLeast(yHigh)
            bodyBottom = bodyBottom.coerceAtMost(yLow)

            drawWick(yHigh, bodyTop)
            drawWick(bodyBottom, yLow)
            drawRect(
                color = bodyColor,
                topLeft = Offset(centerX - bodyWidth / 2f, bodyTop),
                size = Size(bodyWidth, (bodyBottom - bodyTop).coerceAtLeast(0.5f))
            )
        }
    }
}

private fun candleColor(color: FirstCandleColor): Color = when (color) {
    FirstCandleColor.GREEN -> CandleGreen
    FirstCandleColor.RED -> CandleRed
    FirstCandleColor.DOJI -> TextSecondary
}

private data class ChartPriceRange(val min: Double, val max: Double) {
    val span: Double get() = (max - min).coerceAtLeast(0.0001)
}

private fun chartPriceRange(candles: List<LiveMinuteCandleUi>): ChartPriceRange {
    val lows = candles.map { it.bar.low }
    val highs = candles.map { it.bar.high }
    val rawMin = lows.minOrNull() ?: 0.0
    val rawMax = highs.maxOrNull() ?: rawMin
    val pad = max((rawMax - rawMin) * 0.08, rawMax * 0.0005)
    return ChartPriceRange(min = rawMin - pad, max = rawMax + pad)
}
