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
    fun parentOpenOrder_sendsChildren_thenChildOpenOrder_emitsSuccess() = runTest {
        val harness = Harness(this)
        harness.begin()

        harness.onOpenOrder(submission.parentOrderId, isWorking = true)

        assertTrue(harness.childrenSent)
        assertFalse(harness.successes.isNotEmpty())

        harness.onOpenOrder(submission.takeProfitOrderId, isWorking = true)

        assertEquals(1, harness.successes.size)
        assertEquals(submission.parentOrderId, harness.successes.single().submission.parentOrderId)
    }

    @Test
    fun parentOpenOrderTimeout_emitsFailure() = runTest {
        val harness = Harness(this, parentOpenTimeoutMs = 1_000L)
        harness.begin()

        advanceTimeBy(1_001)

        assertEquals(1, harness.failures.size)
        assertEquals("parent_open_order_timeout", harness.failures.single().second)
    }

    @Test
    fun orderErrorDuringParentWait_emitsFailure() = runTest {
        val harness = Harness(this)
        harness.begin()

        harness.onOrderError(submission.takeProfitOrderId, "Can't find order")

        assertEquals(1, harness.failures.size)
        assertTrue(harness.failures.single().second.contains("ib_order_error"))
    }

    private val submission = IbTouchTurnBracketSubmission(
        symbol = "7709",
        contract = Contract().also { it.symbol("7709") },
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
        symbol = "7709",
        currencyCode = "HKD",
        side = TouchTurnTradeSide.LONG,
        quantity = 100,
        orders = listOf(
            TouchTurnPlannedOrder(
                role = TouchTurnOrderRole.ENTRY,
                action = "BUY",
                orderType = "LMT",
                price = 100.0,
                quantity = 100
            ),
            TouchTurnPlannedOrder(
                role = TouchTurnOrderRole.TAKE_PROFIT,
                action = "SELL",
                orderType = "LMT",
                price = 110.0,
                quantity = 100
            ),
            TouchTurnPlannedOrder(
                role = TouchTurnOrderRole.STOP_LOSS,
                action = "SELL",
                orderType = "STP",
                price = 90.0,
                quantity = 100
            )
        )
    )

    private inner class Harness(
        private val testScope: TestScope,
        parentOpenTimeoutMs: Long = 5_000L,
        confirmTimeoutMs: Long = 3_000L,
    ) {
        private val coordinator = IbTouchTurnBracketCoordinator(
            scope = testScope,
            parentOpenTimeoutMs = parentOpenTimeoutMs,
            confirmTimeoutMs = confirmTimeoutMs
        )

        var childrenSent = false
        val successes = mutableListOf<IbTouchTurnBracketCoordinator.Pending>()
        val failures = mutableListOf<Pair<IbTouchTurnBracketCoordinator.Pending, String>>()
        private val workingOrderIds = mutableSetOf<Int>()

        fun begin() {
            coordinator.begin(plan, submission) { pending, reason ->
                failures += pending to reason
            }
        }

        fun onOpenOrder(orderId: Int, isWorking: Boolean) {
            if (isWorking) {
                workingOrderIds += orderId
            } else {
                workingOrderIds -= orderId
            }
            coordinator.onOpenOrder(
                orderId = orderId,
                isWorking = isWorking,
                sendChildren = {
                    childrenSent = true
                },
                hasWorkingOrder = { id -> workingOrderIds.contains(id) },
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
