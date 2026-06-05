package daytrader.ui

import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** Shared canvas heights and plot geometry for Touch Turn price charts. */
object TouchTurnChartDimensions {
    val liveOrderCanvasHeight: Dp = 260.dp
    val openingBarFormingHeight: Dp = 220.dp
    val openingBarClosedHeight: Dp = 240.dp
    val orderPreviewHeight: Dp = 220.dp
    val orderLevelLabelColumnWidth: Dp = 104.dp

    val plotPadTop: Dp = 6.dp
    val plotPadBottom: Dp = 14.dp
    val plotPadLeft: Dp = 44.dp
    val plotPadRight: Dp = 4.dp
    val orderLevelLabelHeight: Dp = 18.dp

    fun priceSpan(priceMin: Double, priceMax: Double): Double =
        (priceMax - priceMin).coerceAtLeast(0.0001)

    fun priceYFraction(price: Double, priceMin: Double, priceMax: Double): Float =
        ((priceMax - price) / priceSpan(priceMin, priceMax)).toFloat().coerceIn(0f, 1f)

    fun plotHeight(chartHeight: Dp): Dp =
        (chartHeight - plotPadTop - plotPadBottom).coerceAtLeast(1.dp)

    /** Y position of a price within the chart canvas (plot-area coordinates). */
    fun yForPrice(price: Double, priceMin: Double, priceMax: Double, chartHeight: Dp): Dp {
        val fraction = priceYFraction(price, priceMin, priceMax)
        return plotPadTop + plotHeight(chartHeight) * fraction
    }

    /** Vertically centers a level label row on the dashed price line. */
    fun levelLabelYOffset(lineY: Dp, chartHeight: Dp, labelHeight: Dp = orderLevelLabelHeight): Dp =
        (lineY - labelHeight / 2).coerceIn(0.dp, chartHeight - labelHeight)

    fun plotBounds(
        canvasWidth: Float,
        canvasHeight: Float,
        density: Density,
        includeHorizontalPadding: Boolean = true,
    ): TouchTurnPlotBounds {
        val left = if (includeHorizontalPadding) with(density) { plotPadLeft.toPx() } else 0f
        val right = if (includeHorizontalPadding) {
            canvasWidth - with(density) { plotPadRight.toPx() }
        } else {
            canvasWidth
        }
        val top = with(density) { plotPadTop.toPx() }
        val bottom = canvasHeight - with(density) { plotPadBottom.toPx() }
        return TouchTurnPlotBounds(left = left, right = right, top = top, bottom = bottom)
    }
}

data class TouchTurnPlotBounds(
    val left: Float,
    val right: Float,
    val top: Float,
    val bottom: Float,
) {
    val width: Float get() = (right - left).coerceAtLeast(1f)
    val height: Float get() = (bottom - top).coerceAtLeast(1f)

    fun yForPrice(price: Double, priceMin: Double, priceMax: Double): Float {
        val fraction = TouchTurnChartDimensions.priceYFraction(price, priceMin, priceMax)
        return top + fraction * height
    }
}
