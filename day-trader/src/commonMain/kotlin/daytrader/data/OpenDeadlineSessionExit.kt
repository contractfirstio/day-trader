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
 */
object OpenDeadlineSessionExit {
    const val CONFIRM_TIMEOUT_MS = 30_000L
    const val POLL_INTERVAL_MS = 250L

    sealed interface Result {
        data object NoOpenPosition : Result
        data object PositionConfirmedFlat : Result
        data class CloseUnconfirmedStopLossRetained(
            val reason: String,
            val stopLossOrderCount: Int
        ) : Result
    }

    suspend fun execute(
        gateway: BrokerGateway,
        symbol: String,
        knownPosition: AccountPosition,
        positions: StateFlow<List<AccountPosition>>,
        openOrders: StateFlow<List<WorkingOrder>>,
        confirmTimeoutMs: Long = CONFIRM_TIMEOUT_MS,
        pollIntervalMs: Long = POLL_INTERVAL_MS
    ): Result {
        gateway.cancelOpenOrdersForSymbol(symbol, preserveStopLoss = true)
        gateway.closeOpenPositionForSymbol(symbol, knownPosition)
        gateway.refreshFills()
        gateway.refreshPositions()

        val flat = awaitPositionFlat(
            symbol = symbol,
            positions = positions,
            timeoutMs = confirmTimeoutMs,
            pollIntervalMs = pollIntervalMs,
            onPoll = {
                gateway.refreshFills()
            }
        )
        return if (flat) {
            gateway.cancelOpenOrdersForSymbol(symbol, preserveStopLoss = false)
            Result.PositionConfirmedFlat
        } else {
            val stopLossCount = SymbolMarkets.openOrdersForSymbol(symbol, openOrders.value)
                .count { SessionOrderClassification.isProtectiveStopLoss(it) }
            Result.CloseUnconfirmedStopLossRetained(
                reason = "position_still_open_after_${confirmTimeoutMs}ms",
                stopLossOrderCount = stopLossCount
            )
        }
    }

    internal suspend fun awaitPositionFlat(
        symbol: String,
        positions: StateFlow<List<AccountPosition>>,
        timeoutMs: Long,
        pollIntervalMs: Long,
        onPoll: () -> Unit = {}
    ): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (!SymbolMarkets.hasOpenPosition(symbol, positions.value)) return true
            onPoll()
            delay(pollIntervalMs)
        }
        return !SymbolMarkets.hasOpenPosition(symbol, positions.value)
    }
}
