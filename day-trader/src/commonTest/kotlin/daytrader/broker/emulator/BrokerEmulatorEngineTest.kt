package daytrader.broker.emulator

import daytrader.broker.SymbolMarkets
import daytrader.domain.FirstCandleCloseStatus
import daytrader.domain.FirstCandleColor
import daytrader.domain.TouchTurnBracketSetup
import daytrader.domain.TouchTurnLogic
import daytrader.domain.TouchTurnOrderPlanner
import daytrader.domain.TouchTurnTradeSide
import daytrader.gateway.GatewayConnectionState
import daytrader.gateway.GatewayEvent
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking

class BrokerEmulatorEngineTest {

    @Test
    fun connect_publishesEmptyPositionsAndSeedOrders() = runBlocking {
        val events = mutableListOf<GatewayEvent>()
        val engine = BrokerEmulatorEngine(
            config = BrokerEmulatorConfig(connectDelayMs = 1, historicalDelayMs = 1),
            emit = { events.add(it) }
        )
        engine.handleConnect()
        engine.finishConnect()

        val states = events.filterIsInstance<GatewayEvent.ConnectionStateChanged>().map { it.state }
        assertTrue(GatewayConnectionState.Connected in states)

        val positions = events.filterIsInstance<GatewayEvent.PositionsSnapshot>().last().positions
        assertTrue(positions.isEmpty())

        val orders = events.filterIsInstance<GatewayEvent.OpenOrdersSnapshot>().last().orders
        assertTrue(orders.isNotEmpty())
        assertTrue(orders.any { it.symbol == "SPY" })
    }

    @Test
    fun fetchFirstFifteenMinuteCandle_returnsBarForKnownSymbol() = runBlocking {
        val events = mutableListOf<GatewayEvent>()
        val engine = BrokerEmulatorEngine(
            config = BrokerEmulatorConfig(historicalDelayMs = 1),
            emit = { events.add(it) }
        )
        engine.fetchFirstFifteenMinuteCandle(1L, "SPY")

        val ready = events.filterIsInstance<GatewayEvent.FirstFifteenMinuteCandleReady>().single()
        assertEquals(1L, ready.requestId)
        val bar = ready.result.getOrThrow()
        assertTrue(bar.high >= bar.low)
        val zone = SymbolMarkets.zoneId("SPY")
        assertEquals(
            FirstCandleCloseStatus.FORMING,
            TouchTurnLogic.firstCandleCloseStatus(bar, zone)
        )
    }

    @Test
    fun fetchFourteenDayAdr_computesFromSyntheticDailies() = runBlocking {
        val events = mutableListOf<GatewayEvent>()
        val engine = BrokerEmulatorEngine(
            config = BrokerEmulatorConfig(historicalDelayMs = 1),
            emit = { events.add(it) }
        )
        engine.fetchFourteenDayAdr(2L, "QQQ")

        val ready = events.filterIsInstance<GatewayEvent.FourteenDayAdrReady>().single()
        assertEquals(2L, ready.requestId)
        assertTrue(ready.result.isSuccess)
        assertTrue(ready.result.getOrThrow() > 0.0)
    }

    @Test
    fun marketTick_withNoPositions_publishesEmptySnapshot() = runBlocking {
        val events = mutableListOf<GatewayEvent>()
        val engine = BrokerEmulatorEngine(
            config = BrokerEmulatorConfig(connectDelayMs = 1),
            emit = { events.add(it) }
        )
        engine.handleConnect()
        engine.finishConnect()
        events.clear()

        engine.runMarketTick()

        val snapshot = events.filterIsInstance<GatewayEvent.PositionsSnapshot>().last().positions
        assertTrue(snapshot.isEmpty())
    }

    @Test
    fun liveIbMode_doesNotFillUntilIbMarkCrossesLimit() = runBlocking {
        val events = mutableListOf<GatewayEvent>()
        val engine = BrokerEmulatorEngine(
            config = BrokerEmulatorConfig.forLiveIbMarketData().copy(connectDelayMs = 1),
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
        assertTrue(events.filterIsInstance<GatewayEvent.PositionsSnapshot>().last().positions.isEmpty())

        engine.ingestLiveMark("AAPL", 105.0, null)
        assertTrue(events.filterIsInstance<GatewayEvent.PositionsSnapshot>().last().positions.isEmpty())

        engine.ingestLiveMark("AAPL", 99.5, null)
        val position = events.filterIsInstance<GatewayEvent.PositionsSnapshot>().last().positions.single()
        assertTrue(position.quantity > 0)
    }

    @Test
    fun cancelOpenOrdersForSymbol_removesWorkingOrdersForThatSymbol() = runBlocking {
        val events = mutableListOf<GatewayEvent>()
        val engine = BrokerEmulatorEngine(
            config = BrokerEmulatorConfig.forLiveIbMarketData().copy(connectDelayMs = 1),
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
        val before = events.filterIsInstance<GatewayEvent.OpenOrdersSnapshot>().last().orders
        assertTrue(before.any { SymbolMarkets.symbolsMatch("AAPL", it.symbol) })

        engine.cancelOpenOrdersForSymbol("AAPL")
        val after = events.filterIsInstance<GatewayEvent.OpenOrdersSnapshot>().last().orders
        assertTrue(after.none { SymbolMarkets.symbolsMatch("AAPL", it.symbol) })
    }

    @Test
    fun closeOpenPositionForSymbol_flattensHeldPosition() = runBlocking {
        val events = mutableListOf<GatewayEvent>()
        val engine = BrokerEmulatorEngine(
            config = BrokerEmulatorConfig(connectDelayMs = 1, simulateOrderProgress = false),
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
        assertTrue(
            events.filterIsInstance<GatewayEvent.PositionsSnapshot>().last().positions
                .any { it.symbol == "AAPL" && it.quantity != 0 }
        )

        engine.closeOpenPositionForSymbol("AAPL")
        val flat = events.filterIsInstance<GatewayEvent.PositionsSnapshot>().last().positions
        assertTrue(flat.none { SymbolMarkets.symbolsMatch("AAPL", it.symbol) && it.quantity != 0 })
    }

}
