package daytrader.broker

import com.ib.client.ContractDetails
import com.ib.client.Decimal
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class IbOrderSizeRulesTest {
    @Test
    fun fromContractDetails_readsMinSizeAndIncrement() {
        val details = ContractDetails()
        details.minSize(Decimal.get(1_000))
        details.sizeIncrement(Decimal.get(1_000))

        val rules = IbOrderSizeRules.fromContractDetails(details)

        assertEquals(1_000, rules.minOrderSize)
        assertEquals(1_000, rules.orderSizeIncrement)
    }

    @Test
    fun fromContractDetails_fallsBackToSuggestedIncrement() {
        val details = ContractDetails()
        details.minSize(Decimal.get(100))
        details.suggestedSizeIncrement(Decimal.get(50))

        val rules = IbOrderSizeRules.fromContractDetails(details)

        assertEquals(100, rules.minOrderSize)
        assertEquals(50, rules.orderSizeIncrement)
    }

    @Test
    fun fromContractDetails_ignoresZeroOrInvalidValues() {
        val details = ContractDetails()
        details.minSize(Decimal.get(0))

        val rules = IbOrderSizeRules.fromContractDetails(details)

        assertNull(rules.minOrderSize)
        assertNull(rules.orderSizeIncrement)
    }
}
