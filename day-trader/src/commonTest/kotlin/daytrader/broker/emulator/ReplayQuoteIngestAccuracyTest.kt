package daytrader.broker.emulator

import daytrader.domain.FirstCandleColor
import daytrader.domain.TouchTurnBracketSetup
import daytrader.domain.TouchTurnOrderPlanner
import daytrader.domain.TouchTurnTradeSide
import daytrader.gateway.GatewayEvent
import daytrader.gateway.LiveQuote
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking

/**
 * Verifies that replay backtest config ingests every captured tick for fill evaluation instead of
 * keeping only the latest quote in a coalescing window.
 */
class ReplayQuoteIngestAccuracyTest {

    @Test
    fun flushEachExternalQuote_stopTriggersOnIntermediateQuoteNotLastTick() = runBlocking {
        val events = mutableListOf<GatewayEvent>()
        val engine = BrokerEmulatorEngine(
            config = BrokerEmulatorConfig.forReplayBacktest().copy(connectDelayMs = 1),
            emit = { events.add(it) }
        )
        engine.handleConnect()
        engine.finishConnect()

        val plan = longBracket(entry = 100.0, stopLoss = 99.0, takeProfit = 101.0)
        engine.placeTouchTurnBracket(plan)

        fun latestFills() =
            events.filterIsInstance<GatewayEvent.FillsSnapshot>().lastOrNull()?.fills.orEmpty()

        engine.ingestExternalQuote(
            symbol = "AAPL",
            quote = LiveQuote(symbol = "AAPL", bid = 100.05, ask = 100.10, last = 100.08),
            priorClose = null
        )
        assertTrue(latestFills().none { it.parentOrderId == 0 }, "entry should not fill above limit")

        engine.ingestExternalQuote(
            symbol = "AAPL",
            quote = LiveQuote(symbol = "AAPL", bid = 99.95, ask = 100.0, last = 99.98),
            priorClose = null
        )
        assertEquals(100.0, latestFills().first { it.parentOrderId == 0 }.price)

        engine.ingestExternalQuote(
            symbol = "AAPL",
            quote = LiveQuote(symbol = "AAPL", bid = 98.95, ask = 99.05, last = 99.0),
            priorClose = null
        )
        val stopFill = latestFills().last { it.parentOrderId != 0 }
        assertEquals(98.95, stopFill.price, "stop should fill at bid when price trades through stop")

        engine.ingestExternalQuote(
            symbol = "AAPL",
            quote = LiveQuote(symbol = "AAPL", bid = 101.50, ask = 101.60, last = 101.55),
            priorClose = null
        )
        assertEquals(2, latestFills().size, "take-profit tick after stop must not add another fill")
    }

    @Test
    fun forReplayBacktest_enablesFlushEachExternalQuote() {
        assertTrue(BrokerEmulatorConfig.forReplayBacktest().flushEachExternalQuote)
    }

    private fun longBracket(entry: Double, stopLoss: Double, takeProfit: Double) =
        TouchTurnOrderPlanner.buildOrderPlan(
            symbol = "AAPL",
            setup = TouchTurnBracketSetup(
                range = entry - stopLoss,
                rangeThreshold = 0.5,
                isLiquidityCandle = true,
                candleColor = FirstCandleColor.RED,
                side = TouchTurnTradeSide.LONG,
                entry = entry,
                stopLoss = stopLoss,
                takeProfit = takeProfit
            ),
            maxDollars = 500,
            currencyCode = "USD",
            openingBarClose = entry + 0.5
        )!!
}
