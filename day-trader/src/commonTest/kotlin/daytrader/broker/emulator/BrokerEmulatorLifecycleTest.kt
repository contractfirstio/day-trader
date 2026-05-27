package daytrader.broker.emulator

import daytrader.domain.FirstCandleColor
import daytrader.domain.TouchTurnBracketSetup
import daytrader.domain.TouchTurnOrderPlanner
import daytrader.domain.TouchTurnTradeSide
import daytrader.gateway.GatewayEvent
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking

class BrokerEmulatorLifecycleTest {

    @Test
    fun placeBracket_fillsEntryImmediately_withPosition() = runBlocking {
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

        val setup = shortLiquiditySetup()
        val plan = TouchTurnOrderPlanner.buildOrderPlan("AAPL", setup, maxDollars = 500, currencyCode = "USD")!!

        engine.placeTouchTurnBracket(plan)

        val position = events.filterIsInstance<GatewayEvent.PositionsSnapshot>().last()
            .positions.first { it.symbol == "AAPL" }
        assertTrue(position.quantity != 0, "entry should open a position immediately")
        val fills = events.filterIsInstance<GatewayEvent.FillsSnapshot>().last().fills
        assertTrue(fills.isNotEmpty(), "entry fill should be recorded")
    }

    @Test
    fun bracketWalk_updatesUnrealizedPnL_afterEntry() = runBlocking {
        val events = mutableListOf<GatewayEvent>()
        val engine = BrokerEmulatorEngine(
            config = BrokerEmulatorConfig(
                connectDelayMs = 1,
                simulateOrderProgress = false,
                touchTurnEntryFillImmediately = true,
                bracketWalkStepPctOfRange = 0.35,
                bracketWalkDirectionFlipChance = 0.0
            ),
            emit = { events.add(it) },
            random = kotlin.random.Random(1)
        )
        engine.handleConnect()
        engine.finishConnect()

        val setup = shortLiquiditySetup()
        val plan = TouchTurnOrderPlanner.buildOrderPlan("AAPL", setup, maxDollars = 500, currencyCode = "USD")!!
        engine.placeTouchTurnBracket(plan)

        val entrySnapshot = events.toList().filterIsInstance<GatewayEvent.PositionsSnapshot>().last()
            .positions.first { it.symbol == "AAPL" }

        engine.runMarketTick()

        val afterWalk = events.toList().filterIsInstance<GatewayEvent.PositionsSnapshot>().last()
            .positions.firstOrNull { it.symbol == "AAPL" }
        assertTrue(afterWalk != null, "position should still be open during walk")
        assertNotEquals(
            entrySnapshot.marketPrice,
            afterWalk!!.marketPrice,
            "market walk should update mark price"
        )
        assertNotEquals(
            entrySnapshot.totalUnrealizedPnL,
            afterWalk.totalUnrealizedPnL,
            "mark change should update unrealized P&L"
        )
    }

    @Test
    fun exitFill_recordsRealizedPnL() = runBlocking {
        val events = mutableListOf<GatewayEvent>()
        val engine = BrokerEmulatorEngine(
            config = BrokerEmulatorConfig(
                connectDelayMs = 1,
                simulateOrderProgress = false,
                touchTurnEntryFillImmediately = true,
                bracketWalkStepPctOfRange = 0.5,
                bracketWalkDirectionFlipChance = 0.0
            ),
            emit = { events.add(it) },
            random = kotlin.random.Random(3)
        )
        engine.handleConnect()
        engine.finishConnect()

        val setup = shortLiquiditySetup()
        val plan = TouchTurnOrderPlanner.buildOrderPlan("AAPL", setup, maxDollars = 500, currencyCode = "USD")!!
        engine.placeTouchTurnBracket(plan)

        for (tick in 0 until 40) {
            engine.runMarketTick()
            val positions = events.toList().filterIsInstance<GatewayEvent.PositionsSnapshot>()
                .lastOrNull()?.positions
            if (positions?.none { it.symbol == "AAPL" } == true) break
        }

        val fills = events.toList().filterIsInstance<GatewayEvent.FillsSnapshot>().last().fills
        val exitFill = fills.lastOrNull { (it.realizedPnL ?: 0.0) != 0.0 }
        assertTrue(exitFill != null, "exit fill should carry realized P&L")
        assertTrue((exitFill!!.realizedPnL ?: 0.0) != 0.0)
    }

    private fun shortLiquiditySetup() = TouchTurnBracketSetup(
        range = 2.0,
        rangeThreshold = 0.5,
        isLiquidityCandle = true,
        candleColor = FirstCandleColor.GREEN,
        side = TouchTurnTradeSide.SHORT,
        entry = 102.0,
        stopLoss = 103.0,
        takeProfit = 100.0
    )
}
