package daytrader.replay

import daytrader.replay.support.ReplaySessionFixtures
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking

class MultiSymbolQuoteFeederTest {
    @Test
    fun markOpeningBarQuotesReady_tracksSymbolUntilReset() {
        val bundle = SessionBundleLoader.load(ReplaySessionFixtures.minimalContents()).getOrThrow()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val clock = ReplayClock(bundle.timeline.sessionStartedEpochMs)
        val registry = ReplayCaptureRegistry(bundle)
        val gateway = ReplayMarketDataGateway(registry)
        val feeder = MultiSymbolQuoteFeeder(registry, quoteBus = null, gateway, clock, scope)

        assertEquals(false, feeder.isOpeningBarQuotesReady(bundle.symbol))
        feeder.markOpeningBarQuotesReady(bundle.symbol)
        assertEquals(true, feeder.isOpeningBarQuotesReady(bundle.symbol))
        feeder.resetSymbol(bundle.symbol)
        assertEquals(false, feeder.isOpeningBarQuotesReady(bundle.symbol))
    }

    @Test
    fun mergedDrip_interleavesQuotesByEpochAcrossSymbols() = runBlocking {
        val bundleA = SessionBundleLoader.load(ReplaySessionFixtures.minimalContents()).getOrThrow()
        val bundleB = bundleA.copy(
            symbol = "META",
            quoteEvents = bundleA.quoteEvents.map { event ->
                event.copy(
                    symbol = "META",
                    epochMs = event.epochMs + 1L,
                    quote = event.quote.copy(symbol = "META")
                )
            }
        )
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val clock = ReplayClock(bundleA.timeline.sessionStartedEpochMs)
        val registry = ReplayCaptureRegistry()
        registry.register(bundleA)
        registry.register(bundleB)
        val gateway = ReplayMarketDataGateway(registry)
        val feeder = MultiSymbolQuoteFeeder(registry, quoteBus = null, gateway, clock, scope)
        feeder.quoteIntervalMs = { 0L }
        feeder.backtestQuoteIngest = { }

        feeder.ensureStreaming(bundleA.symbol)
        feeder.ensureStreaming(bundleB.symbol)
        feeder.enableDrip(bundleA.symbol)
        feeder.enableDrip(bundleB.symbol)

        delay(50L)

        val feederA = feeder.feederForSymbol(bundleA.symbol)!!
        val feederB = feeder.feederForSymbol(bundleB.symbol)!!
        assertEquals(2, feederA.publishedQuoteCount)
        assertEquals(2, feederB.publishedQuoteCount)
        assertEquals(bundleA.quoteEvents[1].epochMs + 1L, clock.nowEpochMillis())
    }
}
