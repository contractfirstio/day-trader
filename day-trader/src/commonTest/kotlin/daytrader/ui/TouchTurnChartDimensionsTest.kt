package daytrader.ui

import androidx.compose.ui.unit.dp
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class TouchTurnChartDimensionsTest {

    @Test
    fun yForPrice_mapsExtremaToPlotEdges() {
        val chartHeight = TouchTurnChartDimensions.liveOrderCanvasHeight
        val priceMin = 95.0
        val priceMax = 110.0

        assertEquals(
            TouchTurnChartDimensions.plotPadTop,
            TouchTurnChartDimensions.yForPrice(priceMax, priceMin, priceMax, chartHeight)
        )
        assertEquals(
            TouchTurnChartDimensions.plotPadTop + TouchTurnChartDimensions.plotHeight(chartHeight),
            TouchTurnChartDimensions.yForPrice(priceMin, priceMin, priceMax, chartHeight)
        )
    }

    @Test
    fun levelLabelYOffset_centersLabelOnLine() {
        val chartHeight = TouchTurnChartDimensions.liveOrderCanvasHeight
        val lineY = 100.dp
        val labelHeight = TouchTurnChartDimensions.orderLevelLabelHeight

        assertEquals(
            lineY - labelHeight / 2,
            TouchTurnChartDimensions.levelLabelYOffset(lineY, chartHeight)
        )
    }

    @Test
    fun priceYFraction_isMonotonic() {
        val min = 100.0
        val max = 110.0
        val midFraction = TouchTurnChartDimensions.priceYFraction(105.0, min, max)
        val highFraction = TouchTurnChartDimensions.priceYFraction(108.0, min, max)
        assertTrue(highFraction < midFraction)
        assertEquals(0f, TouchTurnChartDimensions.priceYFraction(max, min, max))
        assertEquals(1f, TouchTurnChartDimensions.priceYFraction(min, min, max))
    }
}
