package daytrader.replay

import daytrader.replay.support.ReplaySessionFixtures
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class QuoteFeederTest {
    @Test
    fun publishNext_publishesSequentiallyAndPeekDoesNotAdvance() {
        val bundle = SessionBundleLoader.load(ReplaySessionFixtures.minimalContents()).getOrThrow()
        val gateway = ReplayMarketDataGateway(ReplayCaptureRegistry(bundle))
        val feeder = QuoteFeeder(bundle, quoteBus = null, marketDataGateway = gateway)

        assertEquals(2, feeder.totalQuoteCount)
        assertEquals(0, feeder.publishedQuoteCount)
        assertNotNull(feeder.peekNext())
        assertEquals(0, feeder.publishedQuoteCount)

        val first = feeder.publishNext()
        assertNotNull(first)
        assertEquals(1, feeder.publishedQuoteCount)
        assertNotNull(gateway.quotes.value[first.symbol])

        val second = feeder.publishNext()
        assertNotNull(second)
        assertEquals(2, feeder.publishedQuoteCount)
        assertNull(feeder.publishNext())
    }

    @Test
    fun seedGatewayQuotesUpTo_updatesGatewayWithoutAdvancingIndex() {
        val bundle = SessionBundleLoader.load(ReplaySessionFixtures.minimalContents()).getOrThrow()
        val gateway = ReplayMarketDataGateway(ReplayCaptureRegistry(bundle))
        val feeder = QuoteFeeder(bundle, quoteBus = null, marketDataGateway = gateway)
        val lastEpoch = bundle.quoteEvents.last().epochMs

        feeder.seedGatewayQuotesUpTo(lastEpoch)

        assertEquals(0, feeder.publishedQuoteCount)
        assertNotNull(gateway.quotes.value[bundle.symbol])
        assertEquals(1, feeder.indexOfFirstQuoteAfter(lastEpoch - 1))
    }

    @Test
    fun seekToFirstQuoteAfter_positionsForPostOpeningBarDrive() {
        val bundle = SessionBundleLoader.load(ReplaySessionFixtures.minimalContents()).getOrThrow()
        val gateway = ReplayMarketDataGateway(ReplayCaptureRegistry(bundle))
        val feeder = QuoteFeeder(bundle, quoteBus = null, marketDataGateway = gateway)
        val splitEpoch = bundle.quoteEvents.first().epochMs

        feeder.seekToFirstQuoteAfter(splitEpoch - 1)
        assertEquals(0, feeder.publishedQuoteCount)

        feeder.seekToFirstQuoteAfter(splitEpoch)
        assertEquals(1, feeder.publishedQuoteCount)
        assertNotNull(feeder.peekNext())
    }

    @Test
    fun publishSymbolOverride_republishesUnderDeploymentSymbol() {
        val bundle = SessionBundleLoader.load(ReplaySessionFixtures.minimalContents()).getOrThrow()
        val gateway = ReplayMarketDataGateway(ReplayCaptureRegistry(bundle))
        val feeder = QuoteFeeder(bundle, quoteBus = null, marketDataGateway = gateway)
        feeder.publishSymbolOverride = "TSCO"

        assertNotNull(feeder.publishNext())
        assertEquals("TSCO", gateway.quotes.value["TSCO"]?.symbol)
        assertEquals(100.15, gateway.quotes.value["TSCO"]?.last)
    }
}
