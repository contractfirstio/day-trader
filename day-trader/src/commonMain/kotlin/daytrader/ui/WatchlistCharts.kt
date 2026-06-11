package daytrader.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import daytrader.domain.OhlcBar
import daytrader.presentation.Formatters
import daytrader.presentation.strategies.TouchTurnLiveOrderChartUiState
import daytrader.presentation.watchlist.WatchlistEntryChartsUi
import daytrader.ui.theme.CandleGreen
import daytrader.ui.theme.CandleRed
import daytrader.ui.theme.DarkBackground
import daytrader.ui.theme.TableHeaderBg
import daytrader.ui.theme.TextSecondary
import daytrader.ui.theme.TradeBlueBorder
import kotlin.math.max

@Composable
fun WatchlistEntryChartsPanel(
    charts: WatchlistEntryChartsUi,
    modifier: Modifier = Modifier
) {
    VerticalSplitPane(
        modifier = modifier.testTag("WatchlistEntryChartsPanel"),
        initialTopFraction = 0.55f,
        topContent = {
            WatchlistDailyPriceChart(
                symbol = charts.symbol,
                currencyCode = charts.currencyCode,
                bars = charts.dailyBars,
                loading = charts.dailyLoading,
                error = charts.dailyError,
                listingExch = charts.listingExch,
                modifier = Modifier.fillMaxSize()
            )
        },
        bottomContent = {
            WatchlistLivePriceSection(
                charts = charts,
                modifier = Modifier.fillMaxSize()
            )
        }
    )
}

@Composable
fun WatchlistDailyPriceChart(
    symbol: String,
    currencyCode: String,
    bars: List<OhlcBar>,
    loading: Boolean,
    error: String?,
    listingExch: String? = null,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .background(TableHeaderBg, RoundedCornerShape(8.dp))
            .padding(horizontal = 12.dp, vertical = 10.dp)
            .testTag("WatchlistDailyPriceChart")
    ) {
        val lastClose = bars.lastOrNull()?.close
        Text(
            text = buildString {
                append("Daily · last month · ")
                append(symbol)
                if (lastClose != null) {
                    append(" · ")
                    append(Formatters.listingPricePlain(lastClose, currencyCode, listingExch))
                }
            },
            fontSize = 11.sp,
            color = TextSecondary
        )

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .padding(top = 8.dp)
        ) {
            when {
                loading -> {
                    CircularProgressIndicator(
                        modifier = Modifier.align(Alignment.Center),
                        color = TradeBlueBorder,
                        strokeWidth = 2.dp
                    )
                }
                error != null -> {
                    Text(
                        text = error,
                        color = TextSecondary,
                        fontSize = 12.sp,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.align(Alignment.Center).padding(12.dp)
                    )
                }
                bars.isEmpty() -> {
                    Text(
                        text = "No daily history available.",
                        color = TextSecondary,
                        fontSize = 12.sp,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.align(Alignment.Center).padding(12.dp)
                    )
                }
                else -> {
                    WatchlistDailyCandlestickCanvas(
                        bars = bars,
                        currencyCode = currencyCode,
                        listingExch = listingExch,
                        modifier = Modifier.fillMaxSize()
                    )
                }
            }
        }
    }
}

