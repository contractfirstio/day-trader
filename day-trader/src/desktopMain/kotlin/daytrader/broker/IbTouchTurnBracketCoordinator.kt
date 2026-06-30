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
        onTimeout: (pending: Pending, reason: String) -> Unit,
    ) {
        val parentId = submission.parentOrderId
        registerOrderIds(submission)
        pendingByParentId[parentId] = Pending(
            plan = plan,
            submission = submission,
            bracketTransmitted = false,
            timeoutJob = null
        )
    }

    /** Starts the broker-ack wait after every bracket leg has been [EClientSocket.placeOrder]'d. */
    fun onBracketTransmitted(
        parentOrderId: Int,
        onTimeout: (pending: Pending, reason: String) -> Unit,
    ) {
        val pending = pendingByParentId[parentOrderId] ?: return
        if (pending.bracketTransmitted) return
        pending.bracketTransmitted = true
        pending.timeoutJob?.cancel()
        pending.timeoutJob = scope.launch {
            delay(brokerAckTimeoutMs)
            fail(parentOrderId, "parent_open_order_timeout", onTimeout)
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

    fun onOpenOrder(
        orderId: Int,
        isWorking: Boolean,
        onSuccess: (Pending) -> Unit,
        onFailure: (pending: Pending, reason: String) -> Unit,
    ) {
        val parentId = orderIdToParentId[orderId] ?: return
        val pending = pendingByParentId[parentId] ?: return
        if (!pending.bracketTransmitted) return
        if (!isWorking) {
            if (orderId == parentId) {
                fail(parentId, "parent_order_not_working", onFailure)
            }
            return
        }
        if (orderId == parentId) return
        complete(parentId, onSuccess)
    }

    fun onOrderStatus(
        orderId: Int,
        status: String,
        remainingQuantity: Int,
        onSuccess: (Pending) -> Unit,
        onFailure: (pending: Pending, reason: String) -> Unit,
    ) {
        if (!isAcknowledgementStatus(status, remainingQuantity)) return
        val parentId = orderIdToParentId[orderId] ?: return
        val pending = pendingByParentId[parentId] ?: return
        if (!pending.bracketTransmitted) return
        if (orderId == parentId) return
        complete(parentId, onSuccess)
    }

    fun onOrderError(
        orderId: Int,
        message: String,
        onFailure: (pending: Pending, reason: String) -> Unit,
    ) {
        val parentId = orderIdToParentId[orderId] ?: return
        fail(parentId, "ib_order_error:${message.ifBlank { "unknown" }}", onFailure)
    }

    fun failPending(
        parentOrderId: Int,
        reason: String,
        onFailure: (pending: Pending, reason: String) -> Unit,
    ) {
        fail(parentOrderId, reason, onFailure)
    }

    fun clearAll(onFailure: ((pending: Pending, reason: String) -> Unit)? = null) {
        val parentIds = pendingByParentId.keys.toList()
        parentIds.forEach { parentId ->
            if (onFailure != null) {
                fail(parentId, "disconnected", onFailure)
            } else {
                remove(parentId)
            }
        }
    }

    private fun complete(parentId: Int, onSuccess: (Pending) -> Unit) {
        val pending = pendingByParentId.remove(parentId) ?: return
        pending.timeoutJob?.cancel()
        unregisterOrderIds(pending.submission)
        onSuccess(pending)
    }

    private fun fail(
        parentId: Int,
        reason: String,
        onFailure: (pending: Pending, reason: String) -> Unit,
    ) {
        val pending = pendingByParentId.remove(parentId) ?: return
        pending.timeoutJob?.cancel()
        unregisterOrderIds(pending.submission)
        IbGatewayLog.touchTurnBracketFailed(pending.submission.symbol, reason)
        onFailure(pending, reason)
    }

    private fun remove(parentId: Int) {
        pendingByParentId.remove(parentId)?.let { pending ->
            pending.timeoutJob?.cancel()
            unregisterOrderIds(pending.submission)
        }
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
