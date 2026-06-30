package daytrader.broker

import daytrader.domain.TouchTurnOrderPlan
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap

/**
 * Places Touch Turn IB brackets in two phases so child legs never arrive before the parent
 * is registered at the gateway:
 * 1. Send parent only; wait for [onOpenOrder] on the parent id.
 * 2. Send remaining legs atomically (single pacer job, no interleaving).
 * 3. Ack success only after a working child [onOpenOrder] confirms the bracket is live.
 */
internal class IbTouchTurnBracketCoordinator(
    private val scope: CoroutineScope,
    private val parentOpenTimeoutMs: Long = parentOpenTimeoutMsFromEnv(),
    private val confirmTimeoutMs: Long = confirmTimeoutMsFromEnv(),
) {
    internal enum class Phase {
        AWAITING_PARENT,
        AWAITING_CONFIRM,
    }

    internal data class Pending(
        val plan: TouchTurnOrderPlan,
        val submission: IbTouchTurnBracketSubmission,
        var phase: Phase,
        var childrenSent: Boolean,
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
        val timeoutJob = scope.launch {
            delay(parentOpenTimeoutMs)
            fail(parentId, "parent_open_order_timeout", onTimeout)
        }
        pendingByParentId[parentId] = Pending(
            plan = plan,
            submission = submission,
            phase = Phase.AWAITING_PARENT,
            childrenSent = false,
            timeoutJob = timeoutJob
        )
        IbGatewayLog.touchTurnBracketParentSubmitted(
            symbol = submission.symbol,
            parentOrderId = parentId
        )
    }

    fun onOpenOrder(
        orderId: Int,
        isWorking: Boolean,
        sendChildren: (IbTouchTurnBracketSubmission) -> Unit,
        hasWorkingOrder: (Int) -> Boolean,
        onSuccess: (Pending) -> Unit,
        onFailure: (pending: Pending, reason: String) -> Unit,
    ) {
        val parentId = orderIdToParentId[orderId] ?: return
        val pending = pendingByParentId[parentId] ?: return
        when (pending.phase) {
            Phase.AWAITING_PARENT -> {
                if (orderId != parentId) return
                if (!isWorking) {
                    fail(parentId, "parent_order_not_working", onFailure)
                    return
                }
                pending.timeoutJob?.cancel()
                pending.childrenSent = true
                sendChildren(pending.submission)
                pending.phase = Phase.AWAITING_CONFIRM
                pending.timeoutJob = scope.launch {
                    delay(confirmTimeoutMs)
                    fail(parentId, "broker_confirm_timeout", onFailure)
                }
            }
            Phase.AWAITING_CONFIRM -> {
                if (!pending.childrenSent || !isWorking) return
                if (!hasWorkingOrder(pending.submission.parentOrderId)) return
                if (orderId == pending.submission.parentOrderId) return
                complete(parentId, onSuccess)
            }
        }
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
        private fun parentOpenTimeoutMsFromEnv(): Long =
            System.getenv("DAY_TRADER_IB_BRACKET_PARENT_TIMEOUT_MS")?.toLongOrNull()?.coerceAtLeast(500L)
                ?: 5_000L

        private fun confirmTimeoutMsFromEnv(): Long =
            System.getenv("DAY_TRADER_IB_BRACKET_CONFIRM_TIMEOUT_MS")?.toLongOrNull()?.coerceAtLeast(500L)
                ?: 3_000L
    }
}
