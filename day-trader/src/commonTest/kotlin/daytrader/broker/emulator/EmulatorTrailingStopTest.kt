package daytrader.broker.emulator

import daytrader.domain.FirstCandleColor
import daytrader.domain.TouchTurnBracketSetup
import daytrader.domain.TouchTurnOrderPlanner
import daytrader.domain.TouchTurnOrderRole
import daytrader.domain.TouchTurnTradeSide
import daytrader.domain.toPlannedBracket
import daytrader.gateway.GatewayEvent
import daytrader.gateway.LiveQuote
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking

class EmulatorTrailingStopTest {

    @Test
    fun trailingStop_armsAtEntry_andStopPriceRatchetsUp() = runBlocking {
        val events = mutableListOf<GatewayEvent>()
        val engine = BrokerEmulatorEngine(
            config = BrokerEmulatorConfig(
                connectDelayMs = 1,
                simulateOrderProgress = false,
                touchTurnEntryFillImmediately = true,
                bracketExitSpreadWidenFactor = 1.0,
                bracketWalkStepPctOfRange = 0.05,
                bracketWalkDirectionFlipChance = 0.0,
                bracketWalkSteerTowardTargetProbability = 1.0,
                bracketExitTakeProfitProbability = 1.0
            ),
            emit = { events.add(it) },
            random = kotlin.random.Random(3)
        )
        engine.handleConnect()
        engine.finishConnect()
        engine.ensureStreamingMarketData("AAPL")

        val setup = TouchTurnBracketSetup(
            range = 10.0,
            rangeThreshold = 0.5,
            isLiquidityCandle = true,
            candleColor = FirstCandleColor.RED,
            side = TouchTurnTradeSide.LONG,
            entry = 100.0,
            stopLoss = 95.0,
            takeProfit = 110.0
        )
        val plan = TouchTurnOrderPlanner.buildOrderPlan("AAPL", setup, maxDollars = 1000, currencyCode = "USD")!!
        val stopLeg = plan.orders.first { it.role == TouchTurnOrderRole.STOP_LOSS }
        assertEquals(105.0, stopLeg.trailTriggerPrice!!, 0.001)
        assertEquals(100.0, stopLeg.trailArmStopPrice!!, 0.001)

        engine.placeTouchTurnBracket(plan)
        engine.runMarketTick()

        var sawTrailOrder = false
        var firstTrailStop: Double? = null
        var sawMovingStop = false
        var previousStop: Double? = null
        repeat(40) {
            engine.runMarketTick()
            val orders = events.filterIsInstance<GatewayEvent.OpenOrdersSnapshot>().lastOrNull()?.orders.orEmpty()
            val stop = orders.firstOrNull { it.orderType.equals("TRAIL", ignoreCase = true) }
            if (stop != null) {
                sawTrailOrder = true
                val current = stop.stopPrice
                if (firstTrailStop == null && current != null) {
                    firstTrailStop = current
                }
                if (current != null && previousStop != null && current > previousStop!!) {
                    sawMovingStop = true
                }
                previousStop = current
            }
        }
        assertTrue(sawTrailOrder, "expected stop to convert to TRAIL after trigger")
        assertEquals(100.0, firstTrailStop!!, 0.001)
        assertTrue(sawMovingStop, "expected trailing stop price to rise with favorable price walk")
    }

