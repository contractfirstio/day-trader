package daytrader.broker

import daytrader.domain.TouchTurnOrderPlan
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap

/**
 * Tracks Touch Turn IB brackets after all legs are transmitted atomically.
 *
 * IB holds a bracket locally until the final leg is sent with [Order.transmit]=true. Sending only
 * the parent (transmit=false) and waiting for [openOrder] before children causes Gateway to stall
 * ~10s then drop the order ("Clear not needed place orders") — no [openOrder] callback arrives.
 *
 * All legs are placed in one [IbRequestPacer.enqueuePriority] job; this coordinator waits for
 * broker acknowledgment via [openOrder] or [orderStatus].
 *
 * Used for initial bracket placement and in-place bracket resizes (liquidity allocator).
 */
internal class IbTouchTurnBracketCoordinator(
    private val scope: CoroutineScope,
    private val brokerAckTimeoutMs: Long = brokerAckTimeoutMsFromEnv(),
) {
    internal data class Pending(
        val plan: TouchTurnOrderPlan,
        val submission: IbTouchTurnBracketSubmission,
        var bracketTransmitted: Boolean,
        var timeoutJob: Job?,
        val onSuccess: (Pending) -> Unit,
        val onFailure: (Pending, String) -> Unit,
    ) {
        val orderIds: List<Int>
            get() = buildList {
                add(submission.parentOrderId)
                add(submission.takeProfitOrderId)
                add(submission.stopLossOrderId)
                submission.adjustableStopOrderId?.let { add(it) }
            }
    }

    private val pendingByParentId = ConcurrentHashMap<Int, Pending>()
    private val orderIdToParentId = ConcurrentHashMap<Int, Int>()

    fun begin(
        plan: TouchTurnOrderPlan,
        submission: IbTouchTurnBracketSubmission,
        onSuccess: (Pending) -> Unit,
        onFailure: (Pending, String) -> Unit,
    ) {
        val parentId = submission.parentOrderId
        registerOrderIds(submission)
        pendingByParentId[parentId] = Pending(
            plan = plan,
            submission = submission,
            bracketTransmitted = false,
            timeoutJob = null,
            onSuccess = onSuccess,
            onFailure = onFailure,
        )
    }

    /** Starts the broker-ack wait after every bracket leg has been [EClientSocket.placeOrder]'d. */
    fun onBracketTransmitted(parentOrderId: Int) {
        val pending = pendingByParentId[parentOrderId] ?: return
        if (pending.bracketTransmitted) return
        pending.bracketTransmitted = true
        pending.timeoutJob?.cancel()
        pending.timeoutJob = scope.launch {
            delay(brokerAckTimeoutMs)
            fail(parentOrderId, "bracket_ack_timeout")
        }
        IbGatewayLog.touchTurnBracketParentSubmitted(
            symbol = pending.submission.symbol,
            parentOrderId = parentOrderId
        )
        IbGatewayLog.touchTurnBracketChildrenSubmitted(
            symbol = pending.submission.symbol,
            takeProfitOrderId = pending.submission.takeProfitOrderId,
            stopLossOrderId = pending.submission.stopLossOrderId,
            adjustableStopOrderId = pending.submission.adjustableStopOrderId
        )
    }

    fun onOpenOrder(orderId: Int, isWorking: Boolean) {
        val parentId = orderIdToParentId[orderId] ?: return
        val pending = pendingByParentId[parentId] ?: return
        if (!pending.bracketTransmitted) return
        if (!isWorking) {
            if (orderId == parentId) {
                fail(parentId, "parent_order_not_working")
            }
            return
        }
        if (orderId == parentId) return
        complete(parentId)
    }

    fun onOrderStatus(
        orderId: Int,
        status: String,
        remainingQuantity: Int,
    ) {
        if (!isAcknowledgementStatus(status, remainingQuantity)) return
        val parentId = orderIdToParentId[orderId] ?: return
        val pending = pendingByParentId[parentId] ?: return
        if (!pending.bracketTransmitted) return
        if (orderId == parentId) return
        complete(parentId)
    }

    fun onOrderError(orderId: Int, message: String) {
        val parentId = orderIdToParentId[orderId] ?: return
        fail(parentId, "ib_order_error:${message.ifBlank { "unknown" }}")
    }

    fun failPending(parentOrderId: Int, reason: String) {
        fail(parentOrderId, reason)
    }

    fun clearAll() {
        val parentIds = pendingByParentId.keys.toList()
        parentIds.forEach { parentId ->
            fail(parentId, "disconnected")
        }
    }

    private fun complete(parentId: Int) {
        val pending = pendingByParentId.remove(parentId) ?: return
        pending.timeoutJob?.cancel()
        unregisterOrderIds(pending.submission)
        pending.onSuccess(pending)
    }

    private fun fail(parentId: Int, reason: String) {
        val pending = pendingByParentId.remove(parentId) ?: return
        pending.timeoutJob?.cancel()
        unregisterOrderIds(pending.submission)
        IbGatewayLog.touchTurnBracketFailed(pending.submission.symbol, reason)
        pending.onFailure(pending, reason)
    }

    private fun registerOrderIds(submission: IbTouchTurnBracketSubmission) {
        orderIdToParentId[submission.parentOrderId] = submission.parentOrderId
        orderIdToParentId[submission.takeProfitOrderId] = submission.parentOrderId
        orderIdToParentId[submission.stopLossOrderId] = submission.parentOrderId
        submission.adjustableStopOrderId?.let { orderIdToParentId[it] = submission.parentOrderId }
    }

    private fun unregisterOrderIds(submission: IbTouchTurnBracketSubmission) {
        orderIdToParentId.remove(submission.parentOrderId)
        orderIdToParentId.remove(submission.takeProfitOrderId)
        orderIdToParentId.remove(submission.stopLossOrderId)
        submission.adjustableStopOrderId?.let { orderIdToParentId.remove(it) }
    }

    companion object {
        fun isAcknowledgementStatus(status: String, remainingQuantity: Int): Boolean {
            if (isTerminalOrderStatus(status)) return false
            if (remainingQuantity > 0) return true
            return status.equals("Submitted", ignoreCase = true) ||
                status.equals("PreSubmitted", ignoreCase = true) ||
                status.equals("PendingSubmit", ignoreCase = true) ||
                status.equals("ApiPending", ignoreCase = true)
        }

        private fun isTerminalOrderStatus(status: String): Boolean =
            status.equals("Filled", ignoreCase = true) ||
                status.equals("Cancelled", ignoreCase = true) ||
                status.equals("ApiCancelled", ignoreCase = true) ||
                status.equals("Inactive", ignoreCase = true)

        private fun brokerAckTimeoutMsFromEnv(): Long =
            System.getenv("DAY_TRADER_IB_BRACKET_PARENT_TIMEOUT_MS")?.toLongOrNull()?.coerceAtLeast(500L)
                ?: 15_000L
    }
}
