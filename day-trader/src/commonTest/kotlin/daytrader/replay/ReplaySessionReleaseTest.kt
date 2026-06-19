package daytrader.replay

import daytrader.replay.support.ReplaySessionFixtures
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

class ReplayCaptureRegistryTest {
    @Test
    fun evictSymbol_removesBundleFromRegistry() {
        val bundle = SessionBundleLoader.load(ReplaySessionFixtures.minimalContents()).getOrThrow()
        val registry = ReplayCaptureRegistry(bundle)

        assertTrue(registry.bundleFor(bundle.symbol) != null)
        assertEquals(setOf("AAPL"), registry.registeredSymbols())

        registry.evictSymbol(bundle.symbol)

        assertEquals(null, registry.bundleFor(bundle.symbol))
        assertTrue(registry.registeredSymbols().isEmpty())
    }

    @Test
    fun register_evictsOldestWhenAboveMaxBundles() {
        val base = SessionBundleLoader.load(ReplaySessionFixtures.minimalContents()).getOrThrow()
        val registry = ReplayCaptureRegistry(maxBundles = 2)

        registry.register(base)
        registry.register(base.copy(symbol = "MSFT", sessionId = "sess-2"))
        assertEquals(setOf("AAPL", "MSFT"), registry.registeredSymbols())

        registry.register(base.copy(symbol = "GOOGL", sessionId = "sess-3"))

        assertEquals(setOf("MSFT", "GOOGL"), registry.registeredSymbols())
        assertEquals(null, registry.bundleFor("AAPL"))
    }
}

class ReplaySessionReleaseTest {
    @Test
    fun releaseStreaming_removesFeederWhenLastSubscriberGone() {
        val bundle = SessionBundleLoader.load(ReplaySessionFixtures.minimalContents()).getOrThrow()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val clock = ReplayClock(bundle.timeline.sessionStartedEpochMs)
        val registry = ReplayCaptureRegistry(bundle)
        val gateway = ReplayMarketDataGateway(registry)
        val feeder = MultiSymbolQuoteFeeder(registry, quoteBus = null, gateway, clock, scope)

        feeder.ensureStreaming(bundle.symbol)
        assertTrue(feeder.feederForSymbol(bundle.symbol) != null)

        val fullyReleased = feeder.releaseStreaming(bundle.symbol)

        assertTrue(fullyReleased)
        assertFalse(feeder.isStreaming(bundle.symbol))
        assertEquals(null, feeder.cachedFeederForSymbol(bundle.symbol))
    }

    @Test
    fun releaseStreamingMarketData_evictsBundleAndGatewayQuote() {
        val bundle = SessionBundleLoader.load(ReplaySessionFixtures.minimalContents()).getOrThrow()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val clock = ReplayClock(bundle.timeline.sessionStartedEpochMs)
        val runtime = ReplayHybridRuntime(bundle, clock, scope)

        runtime.ensureStreamingMarketData(bundle.symbol)
        runtime.marketDataGateway.updateQuote(bundle.quoteEvents.first())
        assertTrue(runtime.captureRegistry.bundleFor(bundle.symbol) != null)
        assertTrue(runtime.marketDataGateway.quotes.value.isNotEmpty())

        runtime.releaseStreamingMarketData(bundle.symbol)

        assertFalse(runtime.quoteFeeder.isStreaming(bundle.symbol))
        assertEquals(null, runtime.quoteFeeder.cachedFeederForSymbol(bundle.symbol))
        assertTrue(runtime.captureRegistry.registeredSymbols().isEmpty())
        assertTrue(runtime.marketDataGateway.quotes.value.isEmpty())
    }
}
