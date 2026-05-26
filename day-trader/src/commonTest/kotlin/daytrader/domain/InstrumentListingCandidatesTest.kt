package daytrader.domain

import kotlin.test.Test
import kotlin.test.assertEquals

class InstrumentListingCandidatesTest {
    @Test
    fun prepareForUi_keepsUsAndUkListingsForDualListedSymbol() {
        val nyse = ResolvedInstrument(
            marketZoneId = RthMarketSessions.US.zoneId,
            currencyCode = "USD",
            venueLabel = "NYSE · USD",
            source = MarketSource.IB,
            companyName = "NatWest Group plc",
            identity = InstrumentIdentity(
                symbol = "NWG",
                exchange = "SMART",
                primaryExch = "NYSE",
                currency = "USD",
                conId = 1L
            )
        )
        val lse = ResolvedInstrument(
            marketZoneId = RthMarketSessions.EUR.zoneId,
            currencyCode = "GBP",
            venueLabel = "LSE · GBP",
            source = MarketSource.IB,
            companyName = "NatWest Group PLC",
            identity = InstrumentIdentity(
                symbol = "NWG",
                exchange = "SMART",
                primaryExch = "LSE",
                currency = "GBP",
                conId = 2L
            )
        )
        val xetra = ResolvedInstrument(
            marketZoneId = "Europe/Berlin",
            currencyCode = "EUR",
            venueLabel = "IBIS · EUR",
            source = MarketSource.IB,
            identity = InstrumentIdentity(
                symbol = "NWG",
                exchange = "SMART",
                primaryExch = "IBIS",
                currency = "EUR",
                conId = 3L
            )
        )

        val prepared = InstrumentListingCandidates.prepareForUi(listOf(xetra, nyse, lse))

        assertEquals(listOf("LSE · GBP", "NYSE · USD"), prepared.map(InstrumentListingCandidates::listingLabel))
    }
}
