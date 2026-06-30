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
        assertEquals("parent_open_order_timeout", harness.failures.single().second)
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
        private val coordinator = IbTouchTurnBracketCoordinator(
            scope = testScope,
            brokerAckTimeoutMs = brokerAckTimeoutMs
        )

        val successes = mutableListOf<IbTouchTurnBracketCoordinator.Pending>()
        val failures = mutableListOf<Pair<IbTouchTurnBracketCoordinator.Pending, String>>()

        fun begin() {
            coordinator.begin(plan, submission) { pending, reason ->
                failures += pending to reason
            }
        }

        fun onBracketTransmitted() {
            coordinator.onBracketTransmitted(submission.parentOrderId) { pending, reason ->
                failures += pending to reason
            }
        }

        fun onOpenOrder(orderId: Int, isWorking: Boolean) {
            coordinator.onOpenOrder(
                orderId = orderId,
                isWorking = isWorking,
                onSuccess = { pending -> successes += pending },
                onFailure = { pending, reason -> failures += pending to reason }
            )
        }

        fun onOrderStatus(orderId: Int, status: String, remaining: Int) {
            coordinator.onOrderStatus(
                orderId = orderId,
                status = status,
                remainingQuantity = remaining,
                onSuccess = { pending -> successes += pending },
                onFailure = { pending, reason -> failures += pending to reason }
            )
        }

        fun onOrderError(orderId: Int, message: String) {
            coordinator.onOrderError(orderId, message) { pending, reason ->
                failures += pending to reason
            }
        }
    }
}
