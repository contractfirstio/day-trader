package daytrader.broker

import kotlin.test.Test
import kotlin.test.assertEquals

class IbContractMapperTest {
    @Test
    fun contractDetailsLookupContracts_usSymbol_queriesUsdAndGbp() {
        val contracts = IbContractMapper.contractDetailsLookupContracts("NWG")
        assertEquals(2, contracts.size)
        assertEquals("USD", contracts[0].currency())
        assertEquals("GBP", contracts[1].currency())
        assertEquals("NWG", contracts[0].symbol())
        assertEquals("NWG", contracts[1].symbol())
    }

    @Test
    fun contractDetailsLookupContracts_hkUsesSehkContract() {
        val contracts = IbContractMapper.contractDetailsLookupContracts("00700")
        assertEquals(1, contracts.size)
        assertEquals("700", contracts[0].symbol())
        assertEquals("SEHK", contracts[0].exchange())
        assertEquals("HKD", contracts[0].currency())
    }
}
