package daytrader.replay

import daytrader.domain.StrategyType
import daytrader.domain.TouchTurnRuleConfig
import daytrader.domain.TouchTurnRuleEnables
import daytrader.domain.defaultStrategyDeployment
import daytrader.replay.support.ReplaySessionFixtures
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking

class MultiSymbolQuoteFeederOpenDeadlineTest {
    @Test
    fun dripStopsAtOpenDeadline_leavesTrapQuoteUnpublished() = runBlocking {
        val base = SessionBundleLoader.load(ReplaySessionFixtures.minimalContents()).getOrThrow()
        val sessionDate = "2026-06-04"
        val openMs = base.timeline.sessionStartedEpochMs
        val deadlineMs = openMs + 30 * 60_000L
        val trapMs = deadlineMs + 60_000L
        val bundle = base.copy(
            quoteEvents = base.quoteEvents + listOf(
                QuoteEvent(
                    epochMs = trapMs,
                    symbol = base.symbol,
                    quote = base.quoteEvents.first().quote.copy(bid = 1.0, ask = 2.0)
                )
            )
        )
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val clock = ReplayClock(openMs)
        val registry = ReplayCaptureRegistry(bundle)
        val gateway = ReplayMarketDataGateway(registry)
        val feeder = MultiSymbolQuoteFeeder(registry, quoteBus = null, gateway, clock, scope)
        feeder.quoteIntervalMs = { 0L }
        feeder.backtestQuoteIngest = { }
        val deployment = defaultStrategyDeployment(
            strategyType = StrategyType.TOUCH_AND_TURN_SCALPER,
            symbol = bundle.symbol,
            maxDollars = 500
        ).copy(
            touchTurnRules = TouchTurnRuleConfig.DEFAULT.copy(
                stopAfterOpenMinutes = 30,
                enables = TouchTurnRuleEnables.DEFAULT.copy(openDeadline = true)
            )
        )
        feeder.resolveStopDeadlineEpochMs = {
            ReplayQuoteStopSync.openDeadlineEpochMs(deployment, sessionDate)
        }
        var dripContinues = true
        feeder.onAfterQuotePublished = {
            dripContinues = clock.nowEpochMillis() < deadlineMs
            dripContinues
        }

        feeder.ensureStreaming(bundle.symbol)
        feeder.enableDrip(bundle.symbol)
        delay(100L)

        assertTrue(clock.nowEpochMillis() <= deadlineMs)
        val quoteFeeder = feeder.feederForSymbol(bundle.symbol)!!
        val trap = quoteFeeder.peekNext()
        assertNotNull(trap)
        assertEquals(trapMs, trap.epochMs)
        assertEquals(2, quoteFeeder.publishedQuoteCount)
        assertEquals(false, dripContinues)
    }
}
