package daytrader.broker

import com.ib.client.Contract
import com.ib.client.Decimal
import com.ib.client.Order
import com.ib.client.Types
import daytrader.domain.TouchTurnBracketOrderIds
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class IbOrderModifySupportTest {
    @Test
    fun buildResizeSubmission_updatesQuantityAndPreservesPrices() {
        val orderIds = TouchTurnBracketOrderIds(500, 501, 502, null)
        val templates = mapOf(
            500 to entryTemplate(orderIds.parentOrderId, quantity = 100, price = 50.0),
            501 to childTemplate(orderIds.takeProfitOrderId, orderIds.parentOrderId, quantity = 100, price = 51.0, type = "LMT"),
            502 to childTemplate(orderIds.stopLossOrderId, orderIds.parentOrderId, quantity = 100, price = 49.0, type = "STP"),
        )
        val submission = IbOrderModifySupport.buildResizeSubmission(
            symbol = "1211",
            contract = Contract().also {
                it.symbol("1211")
                it.secType("STK")
                it.exchange("SEHK")
                it.currency("HKD")
            },
            orderIds = orderIds,
            templatesByOrderId = templates,
            targetQuantity = 200,
        )
        assertNotNull(submission)
        assertEquals(200L, submission.parent.totalQuantity().longValue())
        assertEquals(200L, submission.takeProfit.totalQuantity().longValue())
        assertEquals(200L, submission.stopLoss.totalQuantity().longValue())
        assertEquals(50.0, submission.parent.lmtPrice(), 0.0001)
        assertEquals(51.0, submission.takeProfit.lmtPrice(), 0.0001)
        assertEquals(49.0, submission.stopLoss.auxPrice(), 0.0001)
        assertTrue(submission.parent.transmit())
        assertTrue(submission.takeProfit.transmit())
        assertTrue(submission.stopLoss.transmit())
    }

    private fun entryTemplate(orderId: Int, quantity: Int, price: Double): Order =
        Order().also {
            it.orderId(orderId)
            it.permId(1_000L + orderId)
            it.clientId(7)
            it.action("BUY")
            it.orderType("LMT")
            it.totalQuantity(Decimal.get(quantity.toLong()))
            it.lmtPrice(price)
            it.tif(Types.TimeInForce.DAY)
            it.transmit(false)
        }

    private fun childTemplate(
        orderId: Int,
        parentId: Int,
        quantity: Int,
        price: Double,
        type: String,
    ): Order = Order().also {
        it.orderId(orderId)
        it.permId(1_000L + orderId)
        it.clientId(7)
        it.action("SELL")
        it.orderType(type)
        it.totalQuantity(Decimal.get(quantity.toLong()))
        if (type == "LMT") it.lmtPrice(price) else it.auxPrice(price)
        it.parentId(parentId)
        it.tif(Types.TimeInForce.GTC)
        it.transmit(false)
    }
}
