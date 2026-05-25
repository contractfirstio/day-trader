package daytrader.domain

import daytrader.broker.SymbolMarkets
import kotlin.test.Test
import kotlin.test.assertEquals

class InstrumentMarketResolverTest {
    @Test
    fun fromIbContract_lseMapsToLondon() {
        val resolved = InstrumentMarketResolver.fromIbContract(
            InstrumentMarketResolver.ContractSnapshot(
                symbol = "VOD",
                exchange = "SMART",
                primaryExch = "LSE",
                currency = "GBP",
                companyName = "Vodafone Group PLC"
            )
        )
        assertEquals(RthMarketSessions.EUR.zoneId, resolved.marketZoneId)
        assertEquals("GBP", resolved.currencyCode)
        assertEquals(MarketSource.IB, resolved.source)
        assertEquals("Vodafone Group PLC", resolved.companyName)
    }

    @Test
    fun fromIbContract_sehkMapsToHongKong() {
        val resolved = InstrumentMarketResolver.fromIbContract(
            InstrumentMarketResolver.ContractSnapshot(
                symbol = "700",
                exchange = "SEHK",
                primaryExch = "SEHK",
                currency = "HKD"
            )
        )
        assertEquals(RthMarketSessions.HK.zoneId, resolved.marketZoneId)
        assertEquals("HKD", resolved.currencyCode)
    }

    @Test
    fun fromIbContract_usdSmartMapsToUs() {
        val resolved = InstrumentMarketResolver.fromIbContract(
            InstrumentMarketResolver.ContractSnapshot(
                symbol = "SPY",
                exchange = "SMART",
                primaryExch = "ARCA",
                currency = "USD"
            )
        )
        assertEquals(RthMarketSessions.US.zoneId, resolved.marketZoneId)
        assertEquals("USD", resolved.currencyCode)
    }

    @Test
    fun fromSymbolHeuristic_hkDigits() {
        val resolved = DeploymentMarket.fromSymbolHeuristic("0700")
        assertEquals(RthMarketSessions.HK.zoneId, resolved.marketZoneId)
        assertEquals(SymbolMarkets.currencyCode("700"), resolved.currencyCode)
    }
}
