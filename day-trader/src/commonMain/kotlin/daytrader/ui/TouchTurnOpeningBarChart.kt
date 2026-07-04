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
import daytrader.domain.FiveMinuteConfirmationLogic
import daytrader.domain.FirstCandleCloseStatus
import daytrader.domain.FirstCandleColor
import daytrader.domain.OhlcBar
import daytrader.domain.TouchTurnLogic
import daytrader.presentation.Formatters
import daytrader.presentation.strategies.LiveChartPrices
import daytrader.presentation.strategies.TouchTurnQuoteStripUi
import daytrader.ui.theme.BrandRed
import daytrader.ui.theme.CandleGreen
import daytrader.ui.theme.CandleRed
import daytrader.ui.theme.DarkBackground
import daytrader.ui.theme.GainGreen
import daytrader.ui.theme.TableHeaderBg
import daytrader.ui.theme.TextSecondary
import kotlin.math.max

@Composable
fun TouchTurnOpeningBarChart(
    candle: OhlcBar,
    candleColor: FirstCandleColor,
    currencyCode: String,
    closeStatus: FirstCandleCloseStatus? = null,
    rangeThreshold: Double? = null,
    livePriceHistory: List<Double> = emptyList(),
    currentPrice: Double? = null,
    quoteStrip: TouchTurnQuoteStripUi? = null,
    listingExch: String? = quoteStrip?.listingExch,
    fiveMinuteBars: List<OhlcBar> = emptyList(),
    confirmedHammerBarTime: String? = null,
    sweepPrice: Double? = null,
    modifier: Modifier = Modifier
) {
    val bodyColor = when (candleColor) {
        FirstCandleColor.GREEN -> CandleGreen
        FirstCandleColor.RED -> CandleRed
        FirstCandleColor.DOJI -> TextSecondary
    }
    val priceSeries = remember(livePriceHistory, currentPrice) {
        buildLivePriceSeries(livePriceHistory, currentPrice)
    }
    val priceRange = remember(candle, priceSeries, fiveMinuteBars, sweepPrice) {
        openingBarChartPriceRange(candle, priceSeries, fiveMinuteBars, sweepPrice)
    }
    val chartHeight = if (closeStatus == FirstCandleCloseStatus.CLOSED) {
        TouchTurnChartDimensions.openingBarClosedHeight
    } else {
        TouchTurnChartDimensions.openingBarFormingHeight
    }
    val textMeasurer = rememberTextMeasurer()
    val labelStyle = androidx.compose.ui.text.TextStyle(color = TextSecondary, fontSize = 9.sp)
    val density = LocalDensity.current
    val wickWidthPx = with(density) { 2.dp.toPx() }
    val minBodyPx = with(density) { 4.dp.toPx() }
    val priceStrokePx = with(density) { 2.dp.toPx() }
    val currentDotRadiusPx = with(density) { 3.5.dp.toPx() }

    val statusLabel = when (closeStatus) {
        FirstCandleCloseStatus.FORMING -> "forming"
        FirstCandleCloseStatus.CLOSED -> "closed"
        FirstCandleCloseStatus.UNKNOWN -> null
        null -> null
    }
    val rangeLabel = rangeThreshold?.takeIf { it > 0.0 }?.let { threshold ->
        val passes = candle.range >= threshold
        buildString {
            append("Range ${Formatters.listingPricePlain(candle.range, currencyCode, listingExch)}")
            append(" · threshold ${Formatters.listingPricePlain(threshold, currencyCode, listingExch)}")
            if (closeStatus == FirstCandleCloseStatus.CLOSED) {
                append(if (passes) " · liquidity OK" else " · below threshold")
            }
        }
    }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(TableHeaderBg, RoundedCornerShape(8.dp))
            .padding(horizontal = 12.dp, vertical = 10.dp)
            .testTag("TouchTurnOpeningBarChart"),
        verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(4.dp)
    ) {
        Text(
            buildString {
                append("Opening 15-minute bar")
                statusLabel?.let { append(" · $it") }
                candle.time?.let { append(" · $it") }
            },
            fontSize = 11.sp,
            color = if (closeStatus == FirstCandleCloseStatus.CLOSED) GainGreen else TextSecondary,
            modifier = Modifier.testTag("TouchTurnOpeningBarChartTitle")
        )
        rangeLabel?.let { label ->
            Text(
                label,
                fontSize = 10.sp,
                color = TextSecondary,
                modifier = Modifier.testTag("TouchTurnOpeningBarChartRange")
            )
        }

        Canvas(
            modifier = Modifier
                .fillMaxWidth()
                .height(chartHeight)
                .padding(top = 4.dp)
                .testTag("TouchTurnOpeningBarChartCanvas")
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
            val candleCenterX = plotLeft + plotWidth * 0.28f
            val bodyWidth = (plotWidth * 0.14f).coerceIn(12f, 36f)

            fun yFor(price: Double): Float {
                val fraction = ((priceRange.max - price) / priceRange.span).toFloat().coerceIn(0f, 1f)
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

            drawRect(
                color = TableHeaderBg.copy(alpha = 0.45f),
                topLeft = Offset(candleCenterX - bodyWidth * 0.55f, yFor(candle.high)),
                size = Size(
                    bodyWidth * 1.1f,
                    (yFor(candle.low) - yFor(candle.high)).coerceAtLeast(1f)
                )
            )

            drawTouchTurnCandle(
                bar = candle,
                centerX = candleCenterX,
                bodyWidth = bodyWidth,
                bodyColor = bodyColor,
                yFor = ::yFor,
                minBodyPx = minBodyPx,
                wickWidthPx = wickWidthPx
            )

            if (priceSeries.isNotEmpty()) {
                val pathStartX = candleCenterX + bodyWidth * 0.65f
                val pathEndX = if (fiveMinuteBars.isEmpty()) {
                    plotRight
                } else {
                    plotLeft + plotWidth * 0.58f
                }
                val path = Path()
                priceSeries.forEachIndexed { index, price ->
                    val fraction = if (priceSeries.size <= 1) {
                        1f
                    } else {
                        index.toFloat() / (priceSeries.size - 1).coerceAtLeast(1)
                    }
                    val x = pathStartX + fraction * (pathEndX - pathStartX)
                    val point = Offset(x, yFor(price))
                    if (index == 0) path.moveTo(point.x, point.y) else path.lineTo(point.x, point.y)
                }
                drawPath(
                    path = path,
                    color = Color.White.copy(alpha = if (closeStatus == FirstCandleCloseStatus.CLOSED) 0.45f else 0.9f),
                    style = Stroke(width = priceStrokePx, cap = StrokeCap.Round)
                )
            }

            if (fiveMinuteBars.isNotEmpty()) {
                val fiveMinAreaLeft = plotLeft + plotWidth * 0.58f
                val fiveMinAreaWidth = plotRight - fiveMinAreaLeft
                val slotWidth = fiveMinAreaWidth / FiveMinuteConfirmationLogic.MAX_BARS.coerceAtLeast(1)
                val miniBodyWidth = (slotWidth * 0.42f).coerceIn(8f, 22f)
                fiveMinuteBars.forEachIndexed { index, bar ->
                    val centerX = fiveMinAreaLeft + slotWidth * (index + 0.5f)
                    val isHammer = bar.time == confirmedHammerBarTime
                    val barColor = if (isHammer) GainGreen else TextSecondary.copy(alpha = 0.8f)
                    drawTouchTurnCandle(
                        bar = bar,
                        centerX = centerX,
                        bodyWidth = miniBodyWidth,
                        bodyColor = barColor,
                        yFor = ::yFor,
                        minBodyPx = minBodyPx,
                        wickWidthPx = wickWidthPx
                    )
                }
            }

            sweepPrice?.let { sweep ->
                val sweepY = yFor(sweep)
                drawLine(
                    color = BrandRed.copy(alpha = 0.75f),
                    start = Offset(plotLeft, sweepY),
                    end = Offset(plotRight, sweepY),
                    strokeWidth = 1f,
                    pathEffect = PathEffect.dashPathEffect(floatArrayOf(6f, 6f))
                )
            }

            currentPrice?.takeIf { it > 0.0 && closeStatus != FirstCandleCloseStatus.CLOSED }?.let { current ->
                val x = plotRight
                val y = yFor(current)
                drawCircle(color = Color.White, radius = currentDotRadiusPx, center = Offset(x, y))
                drawCircle(
                    color = Color.White.copy(alpha = 0.25f),
                    radius = currentDotRadiusPx * 2.2f,
                    center = Offset(x, y)
                )
            }

            listOf(candle.open, candle.close).forEach { price ->
                val y = yFor(price)
                drawLine(
                    color = bodyColor.copy(alpha = 0.35f),
                    start = Offset(plotLeft, y),
                    end = Offset(candleCenterX - bodyWidth * 0.6f, y),
                    strokeWidth = 1f
                )
            }

            listOf(priceRange.max, priceRange.min).forEachIndexed { index, price ->
                val y = if (index == 0) plotTop else plotBottom
                val label = Formatters.listingPricePlain(price, currencyCode, listingExch)
                val layout = textMeasurer.measure(label, labelStyle)
                drawText(
                    textLayoutResult = layout,
                    topLeft = Offset(
                        (plotLeft - layout.size.width - 6f).coerceAtLeast(0f),
                        (y - layout.size.height / 2f).coerceIn(0f, size.height - layout.size.height)
                    )
                )
            }

            val ohlcLabels = listOf(
                "H" to candle.high,
                "L" to candle.low,
                "O" to candle.open,
                "C" to candle.close
            )
            ohlcLabels.forEach { (tag, price) ->
                val y = yFor(price)
                val label = "$tag ${Formatters.listingPricePlain(price, currencyCode, listingExch)}"
                val layout = textMeasurer.measure(label, labelStyle.copy(color = bodyColor.copy(alpha = 0.9f)))
                drawText(
                    textLayoutResult = layout,
                    topLeft = Offset(
                        (candleCenterX + bodyWidth * 0.65f).coerceAtMost(plotRight - layout.size.width),
                        (y - layout.size.height / 2f).coerceIn(plotTop, plotBottom - layout.size.height)
                    )
                )
            }
        }

        quoteStrip?.let { strip ->
            TouchTurnQuoteStrip(
                strip = strip,
                modifier = Modifier.padding(top = 2.dp)
            )
        }

        if (closeStatus == FirstCandleCloseStatus.FORMING && priceSeries.isNotEmpty()) {
            Text(
                "White trace = live marks during the forming bar.",
                fontSize = 9.sp,
                color = TextSecondary.copy(alpha = 0.85f)
            )
        }
        if (fiveMinuteBars.isNotEmpty()) {
            Text(
                "Right: evaluated 5m bars overlaid on the 15m opening bar price scale.",
                fontSize = 9.sp,
                color = TextSecondary.copy(alpha = 0.85f),
                modifier = Modifier.testTag("TouchTurnOpeningBarChartFiveMinOverlayCaption")
            )
        }
    }
}

