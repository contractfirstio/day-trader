package daytrader.replay

import daytrader.domain.TouchTurnSessionOutcome
import daytrader.replay.support.ReplaySessionFixtures
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SessionBundleLoaderTest {

    @Test
    fun load_minimalFixture_extractsIdentityTimelineHistoricalQuotesAndGroundTruth() {
        val bundle = SessionBundleLoader.load(ReplaySessionFixtures.minimalContents()).getOrThrow()

        assertEquals("dep-replay-1", bundle.deploymentId)
        assertEquals("sess-replay-1", bundle.sessionId)
        assertEquals("AAPL", bundle.symbol)
        assertEquals("2026-06-04", bundle.sessionDate)
        assertEquals(1_780_579_800_000L, bundle.timeline.sessionStartedEpochMs)
        assertEquals(1_780_581_600_000L, bundle.timeline.sessionStoppedEpochMs)

        assertNotNull(bundle.bootstrapContext)
        assertEquals(800_000.0, bundle.bootstrapContext?.firstCandle?.volume)
        assertEquals(2, bundle.refetchEvents.size)
        assertEquals(100.15, bundle.acceptedRefetchContext?.firstCandle?.close)

        assertEquals(2, bundle.quoteEvents.size)
        assertEquals(100.15, bundle.quoteEvents.first().quote.last)

        assertTrue(bundle.hasGroundTruth)
        assertEquals(
            TouchTurnSessionOutcome.NO_TRADE_NOT_LIQUIDITY,
            bundle.groundTruth?.runRecord?.decision?.outcome
        )
        assertEquals("NO_TRADE_DECISION", bundle.groundTruth?.stopTrigger)
    }

    @Test
    fun load_withoutManifest_usesApplicationLogForTimeline() {
        val contents = ReplaySessionFixtures.minimalContents().copy(manifestJson = null)
        val bundle = SessionBundleLoader.load(contents).getOrThrow()
        assertEquals(1_780_579_800_000L, bundle.timeline.sessionStartedEpochMs)
        assertNull(bundle.manifest)
    }

    @Test
    fun load_missingSessionStarted_fails() {
        val contents = SessionBundleContents(
            applicationJsonl = "",
            historicalJsonl = "",
            pricesJsonl = ""
        )
        val result = SessionBundleLoader.load(contents)
        assertTrue(result.isFailure)
    }

    @Test
    fun mergeQuoteTimelines_prefersChronologicalUnionAndDedupes() {
        val prices = listOf(
            QuoteEvent(
                epochMs = 100L,
                symbol = "AAPL",
                quote = daytrader.gateway.LiveQuote(symbol = "AAPL", bid = 1.0, ask = 2.0, last = 1.5)
            )
        )
        val ib = listOf(
            QuoteEvent(
                epochMs = 100L,
                symbol = "AAPL",
                quote = daytrader.gateway.LiveQuote(symbol = "AAPL", bid = 1.0, ask = 2.0, last = 1.5)
            ),
            QuoteEvent(
                epochMs = 150L,
                symbol = "AAPL",
                quote = daytrader.gateway.LiveQuote(symbol = "AAPL", bid = 1.1, ask = 2.1, last = 1.6)
            )
        )
        val merged = SessionBundleLoader.mergeQuoteTimelines(prices, ib)
        assertEquals(2, merged.size)
        assertEquals(150L, merged.last().epochMs)
    }
}
