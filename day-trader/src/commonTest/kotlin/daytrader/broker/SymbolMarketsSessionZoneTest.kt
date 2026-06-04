package daytrader.broker

import daytrader.domain.InstrumentIdentity
import daytrader.domain.RthMarketSessions
import kotlin.test.Test
import kotlin.test.assertEquals

class SymbolMarketsSessionZoneTest {
    @Test
    fun marketZoneIdForSession_gbpListingUsesLondon() {
        val zone = SymbolMarkets.marketZoneIdForSession(
            symbol = "NWG",
            instrument = InstrumentIdentity(
                symbol = "NWG",
                exchange = "SMART",
                primaryExch = "LSE",
                currency = "GBP",
                conId = 581444942L
            )
        )
        assertEquals(RthMarketSessions.EUR.zoneId, zone)
    }

    @Test
    fun marketZoneIdForSession_deploymentZoneOverridesUsdInstrumentHeuristic() {
        val zone = SymbolMarkets.marketZoneIdForSession(
            symbol = "NWG",
            instrument = InstrumentIdentity(
                symbol = "NWG",
                exchange = "SMART",
                currency = "USD"
            ),
            deploymentMarketZoneId = RthMarketSessions.EUR.zoneId
        )
        assertEquals(RthMarketSessions.EUR.zoneId, zone)
    }
}
