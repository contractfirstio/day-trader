package daytrader.broker.emulator

import daytrader.domain.FirstCandleColor
import daytrader.domain.TouchTurnBracketSetup
import daytrader.domain.TouchTurnOrderPlanner
import daytrader.domain.TouchTurnTradeSide
import daytrader.broker.emulator.TouchTurnEntryScenario
import daytrader.gateway.GatewayEvent
import daytrader.gateway.LiveQuote
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.runBlocking

class BrokerEmulatorApplyFillIdempotencyTest {

    @Test
    fun repeatedQuoteCrossingStop_doesNotDuplicateFills() = runBlocking {
        val events = mutableListOf<GatewayEvent>()
        val engine = BrokerEmulatorEngine(
            config = BrokerEmulatorConfig.forLiveIbMarketData().copy(
                connectDelayMs = 1,
                touchTurnEntryScenarioOverride = TouchTurnEntryScenario.IMMEDIATE
            ),
            emit = { events.add(it) }
        )
        engine.handleConnect()
        engine.finishConnect()
        engine.placeTouchTurnBracket(touchTurnPlan())

        val quote = LiveQuote(symbol = "AAPL", bid = 100.0, ask = 101.0, last = 100.5)
        repeat(10) {
            engine.ingestExternalQuote("AAPL", quote, priorClose = null)
        }

        val fills = events.filterIsInstance<GatewayEvent.FillsSnapshot>().last().fills
        assertEquals(2, fills.size, "expected entry + stop only, got $fills")
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
