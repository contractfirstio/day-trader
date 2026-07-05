package daytrader.broker

import com.ib.client.EJavaSignal
import com.ib.client.DefaultEWrapper
import com.ib.client.EClientSocket
import com.ib.client.OrderType
import com.ib.client.Types
import daytrader.domain.TouchTurnBracketOrderIds
import daytrader.domain.TouchTurnOrderPlan
import daytrader.domain.TouchTurnOrderDefaults
import daytrader.domain.TouchTurnOrderRole
import daytrader.domain.TouchTurnPlannedOrder
import daytrader.domain.TouchTurnTradeSide
import daytrader.e2e.support.E2EBracketHelper
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Tier 2: exercises production [IbTouchTurnBracketPlacer] wiring (IB BDD mocks stop at [daytrader.engine.support.FakeBrokerGateway]).
 */
class IbTouchTurnBracketPlacerTest {
    private val config = IbGatewayConfig(host = "127.0.0.1", port = 4002, clientId = 7, accountCode = "DU123")

    @Test
    fun build_notConnected_returnsNull() {
        val result = IbTouchTurnBracketPlacer.build(
            client = TestEClientSocket(connected = false),
            config = config,
            plan = standardPlan(),
            allocateOrderIds = { 100 },
        )
        assertNull(result)
    }

    @Test
    fun build_missingBracketLeg_returnsNull() {
        val incomplete = standardPlan().copy(
            orders = standardPlan().orders.filter { it.role != TouchTurnOrderRole.STOP_LOSS }
        )
        val result = IbTouchTurnBracketPlacer.build(
            client = TestEClientSocket(connected = true),
            config = config,
            plan = incomplete,
            allocateOrderIds = { 100 },
        )
        assertNull(result)
    }

    @Test
    fun build_nonPositiveQuantity_returnsNull() {
        val plan = standardPlan().copy(
            orders = standardPlan().orders.map { leg ->
                if (leg.role == TouchTurnOrderRole.ENTRY) leg.copy(quantity = 0) else leg
            }
        )
        val result = IbTouchTurnBracketPlacer.build(
            client = TestEClientSocket(connected = true),
            config = config,
            plan = plan,
            allocateOrderIds = { 100 },
        )
        assertNull(result)
    }

    @Test
    fun build_orderIdsNotReady_returnsNull() {
        val result = IbTouchTurnBracketPlacer.build(
            client = TestEClientSocket(connected = true),
            config = config,
            plan = standardPlan(),
            allocateOrderIds = { null },
        )
        assertNull(result)
    }

    @Test
    fun build_standardBracket_assignsMixedTimeInForce() {
        val plan = standardPlan()
        val submission = IbTouchTurnBracketPlacer.build(
            client = TestEClientSocket(connected = true),
            config = config,
            plan = plan,
            allocateOrderIds = { 500 },
        )
        assertNotNull(submission)
        assertEquals(Types.TimeInForce.DAY, submission.parent.tif())
        assertEquals(Types.TimeInForce.GTC, submission.takeProfit.tif())
        assertEquals(Types.TimeInForce.GTC, submission.stopLoss.tif())
    }

    @Test
    fun build_standardBracket_assignsSequentialIdsAndTransmitFlags() {
        val submission = IbTouchTurnBracketPlacer.build(
            client = TestEClientSocket(connected = true),
            config = config,
            plan = standardPlan(),
            allocateOrderIds = { count ->
                assertEquals(3, count)
                500
            },
        )
        assertNotNull(submission)
        assertEquals("AAPL", submission.symbol)
        assertEquals(500, submission.parentOrderId)
        assertEquals(501, submission.takeProfitOrderId)
        assertEquals(502, submission.stopLossOrderId)
        assertNull(submission.adjustableStopOrderId)
        assertNull(submission.adjustableStop)
        assertFalse(submission.parent.transmit())
        assertFalse(submission.takeProfit.transmit())
        assertTrue(submission.stopLoss.transmit())
        assertEquals(7, submission.parent.clientId())
        assertEquals("DU123", submission.parent.account())
        assertEquals(500, submission.parent.orderId())
        assertEquals(0, submission.parent.parentId())
        assertEquals(500, submission.takeProfit.parentId())
        assertEquals(500, submission.stopLoss.parentId())
    }

