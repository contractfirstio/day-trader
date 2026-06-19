package daytrader.engine.touchturn

import daytrader.broker.emulator.BrokerEmulatorConfig
import daytrader.domain.OhlcBar
import daytrader.domain.TouchTurnLogic
import daytrader.domain.TouchTurnOrderPlanner
import daytrader.domain.TouchTurnRuleConfig
import daytrader.e2e.support.EmulatorModeTestHarness
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking

class TouchTurnEmulatorInvertBracketTest {
    @Test
    fun emulatorStreaming_afterSessionReset_providesBidAskForInvertPlacement() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val harness = EmulatorModeTestHarness(
            scope = scope,
            config = BrokerEmulatorConfig(connectDelayMs = 1, marketTickIntervalMs = 50)
        )
        harness.start()
        harness.gateway.requestSessionReset()
        delay(20)

        val bar = OhlcBar(
            open = 380.33,
            high = 383.77,
            low = 379.92,
            close = 380.75,
            time = "20260619  08:59:01",
            volume = 344_160.0
        )
        val rules = TouchTurnRuleConfig.DEFAULT.copy(invertTradeSide = true)
        val setup = TouchTurnLogic.computeBracketSetup(bar, rangeThreshold = 0.5, rules = rules)!!
        val plan = TouchTurnOrderPlanner.buildOrderPlan("700", setup, maxDollars = 500, rules = rules)!!

        harness.gateway.requestEmulatorStreaming("0700", referencePrice = bar.close)
        delay(50)
        var quote = harness.gateway.quotes.value["700"]
        assertNotNull(quote)
        assertTrue((quote.bid ?: 0.0) > 0.0)
        assertTrue((quote.ask ?: 0.0) > (quote.bid ?: 0.0))

        val blockBefore = TouchTurnLogic.invertPlacementBlockOutcome(
            plan = plan,
            bid = quote.bid,
            ask = quote.ask,
            rules = rules
        )
        if (blockBefore != null) {
            val (bid, ask) = TouchTurnLogic.syntheticBidAskForInvertPlacement(plan, setup, bar.close)
            harness.gateway.requestEmulatorSyntheticQuote("0700", bid, ask, (bid + ask) / 2.0)
            delay(50)
            quote = harness.gateway.quotes.value["700"]
            assertNotNull(quote)
            assertEquals(
                null,
                TouchTurnLogic.invertPlacementBlockOutcome(
                    plan = plan,
                    bid = quote.bid,
                    ask = quote.ask,
                    rules = rules
                )
            )
        } else {
            assertNull(blockBefore)
        }
        harness.shutdown()
    }
}
