package daytrader.broker.emulator

import daytrader.domain.FirstCandleColor
import daytrader.domain.TouchTurnBracketSetup
import daytrader.domain.TouchTurnOrderPlanner
import daytrader.domain.TouchTurnTradeSide
import daytrader.domain.TouchTurnOrderRole
import daytrader.domain.TouchTurnRuleConfig
import daytrader.gateway.GatewayEvent
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking

class TouchTurnEntrySimulationTest {

    @Test
    fun approachEntry_noPositionUntilPriceReachesLimit() = runBlocking {
        val events = mutableListOf<GatewayEvent>()
        val engine = BrokerEmulatorEngine(
            config = BrokerEmulatorConfig(
                connectDelayMs = 1,
                simulateOrderProgress = false,
                touchTurnEntryScenarioOverride = TouchTurnEntryScenario.APPROACH_AND_FILL,
                touchTurnEntryMinApproachTicks = 2,
                touchTurnEntryStepPctOfRange = 0.15
            ),
            emit = { events.add(it) }
        )
        engine.handleConnect()
        engine.finishConnect()
        engine.ensureStreamingMarketData("AAPL")

        val plan = touchTurnPlan()
        engine.placeTouchTurnBracket(plan)

        assertTrue(
            events.filterIsInstance<GatewayEvent.PositionsSnapshot>().last().positions.isEmpty(),
            "entry should not fill on bracket place"
        )

        engine.runMarketTick()
        assertTrue(
            events.filterIsInstance<GatewayEvent.PositionsSnapshot>().last().positions.isEmpty(),
            "entry should not fill before min approach ticks"
        )

        repeat(4) { engine.runMarketTick() }
        val position = events.filterIsInstance<GatewayEvent.PositionsSnapshot>().last().positions
            .firstOrNull { it.symbol == "AAPL" }
        assertTrue(position != null && position.quantity != 0, "entry should fill after price approaches")
    }

    @Test
    fun neverFillEntry_keepsPositionFlat() = runBlocking {
        val events = mutableListOf<GatewayEvent>()
        val engine = BrokerEmulatorEngine(
            config = BrokerEmulatorConfig(
                connectDelayMs = 1,
                simulateOrderProgress = false,
                touchTurnEntryScenarioOverride = TouchTurnEntryScenario.NEVER_FILL,
                touchTurnEntryStepPctOfRange = 0.12
            ),
            emit = { events.add(it) }
        )
        engine.handleConnect()
        engine.finishConnect()
        engine.ensureStreamingMarketData("AAPL")

        engine.placeTouchTurnBracket(touchTurnPlan())
        repeat(20) { engine.runMarketTick() }

        assertTrue(
            events.filterIsInstance<GatewayEvent.PositionsSnapshot>().last().positions.isEmpty(),
            "never-fill scenario should not open a position"
        )
        val entryWorking = events.filterIsInstance<GatewayEvent.OpenOrdersSnapshot>().last().orders
            .any { it.symbol == "AAPL" && it.parentOrderId == 0 && it.remaining > 0 }
        assertTrue(entryWorking, "entry order should remain working when price never touches limit")
    }

    @Test
    fun immediateEntry_fillsOnPlace() = runBlocking {
        val events = mutableListOf<GatewayEvent>()
        val engine = BrokerEmulatorEngine(
            config = BrokerEmulatorConfig(
                connectDelayMs = 1,
                simulateOrderProgress = false,
                touchTurnEntryFillImmediately = true
            ),
            emit = { events.add(it) }
        )
        engine.handleConnect()
        engine.finishConnect()

        engine.placeTouchTurnBracket(touchTurnPlan())
        val position = events.filterIsInstance<GatewayEvent.PositionsSnapshot>().last().positions
            .firstOrNull { it.symbol == "AAPL" }
        assertTrue(position != null && position.quantity != 0)
    }

    @Test
    fun approachStopEntry_noPositionUntilPriceCrossesLevel() = runBlocking {
        val events = mutableListOf<GatewayEvent>()
        val engine = BrokerEmulatorEngine(
            config = BrokerEmulatorConfig(
                connectDelayMs = 1,
                simulateOrderProgress = false,
                touchTurnEntryScenarioOverride = TouchTurnEntryScenario.APPROACH_AND_FILL,
                touchTurnEntryMinApproachTicks = 2,
                touchTurnEntryStepPctOfRange = 0.15
            ),
            emit = { events.add(it) }
        )
        engine.handleConnect()
        engine.finishConnect()
        engine.ensureStreamingMarketData("AAPL")

        val plan = invertedShortPlan()
        engine.placeTouchTurnBracket(plan)

        assertTrue(
            events.filterIsInstance<GatewayEvent.PositionsSnapshot>().last().positions.isEmpty(),
            "stop entry should not fill on bracket place while price is above the level"
        )

        repeat(6) { engine.runMarketTick() }
        val position = events.filterIsInstance<GatewayEvent.PositionsSnapshot>().last().positions
            .firstOrNull { it.symbol == "AAPL" }
        assertTrue(position != null && position.quantity < 0, "sell stop should fill after price breaks down")
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

    private fun invertedShortPlan() = TouchTurnOrderPlanner.buildOrderPlan(
        symbol = "AAPL",
        setup = TouchTurnBracketSetup(
            range = 2.0,
            rangeThreshold = 0.5,
            isLiquidityCandle = true,
            candleColor = FirstCandleColor.RED,
            side = TouchTurnTradeSide.SHORT,
            entry = 100.0,
            stopLoss = 101.0,
            takeProfit = 99.0
        ),
        maxDollars = 500,
        currencyCode = "USD",
        openingBarClose = 101.0,
        rules = TouchTurnRuleConfig.DEFAULT.copy(invertTradeSide = true)
    )!!.also { plan ->
        assertEquals("STP", plan.orders.first { it.role == TouchTurnOrderRole.ENTRY }.orderType)
    }
}
