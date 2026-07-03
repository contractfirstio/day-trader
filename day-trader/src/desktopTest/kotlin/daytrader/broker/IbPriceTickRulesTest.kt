package daytrader.broker

import com.ib.client.ContractDetails
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class IbPriceTickRulesTest {
    @Test
    fun fromContractDetails_readsPositiveMinTick() {
        val details = ContractDetails()
        details.minTick(0.01)
        assertEquals(0.01, IbPriceTickRules.fromContractDetails(details))
    }

    @Test
    fun fromContractDetails_ignoresNonPositiveValues() {
        val details = ContractDetails()
        details.minTick(0.0)
        assertNull(IbPriceTickRules.fromContractDetails(details))
    }
}
