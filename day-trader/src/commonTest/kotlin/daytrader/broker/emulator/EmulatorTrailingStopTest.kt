package daytrader.broker.emulator

import daytrader.domain.FirstCandleColor
import daytrader.domain.TouchTurnBracketSetup
import daytrader.domain.TouchTurnOrderPlanner
import daytrader.domain.TouchTurnOrderRole
import daytrader.domain.TouchTurnTradeSide
import daytrader.domain.toPlannedBracket
import daytrader.gateway.GatewayEvent
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking

class EmulatorTrailingStopTest {

    @Test
    fun trailingStop_armsAfterTrigger_andStopPriceMovesWithPrice() = runBlocking {
        val events = mutableListOf<GatewayEvent>()
        val engine = BrokerEmulatorEngine(
            config = BrokerEmulatorConfig(
                connectDelayMs = 1,
                simulateOrderProgress = false,
                touchTurnEntryFillImmediately = true,
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
        assertEquals(2.5, stopLeg.trailAmount!!, 0.001)

        engine.placeTouchTurnBracket(plan)
        engine.runMarketTick()

        var sawTrailOrder = false
        var sawMovingStop = false
        var previousStop: Double? = null
        repeat(40) {
            engine.runMarketTick()
            val orders = events.filterIsInstance<GatewayEvent.OpenOrdersSnapshot>().lastOrNull()?.orders.orEmpty()
            val stop = orders.firstOrNull { it.orderType.equals("TRAIL", ignoreCase = true) }
            if (stop != null) {
                sawTrailOrder = true
                val current = stop.stopPrice
                if (current != null && previousStop != null && current > previousStop!!) {
                    sawMovingStop = true
                }
                previousStop = current
            }
        }
        assertTrue(sawTrailOrder, "expected stop to convert to TRAIL after trigger")
        assertTrue(sawMovingStop, "expected trailing stop price to rise with favorable price walk")
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
        assertNotNull(stop.trailAmount)
    }
}
