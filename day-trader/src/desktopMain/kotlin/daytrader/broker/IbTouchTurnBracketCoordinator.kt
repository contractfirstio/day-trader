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
    enum class AckMode {
        /** New bracket — child [openOrder] or [orderStatus] is enough. */
        PLACEMENT,
        /** Modify existing legs — any leg [openOrder]/[orderStatus] at [Pending.plan] quantity. */
        RESIZE,
    }

    internal data class Pending(
        val plan: TouchTurnOrderPlan,
        val submission: IbTouchTurnBracketSubmission,
        val ackMode: AckMode,
        var bracketTransmitted: Boolean,
        var timeoutJob: Job?,
        var modifyAckJob: Job?,
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
        ackMode: AckMode = AckMode.PLACEMENT,
        onSuccess: (Pending) -> Unit,
        onFailure: (Pending, String) -> Unit,
    ) {
        val parentId = submission.parentOrderId
        registerOrderIds(submission)
        pendingByParentId[parentId] = Pending(
            plan = plan,
            submission = submission,
            ackMode = ackMode,
            bracketTransmitted = false,
            timeoutJob = null,
            modifyAckJob = null,
            onSuccess = onSuccess,
            onFailure = onFailure,
        )
    }

    /** After modify [placeOrder] calls — IB often acks via openOrder, not orderStatus. */
    fun onModifyTransmitted(parentOrderId: Int) {
        onBracketTransmitted(parentOrderId)
        val pending = pendingByParentId[parentOrderId] ?: return
        if (pending.ackMode != AckMode.RESIZE) return
        pending.modifyAckJob?.cancel()
        pending.modifyAckJob = scope.launch {
            delay(modifyTransmitAckMsFromEnv())
            if (pendingByParentId.containsKey(parentOrderId)) {
                complete(parentOrderId)
            }
        }
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

    fun onOpenOrder(
        orderId: Int,
        isWorking: Boolean,
        totalQuantity: Int = 0,
        remainingQuantity: Int = 0,
    ) {
        val parentId = orderIdToParentId[orderId] ?: return
        val pending = pendingByParentId[parentId] ?: return
        if (!pending.bracketTransmitted) return
        if (pending.ackMode == AckMode.RESIZE) {
            if (!isWorking) {
                if (orderId == parentId) {
                    fail(parentId, "parent_order_not_working")
                }
                return
            }
            if (reportsTargetQuantity(totalQuantity, remainingQuantity, pending.plan.quantity)) {
                complete(parentId)
            }
            return
        }
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
        totalQuantity: Int = remainingQuantity,
    ) {
        if (!isAcknowledgementStatus(status, remainingQuantity)) return
        val parentId = orderIdToParentId[orderId] ?: return
        val pending = pendingByParentId[parentId] ?: return
        if (!pending.bracketTransmitted) return
        when (pending.ackMode) {
            AckMode.PLACEMENT -> {
                if (orderId == parentId) return
                complete(parentId)
            }
            AckMode.RESIZE -> {
                if (!reportsTargetQuantity(totalQuantity, remainingQuantity, pending.plan.quantity)) return
                complete(parentId)
            }
        }
    }

    fun onOrderError(orderId: Int, message: String) {
        val parentId = orderIdToParentId[orderId] ?: return
        pendingByParentId[parentId]?.modifyAckJob?.cancel()
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

    fun verifyOpenOrders(openOrdersById: Map<Int, daytrader.gateway.WorkingOrder>) {
        pendingByParentId.keys.toList().forEach { parentId ->
            val pending = pendingByParentId[parentId] ?: return@forEach
            if (pending.ackMode != AckMode.RESIZE || !pending.bracketTransmitted) return@forEach
            if (entryLegAtTargetQuantity(pending, openOrdersById)) {
                complete(parentId)
            }
        }
    }

    fun hasPending(parentOrderId: Int): Boolean = pendingByParentId.containsKey(parentOrderId)

    private fun entryLegAtTargetQuantity(
        pending: Pending,
        openOrdersById: Map<Int, daytrader.gateway.WorkingOrder>,
    ): Boolean {
        val target = pending.plan.quantity
        val entry = openOrdersById[pending.submission.parentOrderId] ?: return false
        return entry.remaining == target || (entry.filled == 0 && entry.quantity == target)
    }

    private fun complete(parentId: Int) {
        val pending = pendingByParentId.remove(parentId) ?: return
        pending.timeoutJob?.cancel()
        pending.modifyAckJob?.cancel()
        unregisterOrderIds(pending.submission)
        pending.onSuccess(pending)
    }

    private fun fail(parentId: Int, reason: String) {
        val pending = pendingByParentId.remove(parentId) ?: return
        pending.timeoutJob?.cancel()
        pending.modifyAckJob?.cancel()
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
        fun reportsTargetQuantity(totalQuantity: Int, remainingQuantity: Int, targetQuantity: Int): Boolean =
            remainingQuantity == targetQuantity || totalQuantity == targetQuantity

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

        private fun modifyTransmitAckMsFromEnv(): Long =
            System.getenv("DAY_TRADER_IB_BRACKET_MODIFY_ACK_MS")?.toLongOrNull()?.coerceAtLeast(250L)
                ?: 1_500L
    }
}
