package daytrader.broker

import com.ib.client.Contract
import com.ib.client.Order
import daytrader.domain.TouchTurnOrderPlan
import daytrader.domain.TouchTurnOrderRole
import daytrader.domain.TouchTurnPlannedOrder
import daytrader.domain.TouchTurnTradeSide
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class IbTouchTurnBracketCoordinatorTest {

    @Test
    fun childOpenOrder_afterAtomicTransmit_emitsSuccess() = runTest {
        val harness = Harness(this)
        harness.begin()
        harness.onBracketTransmitted()

        harness.onOpenOrder(submission.parentOrderId, isWorking = true)
        assertFalse(harness.successes.isNotEmpty())

        harness.onOpenOrder(submission.takeProfitOrderId, isWorking = true)

        assertEquals(1, harness.successes.size)
        assertEquals(submission.parentOrderId, harness.successes.single().submission.parentOrderId)
    }

    @Test
    fun brokerAckTimeout_startsAfterBracketTransmitted() = runTest {
        val harness = Harness(this, brokerAckTimeoutMs = 1_000L)
        harness.begin()

        advanceTimeBy(2_000)
        assertTrue(harness.failures.isEmpty())

        harness.onBracketTransmitted()
        advanceTimeBy(1_001)

        assertEquals(1, harness.failures.size)
        assertEquals("bracket_ack_timeout", harness.failures.single().second)
    }

    @Test
    fun modifyTransmitted_completesAfterDeferredAckWithoutCallbacks() = runTest {
        val harness = Harness(this, brokerAckTimeoutMs = 5_000L)
        harness.begin(ackMode = IbTouchTurnBracketCoordinator.AckMode.RESIZE, quantity = 200)
        harness.coordinator.onModifyTransmitted(submission.parentOrderId)

        assertTrue(harness.successes.isEmpty())
        advanceTimeBy(1_501)
        assertEquals(1, harness.successes.size)
    }

    @Test
    fun verifyOpenOrders_completesWhenEntryLegMatchesTarget() = runTest {
        val harness = Harness(this)
        harness.begin(ackMode = IbTouchTurnBracketCoordinator.AckMode.RESIZE, quantity = 200)
        harness.onBracketTransmitted()

        harness.coordinator.verifyOpenOrders(
            mapOf(
                submission.parentOrderId to workingOrder(submission.parentOrderId, quantity = 200),
                submission.takeProfitOrderId to workingOrder(submission.takeProfitOrderId, quantity = 100),
            )
        )

        assertEquals(1, harness.successes.size)
    }

    private fun workingOrder(orderId: Int, quantity: Int) = daytrader.gateway.WorkingOrder(
        orderId = orderId,
        symbol = "1211",
        action = "BUY",
        quantity = quantity,
        filled = 0,
        remaining = quantity,
        orderType = "LMT",
        limitPrice = 50.0,
        stopPrice = null,
        status = "Submitted",
        currency = "HKD",
    )

    @Test
    fun resizeParentOpenOrder_atTargetQuantity_emitsSuccess() = runTest {
        val harness = Harness(this)
        harness.begin(ackMode = IbTouchTurnBracketCoordinator.AckMode.RESIZE, quantity = 200)
        harness.onBracketTransmitted()

        harness.onOpenOrder(
            orderId = submission.parentOrderId,
            isWorking = true,
            totalQuantity = 200,
            remainingQuantity = 200,
        )

        assertEquals(1, harness.successes.size)
    }

    @Test
    fun resizeChildOrderStatus_ignoresStaleQuantityUntilTarget() = runTest {
        val harness = Harness(this)
        harness.begin(ackMode = IbTouchTurnBracketCoordinator.AckMode.RESIZE, quantity = 200)
        harness.onBracketTransmitted()

        harness.onOrderStatus(submission.stopLossOrderId, status = "Submitted", remaining = 100)
        assertTrue(harness.successes.isEmpty())

        harness.onOrderStatus(submission.stopLossOrderId, status = "Submitted", remaining = 200)
        assertEquals(1, harness.successes.size)
    }

    @Test
    fun childOrderStatusSubmitted_emitsSuccess() = runTest {
        val harness = Harness(this)
        harness.begin()
        harness.onBracketTransmitted()

        harness.onOrderStatus(submission.stopLossOrderId, status = "Submitted", remaining = 100)

        assertEquals(1, harness.successes.size)
    }

    @Test
    fun orderErrorDuringWait_emitsFailure() = runTest {
        val harness = Harness(this)
        harness.begin()
        harness.onBracketTransmitted()

        harness.onOrderError(submission.takeProfitOrderId, "Can't find order")

        assertEquals(1, harness.failures.size)
        assertTrue(harness.failures.single().second.contains("ib_order_error"))
    }

    @Test
    fun openOrderIgnoredBeforeBracketTransmitted() = runTest {
        val harness = Harness(this)
        harness.begin()

        harness.onOpenOrder(submission.takeProfitOrderId, isWorking = true)

        assertTrue(harness.successes.isEmpty())
    }

    private val submission = IbTouchTurnBracketSubmission(
        symbol = "939",
        contract = Contract().also { it.symbol("939") },
        parentOrderId = 83,
        takeProfitOrderId = 84,
        stopLossOrderId = 85,
        adjustableStopOrderId = null,
        parent = Order().also { it.orderId(83) },
        takeProfit = Order().also { it.orderId(84) },
        stopLoss = Order().also { it.orderId(85) },
        adjustableStop = null
    )

    private val plan = TouchTurnOrderPlan(
        symbol = "939",
        currencyCode = "HKD",
        side = TouchTurnTradeSide.LONG,
        quantity = 100,
        orders = listOf(
            TouchTurnPlannedOrder(
                role = TouchTurnOrderRole.ENTRY,
                action = "BUY",
                orderType = "STP",
                price = 8.14,
                quantity = 100
            ),
            TouchTurnPlannedOrder(
                role = TouchTurnOrderRole.TAKE_PROFIT,
                action = "SELL",
                orderType = "LMT",
                price = 8.20,
                quantity = 100
            ),
            TouchTurnPlannedOrder(
                role = TouchTurnOrderRole.STOP_LOSS,
                action = "SELL",
                orderType = "STP",
                price = 8.10,
                quantity = 100
            )
        )
    )

    private inner class Harness(
        private val testScope: TestScope,
        brokerAckTimeoutMs: Long = 5_000L,
    ) {
        val coordinator = IbTouchTurnBracketCoordinator(
            scope = testScope,
            brokerAckTimeoutMs = brokerAckTimeoutMs
        )

        val successes = mutableListOf<IbTouchTurnBracketCoordinator.Pending>()
        val failures = mutableListOf<Pair<IbTouchTurnBracketCoordinator.Pending, String>>()

        fun begin(
            ackMode: IbTouchTurnBracketCoordinator.AckMode = IbTouchTurnBracketCoordinator.AckMode.PLACEMENT,
            quantity: Int = plan.quantity,
        ) {
            val resizePlan = plan.copy(
                quantity = quantity,
                orders = plan.orders.map { it.copy(quantity = quantity) },
            )
            coordinator.begin(
                plan = resizePlan,
                submission = submission,
                ackMode = ackMode,
                onSuccess = { pending -> successes += pending },
                onFailure = { pending, reason -> failures += pending to reason },
            )
        }

        fun onBracketTransmitted() {
            coordinator.onBracketTransmitted(submission.parentOrderId)
        }

        fun onModifyTransmitted() {
            coordinator.onModifyTransmitted(submission.parentOrderId)
        }

        fun onOpenOrder(
            orderId: Int,
            isWorking: Boolean,
            totalQuantity: Int = 0,
            remainingQuantity: Int = 0,
        ) {
            coordinator.onOpenOrder(
                orderId = orderId,
                isWorking = isWorking,
                totalQuantity = totalQuantity,
                remainingQuantity = remainingQuantity,
            )
        }

        fun onOrderStatus(orderId: Int, status: String, remaining: Int, total: Int = remaining) {
            coordinator.onOrderStatus(
                orderId = orderId,
                status = status,
                remainingQuantity = remaining,
                totalQuantity = total,
            )
        }

        fun onOrderError(orderId: Int, message: String) {
            coordinator.onOrderError(orderId, message)
        }
    }
}
