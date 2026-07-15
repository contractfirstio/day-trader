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
    fun macroBenchmarkContractCandidates_hsiTriesHkfeThenHkexThenSmart() {
        val contracts = IbContractMapper.macroBenchmarkContractCandidates("HSI")
        assertEquals(3, contracts.size)
        assertEquals("HSI", contracts[0].symbol())
        assertEquals("HKFE", contracts[0].exchange())
        assertEquals("HKD", contracts[0].currency())
        assertEquals("HSI", contracts[1].symbol())
        assertEquals("HKEX", contracts[1].exchange())
        assertEquals("HSI", contracts[2].symbol())
        assertEquals("SMART", contracts[2].exchange())
        assertEquals("HKFE", contracts[2].primaryExch())
    }

    @Test
    fun macroBenchmarkContractCandidates_ukxTriesIceeuThenSmartThenIsfEtf() {
        val contracts = IbContractMapper.macroBenchmarkContractCandidates("UKX")
        assertEquals(3, contracts.size)
        assertEquals("UKX", contracts[0].symbol())
        assertEquals("ICEEU", contracts[0].exchange())
        assertEquals("GBP", contracts[0].currency())
        assertEquals("UKX", contracts[1].symbol())
        assertEquals("SMART", contracts[1].exchange())
        assertEquals("ICEEU", contracts[1].primaryExch())
        assertEquals("ISF", contracts[2].symbol())
        assertEquals("SMART", contracts[2].exchange())
        assertEquals("LSE", contracts[2].primaryExch())
        assertEquals("STK", contracts[2].getSecType())
    }

    @Test
    fun contractDetailsLookupContracts_hkUsesSehkContract() {
        val contracts = IbContractMapper.contractDetailsLookupContracts("00700")
        assertEquals(1, contracts.size)
        assertEquals("700", contracts[0].symbol())
        assertEquals("SEHK", contracts[0].exchange())
        assertEquals("HKD", contracts[0].currency())
    }

    /**
     * META 2026-07-14 OPEN_DEADLINE: position callback had exchange=NASDAQ → MKT close rejected
     * IB 10311 (direct routed). Session closes must use SMART + listing as primaryExch.
     */
    @Test
    fun forSmartRoutedOrder_rewritesListingExchangeToSmartPreservingPrimary() {
        val fromPosition = com.ib.client.Contract().also {
            it.conid(107113386)
            it.symbol("META")
            it.secType("STK")
            it.exchange("NASDAQ")
            it.currency("USD")
        }
        val routed = IbContractMapper.forSmartRoutedOrder(fromPosition)
        assertEquals("SMART", routed.exchange())
        assertEquals("NASDAQ", routed.primaryExch())
        assertEquals(107113386, routed.conid())
        assertEquals("META", routed.symbol())
        assertEquals("USD", routed.currency())
    }

    @Test
    fun forSmartRoutedOrder_keepsSmartAndExistingPrimary() {
        val alreadySmart = com.ib.client.Contract().also {
            it.symbol("BAC")
            it.secType("STK")
            it.exchange("SMART")
            it.primaryExch("NYSE")
            it.currency("USD")
        }
        val routed = IbContractMapper.forSmartRoutedOrder(alreadySmart)
        assertEquals("SMART", routed.exchange())
        assertEquals("NYSE", routed.primaryExch())
    }

    @Test
    fun forSmartRoutedOrder_leavesSehkUnchanged() {
        val sehk = IbContractMapper.hkStock("00700")
        val routed = IbContractMapper.forSmartRoutedOrder(sehk)
        assertEquals("SEHK", routed.exchange())
        assertEquals("SEHK", routed.primaryExch())
    }
}
