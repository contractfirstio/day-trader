package daytrader.domain

import daytrader.gateway.BrokerKind
import kotlin.test.Test
import kotlin.test.assertEquals

class WatchlistBrokerScopeTest {
    @Test
    fun defaultWatchlistName_variesByBrokerKind() {
        assertEquals(
            "Watchlist (Emulator)",
            watchlistNameForBrokerKind(BrokerKind.EMULATOR)
        )
        assertEquals(
            "Watchlist (Paper · Live IB)",
            watchlistNameForBrokerKind(BrokerKind.EMULATOR_LIVE_IB_MARKET_DATA)
        )
        assertEquals(
            "Watchlist (Interactive Brokers)",
            watchlistNameForBrokerKind(BrokerKind.INTERACTIVE_BROKERS)
        )
    }

    @Test
    fun brokerKind_dataDirectorySegments_areDistinct() {
        val segments = BrokerKind.entries.map { it.dataDirectorySegment }.toSet()
        assertEquals(BrokerKind.entries.size, segments.size)
    }
}
