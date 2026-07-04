package daytrader.broker.emulator

import daytrader.domain.FiveMinuteConfirmationLogic
import daytrader.domain.OhlcBar
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
        val plan = TouchTurnOrderPlanner.buildHammerConfirmationOrderPlan(
            symbol = "0700",
            hammerBar = hammer,
            side = TouchTurnTradeSide.SHORT,
            maxDollars = 500,
            currencyCode = "HKD",
            rules = TouchTurnRuleConfig.DEFAULT
        )!!
        assertEquals("MKT", plan.orders.first { it.role == TouchTurnOrderRole.ENTRY }.orderType)

        engine.placeTouchTurnBracket(plan)

        val entryFill = events.filterIsInstance<GatewayEvent.FillsSnapshot>()
            .flatMap { it.fills }
            .first { it.parentOrderId == 0 && it.side == "SELL" }
        val maxSlippageFromHammerClose = 0.10
        assertTrue(
            kotlin.math.abs(entryFill.price - hammer.close) <= maxSlippageFromHammerClose,
            "MKT short entry should fill near hammer close ${hammer.close}, got ${entryFill.price}"
        )

        val tp = plan.orders.first { it.role == TouchTurnOrderRole.TAKE_PROFIT }.price
        val stop = plan.orders.first { it.role == TouchTurnOrderRole.STOP_LOSS }.price
        assertTrue(entryFill.price - tp > 0.2, "TP should remain meaningful distance below entry fill")
        assertTrue(stop - entryFill.price > 0.2, "Stop should remain meaningful distance above entry fill")
    }
}
