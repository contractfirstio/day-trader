package daytrader.ui

import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** Shared canvas heights for Touch Turn price charts (live stream, opening bar, order preview). */
object TouchTurnChartDimensions {
    val liveOrderCanvasHeight: Dp = 220.dp
    val openingBarFormingHeight: Dp = 220.dp
    val openingBarClosedHeight: Dp = 240.dp
    val orderPreviewHeight: Dp = 220.dp
    val orderLevelLabelColumnWidth: Dp = 104.dp

    /** Places order-level labels above the price line when possible, otherwise just below. */
    fun levelLabelYOffset(chartHeight: Dp, yFraction: Float): Dp {
        val lineY = chartHeight * yFraction
        val labelHeight = 18.dp
        val gap = 4.dp
        val above = lineY - labelHeight - gap
        return if (above >= 0.dp) above else (lineY + gap).coerceAtMost(chartHeight - labelHeight)
    }
}
