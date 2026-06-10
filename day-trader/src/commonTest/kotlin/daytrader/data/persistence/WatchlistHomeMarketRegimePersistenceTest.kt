package daytrader.data.persistence

import daytrader.domain.MacroTrendState
import daytrader.domain.RthMarketSessions
import daytrader.domain.WatchlistHomeMarketRegime
import daytrader.domain.defaultWatchlist
import kotlin.test.Test
import kotlin.test.assertEquals

class WatchlistHomeMarketRegimePersistenceTest {

    @Test
    fun roundTrip_persistsMultipleHomeMarketRegimes() {
        val original = defaultWatchlist().copy(
            lastReversalScoreHomeMarketRegimes = listOf(
                WatchlistHomeMarketRegime(
                    marketZoneId = RthMarketSessions.EUR.zoneId,
                    benchmarkSymbol = "UKX",
                    benchmarkLabel = "FTSE 100",
                    macroTrend = MacroTrendState.BULL,
                    lastPrice = 8_220.0,
                    sma200 = 8_100.0
                ),
                WatchlistHomeMarketRegime(
                    marketZoneId = RthMarketSessions.HK.zoneId,
                    benchmarkSymbol = "HSI",
                    benchmarkLabel = "Hang Seng",
                    macroTrend = MacroTrendState.BEAR,
                    lastPrice = 17_100.0,
                    sma200 = 17_400.0
                )
            )
        )

        val restored = WatchlistPersistence.toDomain(WatchlistPersistence.toRecord(original))

        assertEquals(2, restored.lastReversalScoreHomeMarketRegimes.size)
        assertEquals("UKX", restored.lastReversalScoreHomeMarketRegimes[0].benchmarkSymbol)
        assertEquals("HSI", restored.lastReversalScoreHomeMarketRegimes[1].benchmarkSymbol)
    }

    @Test
    fun legacySpyFields_migrateToUsHomeMarketRegimeOnLoad() {
        val record = WatchlistRecord(
            id = "wl-1",
            name = "Watchlist",
            createdAtEpochMs = 1L,
            lastReversalScoreMacroTrend = "BULL",
            lastReversalScoreSpyLastPrice = 520.0,
            lastReversalScoreSpySma200 = 500.0
        )

        val restored = WatchlistPersistence.toDomain(record)

        assertEquals(1, restored.lastReversalScoreHomeMarketRegimes.size)
        val regime = restored.lastReversalScoreHomeMarketRegimes.single()
        assertEquals("SPY", regime.benchmarkSymbol)
        assertEquals(MacroTrendState.BULL, regime.macroTrend)
        assertEquals(520.0, regime.lastPrice)
        assertEquals(500.0, regime.sma200)
    }
}
