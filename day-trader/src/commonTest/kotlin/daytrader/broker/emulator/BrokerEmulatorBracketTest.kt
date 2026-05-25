package daytrader.broker.emulator

import daytrader.domain.FirstCandleColor
import daytrader.domain.TouchTurnBracketSetup
import daytrader.domain.TouchTurnOrderPlanner
import daytrader.domain.TouchTurnTradeSide
import daytrader.gateway.GatewayEvent
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking

class BrokerEmulatorBracketTest {

    @Test
    fun placeBracket_entryFillOnTick_createsPosition() = runBlocking {
        val events = mutableListOf<GatewayEvent>()
        val engine = BrokerEmulatorEngine(
            config = BrokerEmulatorConfig(
                connectDelayMs = 1,
                marketTickIntervalMs = 100,
                simulateOrderProgress = false
            ),
            emit = { events.add(it) }
        )
        engine.handleConnect()
        engine.finishConnect()

        val setup = TouchTurnBracketSetup(
            range = 2.0,
            rangeThreshold = 0.5,
            isLiquidityCandle = true,
            candleColor = FirstCandleColor.GREEN,
            side = TouchTurnTradeSide.LONG,
            entry = 100.0,
            stopLoss = 99.0,
            takeProfit = 101.0
        )
        val plan = TouchTurnOrderPlanner.buildOrderPlan("AAPL", setup, maxDollars = 500, currencyCode = "USD")!!

        engine.placeTouchTurnBracket(plan)
        engine.runMarketTick()

        val positions = events.filterIsInstance<GatewayEvent.PositionsSnapshot>().last().positions
        val aapl = positions.firstOrNull { it.symbol == "AAPL" }
        assertTrue(aapl != null, "expected AAPL position after entry fill")
        assertTrue(aapl!!.quantity > 0)
    }
}
