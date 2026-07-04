package daytrader.broker.emulator

import daytrader.domain.FirstCandleColor
import daytrader.domain.FiveMinuteConfirmationLogic
import daytrader.domain.OhlcBar
import daytrader.domain.TouchTurnBracketSetup
import daytrader.domain.TouchTurnOrderPlanner
import daytrader.domain.TouchTurnOrderRole
import daytrader.domain.TouchTurnRuleConfig
import daytrader.domain.TouchTurnTradeSide
import daytrader.gateway.GatewayEvent
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking

class EmulatorHammerMktEntryTest {
    @Test
    fun hammerMktBracket_fillsNearHammerClose_notDepressedApproachMark() = runBlocking {
        val events = mutableListOf<GatewayEvent>()
        val engine = BrokerEmulatorEngine(
            config = BrokerEmulatorConfig(
                connectDelayMs = 1,
                simulateOrderProgress = false,
                touchTurnEntryScenarioOverride = TouchTurnEntryScenario.APPROACH_AND_FILL
            ),
            emit = { events.add(it) }
        )
        engine.handleConnect()
        engine.finishConnect()
        engine.ensureStreamingMarketData("0700")

        val hammer = OhlcBar(
            open = 382.381277696,
            high = 382.689645056,
            low = 382.365859328,
            close = 382.4275328,
            time = "20260704  12:34:40"
        )
        assertTrue(FiveMinuteConfirmationLogic.isHammerPattern(hammer, TouchTurnTradeSide.SHORT))
        val fifteenMinuteSetup = TouchTurnBracketSetup(
            range = 4.0,
            rangeThreshold = 0.0,
            isLiquidityCandle = true,
            candleColor = FirstCandleColor.GREEN,
            side = TouchTurnTradeSide.SHORT,
            entry = 384.0,
            stopLoss = 385.0,
            takeProfit = 382.0
        )
        val plan = TouchTurnOrderPlanner.buildHammerConfirmationOrderPlan(
            symbol = "0700",
            fifteenMinuteSetup = fifteenMinuteSetup,
            hammerBar = hammer,
            maxDollars = 500,
            currencyCode = "HKD",
            rules = TouchTurnRuleConfig.DEFAULT
        )!!
        assertEquals("MKT", plan.orders.first { it.role == TouchTurnOrderRole.ENTRY }.orderType)

        val tp = plan.orders.first { it.role == TouchTurnOrderRole.TAKE_PROFIT }.price
        val stop = plan.orders.first { it.role == TouchTurnOrderRole.STOP_LOSS }.price
        val planEntry = plan.orders.first { it.role == TouchTurnOrderRole.ENTRY }.price
        val confirmationSetup = FiveMinuteConfirmationLogic.buildConfirmationSetup(
            fifteenMinuteSetup,
            hammer.close
        )
        assertEquals(fifteenMinuteSetup.takeProfit, tp)
        assertEquals(confirmationSetup.stopLoss, stop)
        val plannedReward = planEntry - tp
        val plannedRisk = stop - planEntry
        assertTrue(plannedReward > 0.2, "TP should remain meaningful distance below planned entry")
        assertTrue(plannedRisk > 0.2, "Stop should remain meaningful distance above planned entry")
        assertEquals(2.0, plannedReward / plannedRisk, absoluteTolerance = 1e-9)

        engine.placeTouchTurnBracket(plan)

        val entryFill = events.filterIsInstance<GatewayEvent.FillsSnapshot>()
            .flatMap { it.fills }
            .first { it.parentOrderId == 0 && it.side == "SELL" }
        val maxSlippageFromHammerClose = 0.10
        assertTrue(
            kotlin.math.abs(entryFill.price - hammer.close) <= maxSlippageFromHammerClose,
            "MKT short entry should fill near hammer close ${hammer.close}, got ${entryFill.price}"
        )
    }
}
