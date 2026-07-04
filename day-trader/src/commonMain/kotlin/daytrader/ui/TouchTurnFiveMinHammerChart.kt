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
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import daytrader.domain.FirstCandleColor
import daytrader.domain.OhlcBar
import daytrader.domain.TouchTurnTradeSide
import daytrader.presentation.Formatters
import daytrader.presentation.strategies.FiveMinHammerBarDetailUi
import daytrader.ui.theme.BrandRed
import daytrader.ui.theme.CandleGreen
import daytrader.ui.theme.CandleRed
import daytrader.ui.theme.DarkBackground
import daytrader.ui.theme.GainGreen
import daytrader.ui.theme.TableHeaderBg
import daytrader.ui.theme.TextSecondary

@Composable
fun TouchTurnFiveMinHammerChart(
    hammer: OhlcBar,
    detail: FiveMinHammerBarDetailUi,
    fifteenMinuteBar: OhlcBar?,
    modifier: Modifier = Modifier
) {
    val bodyColor = when (detail.candleColor) {
        FirstCandleColor.GREEN -> CandleGreen
        FirstCandleColor.RED -> CandleRed
        FirstCandleColor.DOJI -> TextSecondary
    }
    val priceRange = remember(hammer, detail.sweepPrice, fifteenMinuteBar) {
        hammerBarChartPriceRange(hammer, detail.sweepPrice, fifteenMinuteBar)
    }
    val chartHeight = TouchTurnChartDimensions.openingBarClosedHeight
    val textMeasurer = rememberTextMeasurer()
    val labelStyle = androidx.compose.ui.text.TextStyle(color = TextSecondary, fontSize = 9.sp)
    val density = LocalDensity.current
    val wickWidthPx = with(density) { 2.dp.toPx() }
    val minBodyPx = with(density) { 4.dp.toPx() }

    val sideLabel = when (detail.tradeSide) {
        TouchTurnTradeSide.LONG -> "long reversal"
        TouchTurnTradeSide.SHORT -> "short reversal"
        null -> null
    }
    val sweepLabel = buildString {
        append("Sweep ")
        append(Formatters.listingPricePlain(detail.sweepPrice, detail.currency))
    }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(TableHeaderBg, RoundedCornerShape(8.dp))
            .padding(horizontal = 12.dp, vertical = 10.dp)
            .testTag("TouchTurnFiveMinHammerChart"),
        verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(4.dp)
    ) {
        Text(
            buildString {
                append("5-minute hammer bar")
                append(" · closed")
                detail.barTime?.let { append(" · $it") }
            },
            fontSize = 11.sp,
            color = GainGreen,
            modifier = Modifier.testTag("TouchTurnFiveMinHammerChartTitle")
        )
        sideLabel?.let { label ->
            Text(
                "Confirmed for $label entry at hammer close.",
                fontSize = 10.sp,
                color = TextSecondary,
                modifier = Modifier.testTag("TouchTurnFiveMinHammerChartSide")
            )
        }
        Text(
            sweepLabel,
            fontSize = 10.sp,
            color = TextSecondary,
            modifier = Modifier.testTag("TouchTurnFiveMinHammerChartSweep")
        )

        Canvas(
            modifier = Modifier
                .fillMaxWidth()
                .height(chartHeight)
                .padding(top = 4.dp)
                .testTag("TouchTurnFiveMinHammerChartCanvas")
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

            fifteenMinuteBar?.let { parent ->
                val yHigh = yFor(parent.high)
                val yLow = yFor(parent.low)
                drawRect(
                    color = TextSecondary.copy(alpha = 0.08f),
                    topLeft = Offset(plotLeft, yHigh),
                    size = Size(plotWidth, (yLow - yHigh).coerceAtLeast(1f))
                )
                listOf(parent.high to "15m H", parent.low to "15m L").forEach { (price, tag) ->
                    val y = yFor(price)
                    drawLine(
                        color = TextSecondary.copy(alpha = 0.35f),
                        start = Offset(plotLeft, y),
                        end = Offset(plotRight, y),
                        strokeWidth = 1f,
                        pathEffect = PathEffect.dashPathEffect(floatArrayOf(6f, 6f))
                    )
                    val layout = textMeasurer.measure(
                        "$tag ${Formatters.listingPricePlain(price, detail.currency)}",
                        labelStyle.copy(color = TextSecondary.copy(alpha = 0.75f))
                    )
                    drawText(
                        textLayoutResult = layout,
                        topLeft = Offset(
                            plotRight - layout.size.width,
                            (y - layout.size.height / 2f).coerceIn(plotTop, plotBottom - layout.size.height)
                        )
                    )
                }
            }

            val sweepY = yFor(detail.sweepPrice)
            drawLine(
                color = BrandRed.copy(alpha = 0.85f),
                start = Offset(plotLeft, sweepY),
                end = Offset(plotRight, sweepY),
                strokeWidth = 1.5f,
                pathEffect = PathEffect.dashPathEffect(floatArrayOf(8f, 6f))
            )
            val sweepLayout = textMeasurer.measure(
                sweepLabel,
                labelStyle.copy(color = BrandRed.copy(alpha = 0.9f))
            )
            drawText(
                textLayoutResult = sweepLayout,
                topLeft = Offset(
                    plotLeft + 4f,
                    (sweepY - sweepLayout.size.height - 2f).coerceAtLeast(plotTop)
                )
            )

            drawRect(
                color = TableHeaderBg.copy(alpha = 0.45f),
                topLeft = Offset(candleCenterX - bodyWidth * 0.55f, yFor(hammer.high)),
                size = Size(
                    bodyWidth * 1.1f,
                    (yFor(hammer.low) - yFor(hammer.high)).coerceAtLeast(1f)
                )
            )

            drawTouchTurnCandle(
                bar = hammer,
                centerX = candleCenterX,
                bodyWidth = bodyWidth,
                bodyColor = bodyColor,
                yFor = ::yFor,
                minBodyPx = minBodyPx,
                wickWidthPx = wickWidthPx
            )

            listOf(hammer.open, hammer.close).forEach { price ->
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
                val label = Formatters.listingPricePlain(price, detail.currency)
                val layout = textMeasurer.measure(label, labelStyle)
                drawText(
                    textLayoutResult = layout,
                    topLeft = Offset(
                        (plotLeft - layout.size.width - 6f).coerceAtLeast(0f),
                        (y - layout.size.height / 2f).coerceIn(0f, size.height - layout.size.height)
                    )
                )
            }

            listOf(
                "H" to hammer.high,
                "L" to hammer.low,
                "O" to hammer.open,
                "C" to hammer.close
            ).forEach { (tag, price) ->
                val y = yFor(price)
                val label = "$tag ${Formatters.listingPricePlain(price, detail.currency)}"
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
    }
}

private fun hammerBarChartPriceRange(
    hammer: OhlcBar,
    sweepPrice: Double,
    fifteenMinuteBar: OhlcBar?
): TouchTurnCandlePriceRange {
    val prices = buildList {
        add(hammer.high)
        add(hammer.low)
        add(hammer.open)
        add(hammer.close)
        add(sweepPrice)
        fifteenMinuteBar?.let {
            add(it.high)
            add(it.low)
        }
    }
    return touchTurnCandlePriceRange(prices, hammer.low, hammer.high)
}
