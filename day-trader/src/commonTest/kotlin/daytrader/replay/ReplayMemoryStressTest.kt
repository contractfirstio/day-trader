package daytrader.replay

import daytrader.replay.support.ReplaySessionFixtures
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

class ReplayMemoryStressTest {
    @Test
    fun parallelSymbolStartStop_cycleReleasesAllRetainedState() {
        val base = SessionBundleLoader.load(ReplaySessionFixtures.minimalContents()).getOrThrow()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val clock = ReplayClock(base.timeline.sessionStartedEpochMs)
        val runtime = ReplayHybridRuntime(base, clock, scope)
        val symbols = listOf("AAPL", "MSFT", "GOOGL", "META", "NVDA")

        repeat(3) {
            symbols.forEach { symbol ->
                val bundle = base.copy(
                    symbol = symbol,
                    sessionId = "sess-$symbol-$it",
                    deploymentId = "dep-$symbol-$it"
                )
                runtime.registerBundle(bundle)
                runtime.ensureStreamingMarketData(symbol)
                runtime.marketDataGateway.updateQuote(
                    bundle.quoteEvents.first().copy(symbol = symbol)
                )
            }
            assertEquals(symbols.toSet(), runtime.captureRegistry.registeredSymbols())

            symbols.forEach { symbol ->
                runtime.releaseStreamingMarketData(symbol)
            }
            assertTrue(runtime.captureRegistry.registeredSymbols().isEmpty())
            assertTrue(runtime.marketDataGateway.quotes.value.isEmpty())
            symbols.forEach { symbol ->
                assertFalse(runtime.quoteFeeder.isStreaming(symbol))
                assertEquals(null, runtime.quoteFeeder.cachedFeederForSymbol(symbol))
            }
        }
    }
}
