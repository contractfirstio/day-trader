package daytrader.broker.emulator

import daytrader.domain.FirstCandleColor
import daytrader.domain.TouchTurnBracketSetup
import daytrader.domain.TouchTurnOrderPlanner
import daytrader.domain.TouchTurnTradeSide
import daytrader.gateway.GatewayEvent
import daytrader.gateway.LiveQuote
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking

class EmulatorPricingDecouplingTest {

    @Test
    fun syntheticMode_externalQuotesDoNotTriggerFills() = runBlocking {
        val events = mutableListOf<GatewayEvent>()
        val engine = BrokerEmulatorEngine(
            config = BrokerEmulatorConfig(connectDelayMs = 1, pricingSource = EmulatorPricingSource.SYNTHETIC),
            emit = { events.add(it) }
        )
        engine.handleConnect()
        engine.finishConnect()

        val plan = touchTurnPlan()
        engine.placeTouchTurnBracket(plan)
        engine.ingestExternalQuote(
            "AAPL",
            LiveQuote(symbol = "AAPL", bid = 99.0, ask = 99.2, last = 99.1),
            priorClose = null
        )

        assertTrue(events.filterIsInstance<GatewayEvent.PositionsSnapshot>().last().positions.isEmpty())
    }

    @Test
    fun liveExchangeMode_syntheticTicksDoNotRun() = runBlocking {
        val events = mutableListOf<GatewayEvent>()
        val engine = BrokerEmulatorEngine(
            config = BrokerEmulatorConfig.forLiveIbMarketData().copy(connectDelayMs = 1),
            emit = { events.add(it) }
        )
        engine.handleConnect()
        engine.finishConnect()

        engine.placeTouchTurnBracket(touchTurnPlan())
        repeat(5) { engine.runMarketTick() }

        assertTrue(events.filterIsInstance<GatewayEvent.PositionsSnapshot>().last().positions.isEmpty())
    }

    private fun touchTurnPlan() = TouchTurnOrderPlanner.buildOrderPlan(
        symbol = "AAPL",
        setup = TouchTurnBracketSetup(
            range = 2.0,
            rangeThreshold = 0.5,
            isLiquidityCandle = true,
            candleColor = FirstCandleColor.RED,
            side = TouchTurnTradeSide.LONG,
            entry = 100.0,
            stopLoss = 99.0,
            takeProfit = 101.0
        ),
        maxDollars = 500,
        currencyCode = "USD",
        openingBarClose = 101.0
    )!!
}