    @Test
    fun build_trailingStopPlan_emitsAdjustableStopLegWithTransmit() {
        val submission = IbTouchTurnBracketPlacer.build(
            client = TestEClientSocket(connected = true),
            config = config,
            plan = E2EBracketHelper.trailingLiquidityPlan(),
            allocateOrderIds = { count ->
                assertEquals(4, count)
                600
            },
        )
        assertNotNull(submission)
        assertEquals(603, submission.adjustableStopOrderId)
        assertNotNull(submission.adjustableStop)
        assertFalse(submission.stopLoss.transmit())
        assertTrue(submission.adjustableStop!!.transmit())
        assertEquals(OrderType.TRAIL, submission.adjustableStop!!.adjustedOrderType())
        assertEquals(602, submission.adjustableStop!!.parentId())
    }

    @Test
    fun buildResize_reusesExistingOrderIds() {
        val orderIds = TouchTurnBracketOrderIds(
            parentOrderId = 700,
            takeProfitOrderId = 701,
            stopLossOrderId = 702,
            adjustableStopOrderId = null,
        )
        val submission = IbTouchTurnBracketPlacer.buildResize(
            config = config,
            plan = E2EBracketHelper.liquidityPlan(entry = 100.5, stopLoss = 99.5, takeProfit = 101.5),
            orderIds = orderIds,
        )
        assertNotNull(submission)
        assertEquals(orderIds.parentOrderId, submission.parentOrderId)
        assertEquals(orderIds.takeProfitOrderId, submission.takeProfitOrderId)
        assertEquals(orderIds.stopLossOrderId, submission.stopLossOrderId)
        assertTrue(submission.stopLoss.transmit())
    }

    @Test
    fun buildResize_missingEntry_returnsNull() {
        val plan = standardPlan().copy(
            orders = standardPlan().orders.filter { it.role != TouchTurnOrderRole.ENTRY }
        )
        val result = IbTouchTurnBracketPlacer.buildResize(
            config = config,
            plan = plan,
            orderIds = TouchTurnBracketOrderIds(1, 2, 3, null),
        )
        assertNull(result)
    }

    private fun standardPlan(): TouchTurnOrderPlan = TouchTurnOrderPlan(
        symbol = "AAPL",
        currencyCode = "USD",
        side = TouchTurnTradeSide.LONG,
        quantity = 100,
        orders = listOf(
            TouchTurnPlannedOrder(
                role = TouchTurnOrderRole.ENTRY,
                action = "BUY",
                orderType = "STP",
                price = 100.0,
                quantity = 100,
                timeInForce = TouchTurnOrderDefaults.ENTRY_TIME_IN_FORCE,
            ),
            TouchTurnPlannedOrder(
                role = TouchTurnOrderRole.TAKE_PROFIT,
                action = "SELL",
                orderType = "LMT",
                price = 101.0,
                quantity = 100,
                timeInForce = TouchTurnOrderDefaults.PROTECTIVE_LEG_TIME_IN_FORCE,
            ),
            TouchTurnPlannedOrder(
                role = TouchTurnOrderRole.STOP_LOSS,
                action = "SELL",
                orderType = "STP",
                price = 99.0,
                quantity = 100,
                timeInForce = TouchTurnOrderDefaults.PROTECTIVE_LEG_TIME_IN_FORCE,
            ),
        ),
    )

    private class TestEClientSocket(connected: Boolean) : EClientSocket(DefaultEWrapper(), EJavaSignal()) {
        private val connectedFlag = connected

        override fun isConnected(): Boolean = connectedFlag
    }
}
