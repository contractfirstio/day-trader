package daytrader.presentation.strategies

import daytrader.broker.SessionTradeMatcher
import daytrader.broker.emulator.BrokerEmulatorConfig
import daytrader.broker.emulator.BrokerEmulatorEngine
import daytrader.domain.FirstCandleColor
import daytrader.domain.OhlcBar
import daytrader.domain.TouchTurnBracketSetup
import daytrader.domain.TouchTurnLogic
import daytrader.domain.TouchTurnOrderPlanner
import daytrader.domain.toPlannedBracket
import daytrader.domain.sessionRealizedPnL
import daytrader.gateway.GatewayEvent
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking

class TouchTurnExecutedBracketLegsEmulatorTest {

    @Test
    fun emulatorLossSession_pulsesEntryAndStopNotTakeProfit() = runBlocking {
        val events = mutableListOf<GatewayEvent>()
        val engine = BrokerEmulatorEngine(
            config = BrokerEmulatorConfig(
                connectDelayMs = 1,
                simulateOrderProgress = false,
                bracketWalkStepPctOfRange = 0.5,
                bracketWalkDirectionFlipChance = 0.0
            ),
            emit = { events.add(it) },
            random = kotlin.random.Random(3)
        )
        engine.handleConnect()
        engine.finishConnect()

        val candle = OhlcBar(open = 100.0, high = 102.0, low = 99.0, close = 101.5, time = "20260522  09:30:00")
        val setup = TouchTurnLogic.computeBracketSetup(candle, rangeThreshold = 0.5)
        val plan = TouchTurnOrderPlanner.buildOrderPlan("AAPL", setup, maxDollars = 500, currencyCode = "USD")!!
        engine.placeTouchTurnBracket(plan)

        for (tick in 0 until 40) {
            engine.runMarketTick()
            val positions = events.filterIsInstance<GatewayEvent.PositionsSnapshot>().lastOrNull()?.positions
            if (positions?.none { it.symbol == "AAPL" } == true) break
        }

        val fills = events.filterIsInstance<GatewayEvent.FillsSnapshot>().last().fills
        val trades = SessionTradeMatcher.toSessionTrades(fills)
        val sessionPnl = trades.sessionRealizedPnL()

        assertTrue(sessionPnl < 0, "emulator walk should end in a loss, was $sessionPnl")

        val executed = TouchTurnExecutedBracketLegs.resolve(
            trades = trades,
            plannedBracket = plan.toPlannedBracket(),
            bracketSetup = setup,
            sessionPnl = sessionPnl,
            persistedLegs = emptyList()
        )
        assertTrue(TouchTurnOrderLevelKind.ENTRY in executed, "executed=$executed setup=$setup")
        assertTrue(TouchTurnOrderLevelKind.STOP_LOSS in executed, "executed=$executed fills=$fills")
        assertTrue(TouchTurnOrderLevelKind.TAKE_PROFIT !in executed, "executed=$executed")
    }

    @Test
    fun emulatorWinSession_pulsesEntryAndTakeProfitNotStop() = runBlocking {
        val events = mutableListOf<GatewayEvent>()
        val engine = BrokerEmulatorEngine(
            config = BrokerEmulatorConfig(
                connectDelayMs = 1,
                simulateOrderProgress = false,
                bracketWalkStepPctOfRange = 0.5,
                bracketWalkDirectionFlipChance = 0.0,
                bracketExitTakeProfitProbability = 1.0
            ),
            emit = { events.add(it) },
            random = kotlin.random.Random(3)
        )
        engine.handleConnect()
        engine.finishConnect()

        val candle = OhlcBar(open = 100.0, high = 102.0, low = 99.0, close = 101.5, time = "20260522  09:30:00")
        val setup = TouchTurnLogic.computeBracketSetup(candle, rangeThreshold = 0.5)
        val plan = TouchTurnOrderPlanner.buildOrderPlan("AAPL", setup, maxDollars = 500, currencyCode = "USD")!!
        engine.placeTouchTurnBracket(plan)

        for (tick in 0 until 40) {
            engine.runMarketTick()
            val positions = events.filterIsInstance<GatewayEvent.PositionsSnapshot>().lastOrNull()?.positions
            if (positions?.none { it.symbol == "AAPL" } == true) break
        }

        val fills = events.filterIsInstance<GatewayEvent.FillsSnapshot>().last().fills
        val trades = SessionTradeMatcher.toSessionTrades(fills)
        val sessionPnl = trades.sessionRealizedPnL()

        assertTrue(sessionPnl > 0, "emulator walk should end in a win, was $sessionPnl")

        val executed = TouchTurnExecutedBracketLegs.resolve(
            trades = trades,
            plannedBracket = plan.toPlannedBracket(),
            bracketSetup = setup,
            sessionPnl = -sessionPnl
        )
        assertTrue(TouchTurnOrderLevelKind.ENTRY in executed, "executed=$executed setup=$setup")
        assertTrue(TouchTurnOrderLevelKind.TAKE_PROFIT in executed, "executed=$executed fills=$fills")
        assertTrue(TouchTurnOrderLevelKind.STOP_LOSS !in executed, "executed=$executed")
    }
}
