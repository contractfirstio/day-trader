package daytrader.engine.touchturn

import daytrader.domain.MacroTrendState
import daytrader.domain.RthMarketSessions
import daytrader.domain.StockTrendState
import daytrader.e2e.support.HybridModeTestHarness
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.runBlocking

class TouchTurnTrendAlignmentHybridTest {

    @Test
    fun hybrid_trendSnapshots_useIbMarketDataGateway_notEmulatorSeedHistory() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val harness = HybridModeTestHarness(scope)
        harness.start()

        val macro = harness.ibGateway.fetchHomeMarketRegimeSnapshot(RthMarketSessions.HK.zoneId).getOrThrow()
        val stock = harness.ibGateway.fetchStockTrendSnapshot("9988").getOrThrow()

        assertEquals(1, harness.ibGateway.homeMarketRegimeFetchCount)
        assertEquals(1, harness.ibGateway.reversalScoreSymbolFetchCount)
        assertEquals(1, harness.ibGateway.stockTrendFetchCount)
        assertEquals("HSI", macro.benchmark.symbol)
        assertEquals(MacroTrendState.BULL, macro.macroTrendState())
        assertEquals(StockTrendState.UP, stock.stockTrendState())

        harness.shutdown()
    }

    @Test
    fun hybrid_stockTrendSnapshot_comparesLivePriceAgainstHistoricalDailyCloses() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val harness = HybridModeTestHarness(scope)
        harness.start()

        val liveLast = 112.0
        val historical = List(30) { index -> 100.0 + index * 0.1 }
        harness.ibGateway.reversalScoreSymbolResult = Result.success(
            daytrader.domain.ReversalScoreSymbolSnapshot(
                live = daytrader.domain.ReversalScoreLiveSnapshot(
                    lastPrice = liveLast,
                    volume = 1_000_000.0,
                    impliedVolatility = 0.2
                ),
                historical = daytrader.domain.ReversalScoreHistoricalSnapshot(
                    dailyCloses = historical,
                    dailyVolumes = historical.map { it * 1_000.0 },
                    historicalIvValues = historical.map { 0.2 }
                )
            )
        )

        val stock = harness.ibGateway.fetchStockTrendSnapshot("9988").getOrThrow()
        assertEquals(StockTrendState.UP, stock.stockTrendState())
        assertTrue(liveLast > stock.sma20)

        harness.shutdown()
    }
}
