package daytrader.presentation.strategies

import kotlin.test.Test
import kotlin.test.assertEquals

class LiveChartPricesTest {

    @Test
    fun sanitize_keepsFinitePositivePrices() {
        assertEquals(listOf(100.0, 101.5), LiveChartPrices.sanitize(listOf(100.0, 101.5)))
    }

    @Test
    fun sanitize_dropsNonFiniteAndNonPositive() {
        assertEquals(
            listOf(100.0, 102.0),
            LiveChartPrices.sanitize(
                listOf(
                    100.0,
                    Double.NaN,
                    Double.POSITIVE_INFINITY,
                    0.0,
                    -1.0,
                    102.0
                )
            )
        )
    }
}
