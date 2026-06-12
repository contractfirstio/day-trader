package daytrader.broker.emulator

import daytrader.domain.TouchTurnBracketOrderIds
import daytrader.domain.TouchTurnBracketResizeRequest
import daytrader.domain.TouchTurnOrderPlan
import daytrader.domain.TouchTurnOrderRole
import daytrader.domain.TouchTurnPlannedOrder
import daytrader.domain.TouchTurnTradeSide
import daytrader.gateway.GatewayEvent
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking

class BrokerEmulatorBracketResizeTest {
    @Test
    fun resizeTouchTurnBracket_updatesAllLegQuantities() = runBlocking {
        val events = mutableListOf<GatewayEvent>()
        val engine = BrokerEmulatorEngine(
            config = BrokerEmulatorConfig.Default,
            emit = { events.add(it) }
        )
        engine.handleConnect()
        engine.finishConnect()
        val plan = touchTurnPlan(quantity = 5)
        engine.placeTouchTurnBracket(plan)
        val ack = events.filterIsInstance<GatewayEvent.TouchTurnBracketPlaced>().last().ack
        assertTrue(ack.result.isSuccess)
        val orderIds = TouchTurnBracketOrderIds.fromAckOrderIds(ack.orderIds)
        assertNotNull(orderIds)
        val request = TouchTurnBracketResizeRequest(
            symbol = "AAPL",
            currencyCode = "USD",
            instrument = null,
            orderIds = orderIds,
            plan = touchTurnPlan(quantity = 10)
        )
        val result = engine.resizeTouchTurnBracket(request)
        assertTrue(result.isSuccess)
        assertEquals(10, result.getOrThrow())
    }

    private fun touchTurnPlan(quantity: Int): TouchTurnOrderPlan =
        TouchTurnOrderPlan(
            symbol = "AAPL",
            currencyCode = "USD",
            side = TouchTurnTradeSide.LONG,
            quantity = quantity,
            orders = listOf(
                TouchTurnPlannedOrder(
                    role = TouchTurnOrderRole.ENTRY,
                    action = "BUY",
                    orderType = "LMT",
                    quantity = quantity,
                    price = 100.0
                ),
                TouchTurnPlannedOrder(
                    role = TouchTurnOrderRole.TAKE_PROFIT,
                    action = "SELL",
                    orderType = "LMT",
                    quantity = quantity,
                    price = 110.0
                ),
                TouchTurnPlannedOrder(
                    role = TouchTurnOrderRole.STOP_LOSS,
                    action = "SELL",
                    orderType = "STP",
                    quantity = quantity,
                    price = 95.0
                )
            )
        )
}
