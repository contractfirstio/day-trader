package daytrader.diagnostics

import daytrader.gateway.BrokerId
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ExecutionGatewayLogTest {
    private val captured = mutableListOf<Triple<String, String?, Map<String, String>>>()

    @AfterTest
    fun tearDown() {
        ExecutionGatewayLog.testListener = null
        captured.clear()
    }

    @Test
    fun sessionPositionClosePlaced_emitsGatewayEvent() {
        ExecutionGatewayLog.testListener = { type, symbol, details ->
            captured += Triple(type, symbol, details)
        }

        ExecutionGatewayLog.sessionPositionClosePlaced(
            brokerId = BrokerId.INTERACTIVE_BROKERS,
            symbol = "AAPL",
            orderId = 648,
            action = "BUY",
            quantity = 16,
            purpose = "open_deadline"
        )

        assertEquals(1, captured.size)
        assertEquals("session_position_close_placed", captured.single().first)
        assertEquals("AAPL", captured.single().second)
        assertEquals(
            mapOf(
                "orderId" to "648",
                "action" to "BUY",
                "quantity" to "16",
                "orderType" to "MKT",
                "purpose" to "open_deadline"
            ),
            captured.single().third
        )
    }

    @Test
    fun sessionPositionCloseRejected_emitsGatewayEvent() {
        ExecutionGatewayLog.testListener = { type, symbol, details ->
            captured += Triple(type, symbol, details)
        }

        ExecutionGatewayLog.sessionPositionCloseRejected(
            brokerId = BrokerId.INTERACTIVE_BROKERS,
            symbol = "AAPL",
            orderId = 648,
            purpose = "open_deadline",
            status = "Inactive",
            errorCode = 201,
            errorMessage = "Order rejected"
        )

        assertEquals("session_position_close_rejected", captured.single().first)
        assertTrue(captured.single().third["status"] == "Inactive")
        assertTrue(captured.single().third["errorCode"] == "201")
    }

    @Test
    fun sessionPositionCloseSkipped_emitsGatewayEvent() {
        ExecutionGatewayLog.testListener = { type, symbol, details ->
            captured += Triple(type, symbol, details)
        }

        ExecutionGatewayLog.sessionPositionCloseSkipped(
            brokerId = BrokerId.INTERACTIVE_BROKERS,
            symbol = "AAPL",
            reason = "Order id not ready",
            purpose = "open_deadline"
        )

        assertEquals("session_position_close_skipped", captured.single().first)
        assertEquals("Order id not ready", captured.single().third["reason"])
    }

    @Test
    fun sessionPositionCloseFilled_emitsGatewayEvent() {
        ExecutionGatewayLog.testListener = { type, symbol, details ->
            captured += Triple(type, symbol, details)
        }

        ExecutionGatewayLog.sessionPositionCloseFilled(
            brokerId = BrokerId.INTERACTIVE_BROKERS,
            symbol = "AAPL",
            orderId = 648,
            purpose = "flatten",
            filledQuantity = 16,
            avgFillPrice = 312.15
        )

        assertEquals("session_position_close_filled", captured.single().first)
        assertEquals("flatten", captured.single().third["purpose"])
    }

    @Test
    fun touchTurnBracketResized_emitsGatewayEvent() {
        ExecutionGatewayLog.testListener = { type, symbol, details ->
            captured += Triple(type, symbol, details)
        }

        ExecutionGatewayLog.touchTurnBracketResized(
            brokerId = BrokerId.INTERACTIVE_BROKERS,
            symbol = "AMZN",
            quantity = 21,
            parentOrderId = 782,
            success = true,
        )

        assertEquals("touch_turn_bracket_resized", captured.single().first)
        assertEquals("AMZN", captured.single().second)
        assertEquals("21", captured.single().third["quantity"])
        assertEquals("true", captured.single().third["success"])
    }
}
