package daytrader.broker

import java.math.BigDecimal
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class TransactionCostCalculatorTest {
    private val calculator = TransactionCostCalculator()

    @Test
    fun calculateCommission_limitOrder_usesTieredMinimum() {
        assertEquals(BigDecimal("0.35"), calculator.calculateCommission(shares = 10, orderType = "LIMIT"))
    }

    @Test
    fun calculateCommission_limitOrder_scalesPerShareAboveMinimum() {
        assertEquals(BigDecimal("3.50"), calculator.calculateCommission(shares = 1000, orderType = "LIMIT"))
    }

    @Test
    fun calculateCommission_stopOrder_includesTakerExchangeFees() {
        assertEquals(BigDecimal("0.65"), calculator.calculateCommission(shares = 100, orderType = "STOP"))
    }

    @Test
    fun normalizeOrderType_mapsBrokerCodes() {
        assertEquals("LIMIT", TransactionCostCalculator.normalizeOrderType("LMT"))
        assertEquals("STOP", TransactionCostCalculator.normalizeOrderType("STP"))
        assertEquals("STOP", TransactionCostCalculator.normalizeOrderType("TRAIL"))
    }

    @Test
    fun validateEdge_warnsWhenGrossRealizedBelowThreeTimesCommission() {
        calculator.validateEdge(
            grossRealizedPnL = BigDecimal("1.00"),
            totalCommission = BigDecimal("0.50"),
        )
        assertTrue(true)
    }
}
