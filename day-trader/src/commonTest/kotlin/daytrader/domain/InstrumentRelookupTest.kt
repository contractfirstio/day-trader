package daytrader.domain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class InstrumentRelookupTest {
    @Test
    fun selectCandidate_prefersSavedDedupeKey() {
        val existing = InstrumentIdentity(
            symbol = "939",
            exchange = "SEHK",
            primaryExch = "SEHK",
            currency = "HKD",
            conId = 46_636_696L,
            minOrderSize = 1,
            orderSizeIncrement = 1
        )
        val resolution = InstrumentResolution(
            candidates = listOf(
                ResolvedInstrument(
                    marketZoneId = RthMarketSessions.US.zoneId,
                    currencyCode = "USD",
                    venueLabel = "SMART · USD",
                    source = MarketSource.IB,
                    identity = InstrumentIdentity(symbol = "939", exchange = "SMART", currency = "USD")
                ),
                ResolvedInstrument(
                    marketZoneId = RthMarketSessions.HK.zoneId,
                    currencyCode = "HKD",
                    venueLabel = "SEHK · HKD",
                    source = MarketSource.IB,
                    identity = existing.copy(minOrderSize = 1_000, orderSizeIncrement = 1_000)
                )
            )
        )

        val selected = InstrumentRelookup.selectCandidate(
            resolution = resolution,
            symbol = "939",
            existingInstrument = existing,
            marketZoneId = RthMarketSessions.HK.zoneId
        )

        assertEquals(1_000, selected?.identity?.minOrderSize)
    }

    @Test
    fun mergeIdentity_updatesLotSizeFromIb() {
        val existing = InstrumentIdentity(
            symbol = "939",
            exchange = "SEHK",
            primaryExch = "SEHK",
            currency = "HKD"
        )
        val candidate = ResolvedInstrument(
            marketZoneId = RthMarketSessions.HK.zoneId,
            currencyCode = "HKD",
            venueLabel = "SEHK · HKD",
            source = MarketSource.IB,
            identity = existing.copy(minOrderSize = 1_000, orderSizeIncrement = 1_000, conId = 99L)
        )

        val merged = InstrumentRelookup.mergeIdentity(existing, candidate, "939")

        assertEquals(1_000, merged?.minOrderSize)
        assertEquals(1_000, merged?.orderSizeIncrement)
        assertEquals(99L, merged?.conId)
    }

    @Test
    fun supportsBrokerKind_allowsIbAndHybridOnly() {
        assertTrue(InstrumentRelookup.supportsBrokerKind(daytrader.gateway.BrokerKind.INTERACTIVE_BROKERS))
        assertTrue(InstrumentRelookup.supportsBrokerKind(daytrader.gateway.BrokerKind.EMULATOR_LIVE_IB_MARKET_DATA))
    }
}
