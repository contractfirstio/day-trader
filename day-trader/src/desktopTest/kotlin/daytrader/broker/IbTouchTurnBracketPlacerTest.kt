package daytrader.broker

import com.ib.client.EJavaSignal
import com.ib.client.DefaultEWrapper
import com.ib.client.EClientSocket
import com.ib.client.OrderType
import com.ib.client.Types
import daytrader.domain.FirstCandleColor
import daytrader.domain.TouchTurnBracketOrderIds
import daytrader.domain.TouchTurnBracketSetup
import daytrader.domain.TouchTurnOrderPlan
import daytrader.domain.TouchTurnOrderDefaults
import daytrader.domain.TouchTurnOrderPlanner
import daytrader.domain.TouchTurnOrderRole
import daytrader.domain.TouchTurnPlannedOrder
import daytrader.domain.TouchTurnRuleConfig
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
        val plan = E2EBracketHelper.trailingLiquidityPlan()
        val stopLoss = plan.orders.first { it.role == TouchTurnOrderRole.STOP_LOSS }
        val expectedTrailAmount = daytrader.domain.TouchTurnAdjustableStop.nominalTrailAmount(
            stopLoss.trailTriggerPrice!!,
            stopLoss.trailArmStopPrice!!
        )

        val submission = IbTouchTurnBracketPlacer.build(
            client = TestEClientSocket(connected = true),
            config = config,
            plan = plan,
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
        // IB rejects adjustedTrailingAmount <= 0 ("invalid adjusted trailing amount").
        assertTrue(expectedTrailAmount > 0.0, "fixture must yield a positive trail amount")
        assertEquals(expectedTrailAmount, submission.adjustableStop!!.adjustedTrailingAmount(), 0.0001)
        assertEquals(0, submission.adjustableStop!!.adjustableTrailingUnit())
        assertEquals(stopLoss.trailArmStopPrice!!, submission.adjustableStop!!.adjustedStopPrice(), 0.0001)
        assertEquals(stopLoss.trailTriggerPrice!!, submission.adjustableStop!!.triggerPrice(), 0.0001)
    }

    @Test
    fun build_sessionPlanWithTrailingEnabled_sendsPositiveIbTrailAmount() {
        val plan = TouchTurnOrderPlanner.buildOrderPlan(
            symbol = "AAPL",
            setup = TouchTurnBracketSetup(
                range = 10.0,
                rangeThreshold = 0.5,
                isLiquidityCandle = true,
                candleColor = FirstCandleColor.GREEN,
                side = TouchTurnTradeSide.LONG,
                entry = 100.0,
                stopLoss = 95.0,
                takeProfit = 110.0
            ),
            maxDollars = 1000,
            rules = TouchTurnRuleConfig.DEFAULT
        )!!
        assertTrue(TouchTurnRuleConfig.DEFAULT.enables.adjustableTrailingStop)

        val submission = IbTouchTurnBracketPlacer.build(
            client = TestEClientSocket(connected = true),
            config = config,
            plan = plan,
            allocateOrderIds = { 700 },
        )
        assertNotNull(submission)
        val adjustable = assertNotNull(submission.adjustableStop)
        assertEquals(5.0, adjustable.adjustedTrailingAmount(), 0.0001)
        assertEquals(105.0, adjustable.triggerPrice(), 0.0001)
        assertEquals(100.0, adjustable.adjustedStopPrice(), 0.0001)
        assertEquals(OrderType.TRAIL, adjustable.adjustedOrderType())
    }

    @Test
    fun buildResize_reusesExistingOrderIds() {
        val orderIds = TouchTurnBracketOrderIds(
            parentOrderId = 700,
            takeProfitOrderId = 701,
            stopLossOrderId = 702,
            adjustableStopOrderId = null,
        )
        val permIds = mapOf(700 to 10L, 701 to 11L, 702 to 12L)
        val submission = IbTouchTurnBracketPlacer.buildResize(
            config = config,
            plan = E2EBracketHelper.liquidityPlan(entry = 100.5, stopLoss = 99.5, takeProfit = 101.5),
            orderIds = orderIds,
            permIdsByOrderId = permIds,
        )
        assertNotNull(submission)
        assertEquals(orderIds.parentOrderId, submission.parentOrderId)
        assertEquals(orderIds.takeProfitOrderId, submission.takeProfitOrderId)
        assertEquals(orderIds.stopLossOrderId, submission.stopLossOrderId)
        assertEquals(10L, submission.parent.permId())
        assertEquals(11L, submission.takeProfit.permId())
        assertEquals(12L, submission.stopLoss.permId())
        assertTrue(submission.parent.transmit())
        assertTrue(submission.takeProfit.transmit())
        assertTrue(submission.stopLoss.transmit())
    }

    @Test
    fun buildResize_missingPermId_returnsNull() {
        val submission = IbTouchTurnBracketPlacer.buildResize(
            config = config,
            plan = E2EBracketHelper.liquidityPlan(),
            orderIds = TouchTurnBracketOrderIds(700, 701, 702, null),
            permIdsByOrderId = emptyMap(),
        )
        assertNull(submission)
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
