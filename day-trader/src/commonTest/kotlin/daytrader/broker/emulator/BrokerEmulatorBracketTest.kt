package daytrader.broker.emulator

import daytrader.domain.FirstCandleColor
import daytrader.domain.OhlcBar
import daytrader.domain.TouchTurnBracketSetup
import daytrader.domain.TouchTurnLogic
import daytrader.domain.TouchTurnOrderPlanner
import daytrader.domain.TouchTurnTradeSide
import daytrader.gateway.GatewayEvent
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import kotlin.random.Random

class BrokerEmulatorBracketTest {

    @Test
    fun placeBracket_entryFillOnTick_createsPosition() = runBlocking {
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

        val setup = TouchTurnBracketSetup(
            range = 2.0,
            rangeThreshold = 0.5,
            isLiquidityCandle = true,
            candleColor = FirstCandleColor.RED,
            side = TouchTurnTradeSide.LONG,
            entry = 100.0,
            stopLoss = 99.0,
            takeProfit = 101.0
        )
        val plan = TouchTurnOrderPlanner.buildOrderPlan(
            "AAPL",
            setup,
            maxDollars = 500,
            currencyCode = "USD",
            openingBarClose = 101.0
        )!!

        engine.placeTouchTurnBracket(plan)
        var sawEntryPosition = false
        repeat(12) {
            engine.runMarketTick()
            val aapl = events.filterIsInstance<GatewayEvent.PositionsSnapshot>().last().positions
                .firstOrNull { it.symbol == "AAPL" }
            if (aapl != null && aapl.quantity != 0) {
                sawEntryPosition = true
                return@repeat
            }
        }
        assertTrue(sawEntryPosition, "expected AAPL position after entry fill (before bracket exit)")
    }

    @Test
    fun bracketWalk_afterEntry_eventuallyClosesPositionViaTpOrStop() = runBlocking {
        val events = mutableListOf<GatewayEvent>()
        val engine = BrokerEmulatorEngine(
            config = BrokerEmulatorConfig(
                connectDelayMs = 1,
                simulateOrderProgress = false,
                touchTurnEntryFillImmediately = true,
                bracketWalkStepPctOfRange = 0.2,
                bracketWalkDirectionFlipChance = 0.0
            ),
            emit = { events.add(it) },
            random = kotlin.random.Random(7)
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

        var closed = false
        for (tick in 0 until 30) {
            engine.runMarketTick()
            val positions = events.filterIsInstance<GatewayEvent.PositionsSnapshot>().lastOrNull()?.positions
            if (positions?.none { it.symbol == "AAPL" } == true) {
                closed = true
                break
            }
        }
        assertTrue(closed, "position should close within 30 ticks")

        val finalPositions = events.filterIsInstance<GatewayEvent.PositionsSnapshot>().last().positions
        assertTrue(
            finalPositions.none { it.symbol == "AAPL" },
            "expected AAPL position closed after TP or STOP fill"
        )
    }

    @Test
    fun bracketWalk_touchTurnGeometry_balancedWinsAndLosses() = runBlocking {
        val bar = OhlcBar(open = 410.0, high = 410.0, low = 400.0, close = 402.0)
        val setup = TouchTurnLogic.computeBracketSetup(bar, rangeThreshold = 5.0)!!
        var takeProfitExits = 0
        var stopLossExits = 0
        repeat(40) { seed ->
            val events = mutableListOf<GatewayEvent>()
            val engine = BrokerEmulatorEngine(
                config = BrokerEmulatorConfig(
                    connectDelayMs = 1,
                    simulateOrderProgress = false,
                    touchTurnEntryFillImmediately = true,
                    bracketWalkStepPctOfRange = 0.18,
                    bracketWalkDirectionFlipChance = 0.2,
                    bracketExitSpreadWidenFactor = 1.35,
                    bracketExitTakeProfitProbability = 0.5,
                    bracketWalkSteerTowardTargetProbability = 0.88
                ),
                emit = { events.add(it) },
                random = Random(seed)
            )
            engine.handleConnect()
            engine.finishConnect()

            val plan = TouchTurnOrderPlanner.buildOrderPlan("AAPL", setup, maxDollars = 500, currencyCode = "USD")!!
            engine.placeTouchTurnBracket(plan)
            engine.runMarketTick()

            for (tick in 0 until 40) {
                engine.runMarketTick()
                val positions = events.filterIsInstance<GatewayEvent.PositionsSnapshot>().lastOrNull()?.positions
                if (positions?.none { it.symbol == "AAPL" } == true) break
            }

            val exitFill = events.filterIsInstance<GatewayEvent.FillsSnapshot>()
                .lastOrNull()
                ?.fills
                ?.lastOrNull { (it.realizedPnL ?: 0.0) != 0.0 && it.parentOrderId != 0 }
                ?: return@repeat
            val widened = EmulatorBracketPlanAdjuster.widenExits(plan, spreadWidenFactor = 1.35)
            val tp = EmulatorBracketPlanAdjuster.takeProfitPrice(widened)!!
            val sl = EmulatorBracketPlanAdjuster.stopLossPrice(widened)!!
            when {
                kotlin.math.abs(exitFill.price - tp) < 0.05 -> takeProfitExits++
                kotlin.math.abs(exitFill.price - sl) < 0.05 -> stopLossExits++
            }
        }
        assertTrue(
            takeProfitExits in 12..28,
            "expected ~50% TP exits over 40 runs, got TP=$takeProfitExits SL=$stopLossExits"
        )
        assertTrue(
            stopLossExits in 12..28,
            "expected ~50% SL exits over 40 runs, got TP=$takeProfitExits SL=$stopLossExits"
        )
    }
}
