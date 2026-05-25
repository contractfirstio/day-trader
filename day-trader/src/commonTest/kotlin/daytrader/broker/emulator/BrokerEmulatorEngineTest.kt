package daytrader.broker.emulator

import daytrader.gateway.GatewayConnectionState
import daytrader.gateway.GatewayEvent
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking

class BrokerEmulatorEngineTest {

    @Test
    fun connect_publishesPositionsAndOrders() = runBlocking {
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
        assertTrue(positions.size >= 6)
        assertTrue(positions.any { it.symbol == "AAPL" })

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
        assertTrue(bar.time?.contains("09:30") == true)
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
    fun marketTick_updatesPositionMarks() = runBlocking {
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
        assertTrue(snapshot.isNotEmpty())
    }
}