@Composable
private fun WatchlistDailyCandlestickCanvas(
    bars: List<OhlcBar>,
    currencyCode: String,
    listingExch: String?,
    modifier: Modifier = Modifier
) {
    val priceRange = remember(bars) {
        val lows = bars.map { it.low }
        val highs = bars.map { it.high }
        val rawMin = lows.minOrNull() ?: 0.0
        val rawMax = highs.maxOrNull() ?: rawMin
        val pad = max((rawMax - rawMin) * 0.06, rawMax * 0.0005)
        rawMin - pad to rawMax + pad
    }
    val textMeasurer = rememberTextMeasurer()
    val labelStyle = androidx.compose.ui.text.TextStyle(color = TextSecondary, fontSize = 9.sp)
    val density = LocalDensity.current
    val minBodyPx = with(density) { 2.dp.toPx() }

    Canvas(modifier = modifier.testTag("WatchlistDailyPriceChartCanvas")) {
        val plot = TouchTurnChartDimensions.plotBounds(size.width, size.height, density)
        val (priceMin, priceMax) = priceRange
        val gridColor = TextSecondary.copy(alpha = 0.22f)
        for (i in 0..3) {
            val y = plot.top + (plot.height * i / 3f)
            drawLine(
                color = gridColor,
                start = Offset(plot.left, y),
                end = Offset(plot.right, y),
                strokeWidth = 1f
            )
        }
        drawRect(
            color = DarkBackground.copy(alpha = 0.35f),
            topLeft = Offset(plot.left, plot.top),
            size = Size(plot.width, plot.height)
        )

        val count = bars.size
        val slotWidth = plot.width / count.coerceAtLeast(1)
        val bodyWidth = slotWidth * 0.55f

        bars.forEachIndexed { index, bar ->
            val centerX = plot.left + slotWidth * index + slotWidth / 2f
            val isUp = bar.close >= bar.open
            val color = if (isUp) CandleGreen else CandleRed
            val yHigh = plot.yForPrice(bar.high, priceMin, priceMax)
            val yLow = plot.yForPrice(bar.low, priceMin, priceMax)
            val yOpen = plot.yForPrice(bar.open, priceMin, priceMax)
            val yClose = plot.yForPrice(bar.close, priceMin, priceMax)

            drawLine(
                color = color,
                start = Offset(centerX, yHigh),
                end = Offset(centerX, yLow),
                strokeWidth = 1.2f,
                cap = StrokeCap.Round
            )

            val top = minOf(yOpen, yClose)
            val bottom = maxOf(yOpen, yClose)
            val bodyHeight = (bottom - top).coerceAtLeast(minBodyPx)
            drawRect(
                color = color,
                topLeft = Offset(centerX - bodyWidth / 2f, top),
                size = Size(bodyWidth, bodyHeight)
            )
        }

        val highLabel = Formatters.listingPricePlain(priceMax, currencyCode, listingExch)
        val lowLabel = Formatters.listingPricePlain(priceMin, currencyCode, listingExch)
        drawText(
            textMeasurer = textMeasurer,
            text = highLabel,
            style = labelStyle,
            topLeft = Offset(2f, plot.top)
        )
        drawText(
            textMeasurer = textMeasurer,
            text = lowLabel,
            style = labelStyle,
            topLeft = Offset(2f, plot.bottom - 10f)
        )
    }
}

@Composable
private fun WatchlistLivePriceSection(
    charts: WatchlistEntryChartsUi,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .padding(top = 8.dp)
            .background(TableHeaderBg, RoundedCornerShape(8.dp))
            .padding(horizontal = 12.dp, vertical = 10.dp)
            .testTag("WatchlistLivePriceSection")
    ) {
        if (
            !charts.liveAvailable &&
            charts.livePriceHistory.isEmpty() &&
            charts.liveCurrentPrice == null &&
            charts.orderLevels.isEmpty()
        ) {
            Text(
                text = "Live price · ${charts.symbol}",
                fontSize = 11.sp,
                color = TextSecondary
            )
            Box(
                modifier = Modifier.fillMaxSize().padding(top = 8.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = charts.liveStatusLabel ?: "No live market data for this symbol.",
                    color = TextSecondary,
                    fontSize = 12.sp,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(12.dp)
                )
            }
            return
        }

        val chart = TouchTurnLiveOrderChartUiState(
            symbol = charts.symbol,
            currencyCode = charts.currencyCode,
            priceHistory = charts.livePriceHistory,
            currentPrice = charts.liveCurrentPrice,
            levels = charts.orderLevels,
            executedLevels = charts.executedLevels,
            statusHint = charts.liveStatusLabel,
            quoteStrip = charts.liveQuoteStrip
        )
        TouchTurnLiveOrderPriceChart(
            chart = chart,
            modifier = Modifier.fillMaxSize()
        )
    }
}