private fun buildLivePriceSeries(history: List<Double>, currentPrice: Double?): List<Double> {
    val sanitized = LiveChartPrices.sanitize(history)
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

internal data class TouchTurnCandlePriceRange(val min: Double, val max: Double) {
    val span: Double get() = (max - min).coerceAtLeast(0.0001)
}

internal fun touchTurnCandlePriceRange(
    prices: List<Double>,
    fallbackMin: Double,
    fallbackMax: Double
): TouchTurnCandlePriceRange {
    val rawMin = prices.minOrNull() ?: fallbackMin
    val rawMax = prices.maxOrNull() ?: fallbackMax
    val pad = max((rawMax - rawMin) * 0.12, rawMax * 0.0005)
    return TouchTurnCandlePriceRange(min = rawMin - pad, max = rawMax + pad)
}

private fun openingBarChartPriceRange(
    candle: OhlcBar,
    livePrices: List<Double>,
    fiveMinuteBars: List<OhlcBar> = emptyList(),
    sweepPrice: Double? = null
): TouchTurnCandlePriceRange {
    val prices = buildList {
        add(candle.high)
        add(candle.low)
        add(candle.open)
        add(candle.close)
        addAll(LiveChartPrices.sanitize(livePrices))
        sweepPrice?.let { add(it) }
        fiveMinuteBars.forEach { bar ->
            add(bar.high)
            add(bar.low)
            add(bar.open)
            add(bar.close)
        }
    }
    return touchTurnCandlePriceRange(prices, candle.low, candle.high)
}

internal fun androidx.compose.ui.graphics.drawscope.DrawScope.drawTouchTurnCandle(
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
    drawLine(
        color = bodyColor,
        start = Offset(centerX, yHigh),
        end = Offset(centerX, yLow),
        strokeWidth = wickWidthPx,
        cap = StrokeCap.Round
    )
    val color = TouchTurnLogic.firstCandleColor(bar)
    if (color == FirstCandleColor.DOJI) {
        val halfW = bodyWidth / 2f
        drawLine(
            color = bodyColor,
            start = Offset(centerX - halfW, yClose),
            end = Offset(centerX + halfW, yClose),
            strokeWidth = wickWidthPx,
            cap = StrokeCap.Round
        )
    } else {
        val top = minOf(yOpen, yClose)
        val bottom = maxOf(yOpen, yClose)
        drawRect(
            color = bodyColor,
            topLeft = Offset(centerX - bodyWidth / 2f, top),
            size = Size(bodyWidth, (bottom - top).coerceAtLeast(minBodyPx))
        )
    }
}