    @Test
    fun trailingStop_afterLateTrigger_doesNotJumpToBidMinusTrail() = runBlocking {
        val events = mutableListOf<GatewayEvent>()
        val engine = BrokerEmulatorEngine(
            config = BrokerEmulatorConfig.forLiveIbMarketData().copy(
                connectDelayMs = 1,
                simulateOrderProgress = false,
                touchTurnEntryFillImmediately = true,
                bracketExitSpreadWidenFactor = 1.0
            ),
            emit = { events.add(it) }
        )
        engine.handleConnect()
        engine.finishConnect()

        val setup = TouchTurnBracketSetup(
            range = 10.0,
            rangeThreshold = 0.5,
            isLiquidityCandle = true,
            candleColor = FirstCandleColor.RED,
            side = TouchTurnTradeSide.LONG,
            entry = 100.0,
            stopLoss = 95.0,
            takeProfit = 110.0
        )
        val plan = TouchTurnOrderPlanner.buildOrderPlan("AAPL", setup, maxDollars = 1000, currencyCode = "USD")!!
        engine.placeTouchTurnBracket(plan)

        fun stopPrice(): Double? =
            events.filterIsInstance<GatewayEvent.OpenOrdersSnapshot>()
                .lastOrNull()
                ?.orders
                ?.firstOrNull { it.orderType.equals("TRAIL", ignoreCase = true) }
                ?.stopPrice

        engine.ingestLiveQuote(
            "AAPL",
            LiveQuote(symbol = "AAPL", bid = 106.0, ask = 106.2, last = 106.1),
            priorClose = null
        )
        assertEquals(100.0, stopPrice()!!, 0.001)

        engine.ingestLiveQuote(
            "AAPL",
            LiveQuote(symbol = "AAPL", bid = 106.0, ask = 106.2, last = 106.1),
            priorClose = null
        )
        assertEquals(100.0, stopPrice()!!, 0.001)

        engine.ingestLiveQuote(
            "AAPL",
            LiveQuote(symbol = "AAPL", bid = 107.0, ask = 107.2, last = 107.1),
            priorClose = null
        )
        assertEquals(101.0, stopPrice()!!, 0.001)
    }

    @Test
    fun trailingStop_armCushion_armsBelowEntry() = runBlocking {
        val events = mutableListOf<GatewayEvent>()
        val engine = BrokerEmulatorEngine(
            config = BrokerEmulatorConfig.forLiveIbMarketData().copy(
                connectDelayMs = 1,
                simulateOrderProgress = false,
                touchTurnEntryFillImmediately = true,
                bracketExitSpreadWidenFactor = 1.0
            ),
            emit = { events.add(it) }
        )
        engine.handleConnect()
        engine.finishConnect()

        val setup = TouchTurnBracketSetup(
            range = 10.0,
            rangeThreshold = 0.5,
            isLiquidityCandle = true,
            candleColor = FirstCandleColor.RED,
            side = TouchTurnTradeSide.LONG,
            entry = 100.0,
            stopLoss = 95.0,
            takeProfit = 110.0
        )
        val rules = daytrader.domain.TouchTurnRuleConfig.DEFAULT.copy(
            trailingStopArmOffsetFractionOfBarRange = 0.05
        )
        val plan = TouchTurnOrderPlanner.buildOrderPlan(
            "AAPL",
            setup,
            maxDollars = 1000,
            currencyCode = "USD",
            rules = rules
        )!!
        assertEquals(99.5, plan.orders.first { it.role == TouchTurnOrderRole.STOP_LOSS }.trailArmStopPrice!!, 0.001)
        engine.placeTouchTurnBracket(plan)

        engine.ingestLiveQuote(
            "AAPL",
            LiveQuote(symbol = "AAPL", bid = 106.0, ask = 106.2, last = 106.1),
            priorClose = null
        )
        val stopPrice = events.filterIsInstance<GatewayEvent.OpenOrdersSnapshot>()
            .lastOrNull()
            ?.orders
            ?.firstOrNull { it.orderType.equals("TRAIL", ignoreCase = true) }
            ?.stopPrice
        assertEquals(99.5, stopPrice!!, 0.001)
    }

    @Test
    fun plan_includesAdjustableStopMetadata() {
        val setup = TouchTurnBracketSetup(
            range = 10.0,
            rangeThreshold = 0.5,
            isLiquidityCandle = true,
            candleColor = FirstCandleColor.RED,
            side = TouchTurnTradeSide.LONG,
            entry = 100.0,
            stopLoss = 95.0,
            takeProfit = 110.0
        )
        val plan = TouchTurnOrderPlanner.buildOrderPlan("AAPL", setup, maxDollars = 1000)!!
        val bracket = plan.toPlannedBracket()
        assertEquals(105.0, bracket.trailTriggerPrice!!, 0.001)
        val stop = plan.orders.first { it.role == TouchTurnOrderRole.STOP_LOSS }
        assertNotNull(stop.trailTriggerPrice)
        assertNotNull(stop.trailArmStopPrice)
    }
}
