package daytrader.data

import daytrader.broker.SymbolMarkets
import daytrader.gateway.AccountPosition
import daytrader.gateway.BrokerGateway
import daytrader.gateway.WorkingOrder
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow

/**
 * OPEN_DEADLINE exit: market-close the position, confirm flat, then cancel remaining orders.
 * If the close cannot be confirmed, non–stop-loss orders are cancelled but protective stops stay working.
 * When the broker drops protective stops during the first close attempt (IB bracket behaviour),
 * a recovery [BrokerGateway.flattenSymbolForSymbol] is attempted before giving up.
 */
object OpenDeadlineSessionExit {
    const val CONFIRM_TIMEOUT_MS = 30_000L
    const val RECOVERY_CONFIRM_TIMEOUT_MS = 30_000L
    const val POLL_INTERVAL_MS = 250L

    sealed interface Result {
        data object NoOpenPosition : Result
        data object PositionConfirmedFlat : Result
        /** Flat confirmed after [flattenSymbolForSymbol] when the first close left a naked position. */
        data object PositionConfirmedFlatAfterRecovery : Result
        data class CloseUnconfirmedStopLossRetained(
            val reason: String,
            val stopLossOrderCount: Int,
            val recoveryFlattenAttempted: Boolean = false
        ) : Result
    }

    suspend fun execute(
        gateway: BrokerGateway,
        symbol: String,
        knownPosition: AccountPosition,
        positions: StateFlow<List<AccountPosition>>,
        openOrders: StateFlow<List<WorkingOrder>>,
        confirmTimeoutMs: Long = CONFIRM_TIMEOUT_MS,
        recoveryConfirmTimeoutMs: Long = RECOVERY_CONFIRM_TIMEOUT_MS,
        pollIntervalMs: Long = POLL_INTERVAL_MS
    ): Result {
        gateway.cancelOpenOrdersForSymbol(symbol, preserveStopLoss = true)
        gateway.closeOpenPositionForSymbol(symbol, knownPosition, purpose = "open_deadline")
        refreshBrokerSnapshot(gateway)

        if (awaitPositionFlat(symbol, positions, confirmTimeoutMs, pollIntervalMs, gateway)) {
            gateway.cancelOpenOrdersForSymbol(symbol, preserveStopLoss = false)
            return Result.PositionConfirmedFlat
        }

        val stopLossCountAfterFirstClose = protectiveStopLossCount(symbol, openOrders.value)
        if (stopLossCountAfterFirstClose == 0) {
            gateway.flattenSymbolForSymbol(symbol)
            refreshBrokerSnapshot(gateway)
            if (awaitPositionFlat(symbol, positions, recoveryConfirmTimeoutMs, pollIntervalMs, gateway)) {
                return Result.PositionConfirmedFlatAfterRecovery
            }
        }

        return Result.CloseUnconfirmedStopLossRetained(
            reason = "position_still_open_after_${confirmTimeoutMs}ms",
            stopLossOrderCount = protectiveStopLossCount(symbol, openOrders.value),
            recoveryFlattenAttempted = stopLossCountAfterFirstClose == 0
        )
    }

    internal suspend fun awaitPositionFlat(
        symbol: String,
        positions: StateFlow<List<AccountPosition>>,
        timeoutMs: Long,
        pollIntervalMs: Long,
        gateway: BrokerGateway? = null,
        onPoll: () -> Unit = {}
    ): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (!SymbolMarkets.hasOpenPosition(symbol, positions.value)) return true
            if (gateway != null) {
                refreshBrokerSnapshot(gateway)
            } else {
                onPoll()
            }
            delay(pollIntervalMs)
        }
        return !SymbolMarkets.hasOpenPosition(symbol, positions.value)
    }

    private fun refreshBrokerSnapshot(gateway: BrokerGateway) {
        gateway.refreshFills()
        gateway.refreshPositions()
    }

    private fun protectiveStopLossCount(symbol: String, openOrders: List<WorkingOrder>): Int =
        SymbolMarkets.openOrdersForSymbol(symbol, openOrders)
            .count { SessionOrderClassification.isProtectiveStopLoss(it) }
}
