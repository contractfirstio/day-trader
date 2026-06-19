package daytrader.marketdata

import daytrader.broker.emulator.BrokerEmulatorConfig
import daytrader.broker.emulator.BrokerEmulatorEngine
import daytrader.broker.emulator.TouchTurnEntryScenario
import daytrader.domain.FirstCandleColor
import daytrader.domain.TouchTurnBracketSetup
import daytrader.domain.TouchTurnOrderPlanner
import daytrader.domain.TouchTurnTradeSide
import daytrader.gateway.GatewayEvent
import daytrader.gateway.LiveQuote
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking

/**
 * Regression for hybrid duplicate fills: concurrent IB ticks must not multiply emulator fills.
 */
class MarketQuoteBusConcurrentFillTest {

    @Test
    fun concurrentPublishViaBus_producesSingleRoundTripFill() = runBlocking {
        val events = mutableListOf<GatewayEvent>()
        val bus = MarketQuoteBus()
        val engine = BrokerEmulatorEngine(
            config = BrokerEmulatorConfig.forLiveIbMarketData().copy(
                connectDelayMs = 1,
                touchTurnEntryScenarioOverride = TouchTurnEntryScenario.IMMEDIATE
            ),
            emit = { events.add(it) }
        )
        val quoteChannel = bus.subscribeForEmulator()
        val consumer = launch(Dispatchers.Default) {
            for (update in quoteChannel) {
                engine.ingestExternalQuote(update.symbol, update.quote, update.priorClose)
            }
        }
        engine.handleConnect()
        engine.finishConnect()
        engine.placeTouchTurnBracket(touchTurnPlan())

        val quote = LiveQuote(symbol = "AAPL", bid = 100.0, ask = 101.0, last = 100.5)
        coroutineScope {
            repeat(50) {
                launch(Dispatchers.Default) {
                    bus.publish("AAPL", quote, priorClose = null, QuoteSource.EXTERNAL)
                }
            }
        }
        delay(200)
        consumer.cancel()
        quoteChannel.cancel()

        val fills = events.filterIsInstance<GatewayEvent.FillsSnapshot>().lastOrNull()?.fills.orEmpty()
        val entryFills = fills.filter { it.parentOrderId == 0 }
        val exitFills = fills.filter { it.parentOrderId != 0 }
        assertEquals(1, entryFills.size, "expected one entry fill, got ${entryFills.size}: $fills")
        assertEquals(1, exitFills.size, "expected one exit fill, got ${exitFills.size}: $fills")
        assertTrue(events.filterIsInstance<GatewayEvent.PositionsSnapshot>().last().positions.isEmpty())
    }

    private fun touchTurnPlan() = TouchTurnOrderPlanner.buildOrderPlan(
        symbol = "AAPL",
        setup = TouchTurnBracketSetup(
            range = 2.0,
            rangeThreshold = 0.5,
            isLiquidityCandle = true,
            candleColor = FirstCandleColor.GREEN,
            side = TouchTurnTradeSide.SHORT,
            entry = 100.0,
            stopLoss = 101.0,
            takeProfit = 99.0
        ),
        maxDollars = 500,
        currencyCode = "USD",
        openingBarClose = 99.0
    )!!
}
