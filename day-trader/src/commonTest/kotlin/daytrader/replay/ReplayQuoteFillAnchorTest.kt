package daytrader.replay

import daytrader.replay.support.ReplaySessionFixtures
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

class ReplayQuoteFillAnchorTest {
    @Test
    fun alignAfterBracketPlaced_skipsPreOrdersPlacedAtTrapBid() {
        val bundle = SessionBundleLoader.load(ReplaySessionFixtures.entryFillParityContents()).getOrThrow()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val clock = ReplayClock(bundle.timeline.sessionStartedEpochMs)
        val registry = ReplayCaptureRegistry(bundle)
        val gateway = ReplayMarketDataGateway(registry)
        val feeder = MultiSymbolQuoteFeeder(registry, quoteBus = null, gateway, clock, scope)
        val entry = bundle.groundTruth!!.dedupedFills.first { it.side == "BOT" }.price
        val trapBid = entry + 0.01
        val trap = bundle.quoteEvents.first { kotlin.math.abs(it.quote.bid!! - trapBid) < 0.001 }
        val anchorMs = trap.epochMs + 1_000L

        val quoteFeeder = feeder.feederForSymbol(bundle.symbol)!!
        ReplayQuoteFillAnchor.alignAfterBracketPlaced(feeder, clock, bundle.symbol, anchorMs)

        val next = quoteFeeder.peekNext()
        assertNotNull(next)
        assertTrue(next.epochMs > trap.epochMs)
    }
}
