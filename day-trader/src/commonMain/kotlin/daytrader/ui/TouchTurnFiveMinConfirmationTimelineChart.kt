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
import daytrader.domain.FiveMinuteConfirmationLogic
import daytrader.domain.FiveMinuteConfirmationStatus
import daytrader.domain.OhlcBar
import daytrader.domain.TouchTurnLogic
import daytrader.domain.TouchTurnTradeSide
import daytrader.presentation.Formatters
import daytrader.ui.theme.BrandRed
import daytrader.ui.theme.CandleGreen
import daytrader.ui.theme.CandleRed
import daytrader.ui.theme.DarkBackground
import daytrader.ui.theme.GainGreen
import daytrader.ui.theme.TableHeaderBg
import daytrader.ui.theme.TextSecondary

@Composable
fun TouchTurnFiveMinConfirmationTimelineChart(
    fifteenMinuteBar: OhlcBar,
    fiveMinuteBars: List<OhlcBar>,
    sweepPrice: Double,
    currencyCode: String,
    tradeSide: TouchTurnTradeSide?,
    confirmedHammerBarTime: String?,
    confirmationStatus: FiveMinuteConfirmationStatus?,
    modifier: Modifier = Modifier
) {
    val fifteenColor = TouchTurnLogic.firstCandleColor(fifteenMinuteBar)
    val fifteenBodyColor = candleColorToCompose(fifteenColor)
    val priceRange = remember(fifteenMinuteBar, fiveMinuteBars, sweepPrice) {
        timelineChartPriceRange(fifteenMinuteBar, fiveMinuteBars, sweepPrice)
    }
    val chartHeight = TouchTurnChartDimensions.openingBarClosedHeight
    val textMeasurer = rememberTextMeasurer()
    val labelStyle = androidx.compose.ui.text.TextStyle(color = TextSecondary, fontSize = 9.sp)
    val density = LocalDensity.current
    val wickWidthPx = with(density) { 2.dp.toPx() }
    val minBodyPx = with(density) { 4.dp.toPx() }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(TableHeaderBg, RoundedCornerShape(8.dp))
            .padding(horizontal = 12.dp, vertical = 10.dp)
            .testTag("TouchTurnFiveMinTimelineChart"),
        verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(4.dp)
    ) {
        Text(
            "15-minute sweep bar vs 5-minute confirmation window",
            fontSize = 11.sp,
            color = GainGreen,
            modifier = Modifier.testTag("TouchTurnFiveMinTimelineChartTitle")
        )
        Text(
            "Three 5m bars (15 minutes) may confirm a hammer inside the 15m range after the sweep.",
            fontSize = 10.sp,
            color = TextSecondary,
            modifier = Modifier.testTag("TouchTurnFiveMinTimelineChartCaption")
        )

        Canvas(
            modifier = Modifier
                .fillMaxWidth()
                .height(chartHeight)
                .padding(top = 4.dp)
                .testTag("TouchTurnFiveMinTimelineChartCanvas")
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

            val yHigh = yFor(fifteenMinuteBar.high)
            val yLow = yFor(fifteenMinuteBar.low)
            drawRect(
                color = TextSecondary.copy(alpha = 0.08f),
                topLeft = Offset(plotLeft, yHigh),
                size = Size(plotWidth, (yLow - yHigh).coerceAtLeast(1f))
            )

            val slotCount = FiveMinuteConfirmationLogic.MAX_BARS
            val fifteenSlotWidth = plotWidth * 0.22f
            val fiveMinAreaLeft = plotLeft + fifteenSlotWidth + plotWidth * 0.06f
            val fiveMinAreaWidth = plotRight - fiveMinAreaLeft
            val slotWidth = fiveMinAreaWidth / slotCount
            val bodyWidth = (slotWidth * 0.42f).coerceIn(10f, 28f)

            val fifteenCenterX = plotLeft + fifteenSlotWidth * 0.5f
            drawRect(
                color = TableHeaderBg.copy(alpha = 0.45f),
                topLeft = Offset(fifteenCenterX - bodyWidth * 0.55f, yFor(fifteenMinuteBar.high)),
                size = Size(
                    bodyWidth * 1.1f,
                    (yFor(fifteenMinuteBar.low) - yFor(fifteenMinuteBar.high)).coerceAtLeast(1f)
                )
            )
            drawTouchTurnCandle(
                bar = fifteenMinuteBar,
                centerX = fifteenCenterX,
                bodyWidth = bodyWidth,
                bodyColor = fifteenBodyColor,
                yFor = ::yFor,
                minBodyPx = minBodyPx,
                wickWidthPx = wickWidthPx
            )

            listOf(fifteenMinuteBar.high to "15m H", fifteenMinuteBar.low to "15m L").forEach { (price, tag) ->
                val y = yFor(price)
                drawLine(
                    color = TextSecondary.copy(alpha = 0.35f),
                    start = Offset(plotLeft, y),
                    end = Offset(plotRight, y),
                    strokeWidth = 1f,
                    pathEffect = PathEffect.dashPathEffect(floatArrayOf(6f, 6f))
                )
                val layout = textMeasurer.measure(
                    "$tag ${Formatters.listingPricePlain(price, currencyCode)}",
                    labelStyle.copy(color = TextSecondary.copy(alpha = 0.75f))
                )
                drawText(
                    textLayoutResult = layout,
                    topLeft = Offset(
                        plotLeft + 2f,
                        (y - layout.size.height / 2f).coerceIn(plotTop, plotBottom - layout.size.height)
                    )
                )
            }

            val sweepY = yFor(sweepPrice)
            val sweepLabel = buildString {
                append("Sweep ")
                append(Formatters.listingPricePlain(sweepPrice, currencyCode))
            }
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
                    plotRight - sweepLayout.size.width,
                    (sweepY - sweepLayout.size.height - 2f).coerceAtLeast(plotTop)
                )
            )

            fiveMinuteBars.forEachIndexed { index, bar ->
                val centerX = fiveMinAreaLeft + slotWidth * (index + 0.5f)
                val isHammer = bar.time == confirmedHammerBarTime &&
                    confirmationStatus == FiveMinuteConfirmationStatus.CONFIRMED
                val isHammerCandidate = tradeSide != null &&
                    FiveMinuteConfirmationLogic.isHammerPattern(bar, tradeSide)
                val barColor = when {
                    isHammer -> GainGreen
                    isHammerCandidate -> CandleGreen.copy(alpha = 0.85f)
                    else -> TextSecondary.copy(alpha = 0.75f)
                }
                if (isHammer) {
                    drawRect(
                        color = GainGreen.copy(alpha = 0.12f),
                        topLeft = Offset(centerX - slotWidth * 0.45f, plotTop),
                        size = Size(slotWidth * 0.9f, plotHeight)
                    )
                }
                drawTouchTurnCandle(
                    bar = bar,
                    centerX = centerX,
                    bodyWidth = bodyWidth,
                    bodyColor = barColor,
                    yFor = ::yFor,
                    minBodyPx = minBodyPx,
                    wickWidthPx = wickWidthPx
                )
                bar.time?.let { time ->
                    val slotLabel = "5m ${index + 1}"
                    val layout = textMeasurer.measure(slotLabel, labelStyle)
                    drawText(
                        textLayoutResult = layout,
                        topLeft = Offset(
                            (centerX - layout.size.width / 2f).coerceIn(plotLeft, plotRight - layout.size.width),
                            plotBottom + 2f
                        )
                    )
                    val timeLayout = textMeasurer.measure(
                        time.trim(),
                        labelStyle.copy(fontSize = 8.sp)
                    )
                    drawText(
                        textLayoutResult = timeLayout,
                        topLeft = Offset(
                            (centerX - timeLayout.size.width / 2f).coerceIn(plotLeft, plotRight - timeLayout.size.width),
                            plotBottom + layout.size.height + 1f
                        )
                    )
                }
            }

            for (slot in fiveMinuteBars.size until slotCount) {
                val centerX = fiveMinAreaLeft + slotWidth * (slot + 0.5f)
                val layout = textMeasurer.measure("5m ${slot + 1}", labelStyle.copy(color = TextSecondary.copy(alpha = 0.4f)))
                drawText(
                    textLayoutResult = layout,
                    topLeft = Offset(centerX - layout.size.width / 2f, plotBottom + 2f)
                )
            }

            listOf(priceRange.max, priceRange.min).forEachIndexed { index, price ->
                val y = if (index == 0) plotTop else plotBottom
                val label = Formatters.listingPricePlain(price, currencyCode)
                val layout = textMeasurer.measure(label, labelStyle)
                drawText(
                    textLayoutResult = layout,
                    topLeft = Offset(
                        (plotLeft - layout.size.width - 6f).coerceAtLeast(0f),
                        (y - layout.size.height / 2f).coerceIn(0f, size.height - layout.size.height)
                    )
                )
            }

            val fifteenLabel = textMeasurer.measure("15m", labelStyle.copy(color = fifteenBodyColor.copy(alpha = 0.9f)))
            drawText(
                textLayoutResult = fifteenLabel,
                topLeft = Offset(fifteenCenterX - fifteenLabel.size.width / 2f, plotBottom + 2f)
            )
        }
    }
}

private fun candleColorToCompose(color: FirstCandleColor): Color = when (color) {
    FirstCandleColor.GREEN -> CandleGreen
    FirstCandleColor.RED -> CandleRed
    FirstCandleColor.DOJI -> TextSecondary
}

private fun timelineChartPriceRange(
    fifteenMinuteBar: OhlcBar,
    fiveMinuteBars: List<OhlcBar>,
    sweepPrice: Double
): TouchTurnCandlePriceRange {
    val prices = buildList {
        add(fifteenMinuteBar.high)
        add(fifteenMinuteBar.low)
        add(sweepPrice)
        fiveMinuteBars.forEach { bar ->
            add(bar.high)
            add(bar.low)
            add(bar.open)
            add(bar.close)
        }
    }
    val rawMin = prices.minOrNull() ?: fifteenMinuteBar.low
    val rawMax = prices.maxOrNull() ?: fifteenMinuteBar.high
    return touchTurnCandlePriceRange(prices, rawMin, rawMax)
}
