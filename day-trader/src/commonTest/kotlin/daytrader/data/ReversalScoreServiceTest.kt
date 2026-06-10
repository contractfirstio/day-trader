package daytrader.data

import daytrader.domain.HomeMarketMacroBenchmark
import daytrader.domain.MacroRegimeEvaluator
import daytrader.domain.MacroRegimeSnapshot
import daytrader.domain.MacroTrendState
import daytrader.domain.RthMarketSessions
import daytrader.domain.newWatchlistEntry
import daytrader.engine.support.FakeBrokerGateway
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.runBlocking

class ReversalScoreServiceTest {

    @Test
    fun calculateScores_usesHomeMarketRegimePerEntryZone() = runBlocking {
        val gateway = FakeBrokerGateway().apply {
            homeMarketRegimeByZone = mapOf(
                RthMarketSessions.US.zoneId to bullRegime(RthMarketSessions.US.zoneId),
                RthMarketSessions.EUR.zoneId to bearRegime(RthMarketSessions.EUR.zoneId),
                RthMarketSessions.HK.zoneId to bullRegime(RthMarketSessions.HK.zoneId)
            )
        }
        val service = ReversalScoreService()
        val entries = listOf(
            newWatchlistEntry("AAPL", RthMarketSessions.US.zoneId, "USD", "Apple", null),
            newWatchlistEntry("NWG", RthMarketSessions.EUR.zoneId, "GBP", "NatWest", null),
            newWatchlistEntry("700", RthMarketSessions.HK.zoneId, "HKD", "Tencent", null)
        )

        val result = service.calculateScores(entries, gateway)

        assertEquals(3, result.homeMarketRegimes.size)
        assertEquals("SPY", result.homeMarketRegimes.first { it.marketZoneId == RthMarketSessions.US.zoneId }.benchmarkSymbol)
        assertEquals("UKX", result.homeMarketRegimes.first { it.marketZoneId == RthMarketSessions.EUR.zoneId }.benchmarkSymbol)
        assertEquals("HSI", result.homeMarketRegimes.first { it.marketZoneId == RthMarketSessions.HK.zoneId }.benchmarkSymbol)
        assertEquals(MacroTrendState.BEAR, result.homeMarketRegimes.first { it.benchmarkSymbol == "UKX" }.macroTrendState)
        assertEquals(3, result.entryResults.count { it.score != null })
    }

    @Test
    fun calculateScores_fetchesDistinctHomeMarketsOncePerZone() = runBlocking {
        val gateway = FakeBrokerGateway()
        val service = ReversalScoreService()
        val entries = listOf(
            newWatchlistEntry("AAPL", RthMarketSessions.US.zoneId, "USD", "Apple", null),
            newWatchlistEntry("MSFT", RthMarketSessions.US.zoneId, "USD", "Microsoft", null),
            newWatchlistEntry("NWG", RthMarketSessions.EUR.zoneId, "GBP", "NatWest", null),
            newWatchlistEntry("700", RthMarketSessions.HK.zoneId, "HKD", "Tencent", null)
        )

        service.calculateScores(entries, gateway)

        assertEquals(
            listOf(
                RthMarketSessions.US.zoneId,
                RthMarketSessions.EUR.zoneId,
                RthMarketSessions.HK.zoneId
            ),
            gateway.homeMarketRegimeFetchZones
        )
    }

    private fun bullRegime(marketZoneId: String): MacroRegimeSnapshot {
        val benchmark = HomeMarketMacroBenchmark.forMarketZoneId(marketZoneId)
        return MacroRegimeEvaluator.buildSyntheticSnapshot(
            benchmark = benchmark,
            lastPrice = 110.0,
            trend = MacroTrendState.BULL
        )
    }

    private fun bearRegime(marketZoneId: String): MacroRegimeSnapshot {
        val benchmark = HomeMarketMacroBenchmark.forMarketZoneId(marketZoneId)
        return MacroRegimeEvaluator.buildSyntheticSnapshot(
            benchmark = benchmark,
            lastPrice = 90.0,
            trend = MacroTrendState.BEAR
        )
    }

}
