package daytrader.data

import daytrader.broker.SymbolMarkets
import daytrader.domain.InstrumentIdentity
import daytrader.domain.OpenDeadlineTightStopPrice
import daytrader.gateway.AccountPosition
import daytrader.gateway.BrokerGateway
import daytrader.gateway.LiveQuote
import daytrader.gateway.WorkingOrder
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow

/**
 * OPEN_DEADLINE exit: cancel take-profit (keep stop), tighten the protective stop near market,
 * confirm flat, then cancel remaining orders. Protective stops are never removed while the
 * position is open. Market close is a last-resort fallback only.
 */
object OpenDeadlineSessionExit {
    const val CONFIRM_TIMEOUT_MS = 30_000L
    const val MARKET_FALLBACK_CONFIRM_TIMEOUT_MS = 30_000L
    const val POLL_INTERVAL_MS = 250L

    sealed interface Result {
        data object NoOpenPosition : Result
        data object PositionConfirmedFlat : Result
        /** Flat confirmed after a last-resort market close when the tightened stop did not fill in time. */
        data object PositionConfirmedFlatAfterMarketFallback : Result
        data class CloseUnconfirmedStopLossRetained(
            val reason: String,
            val stopLossOrderCount: Int,
            val marketFallbackAttempted: Boolean = false
        ) : Result
    }

    suspend fun execute(
        gateway: BrokerGateway,
        symbol: String,
        knownPosition: AccountPosition,
        positions: StateFlow<List<AccountPosition>>,
        openOrders: StateFlow<List<WorkingOrder>>,
        quote: LiveQuote? = null,
        instrument: InstrumentIdentity? = null,
        confirmTimeoutMs: Long = CONFIRM_TIMEOUT_MS,
        marketFallbackConfirmTimeoutMs: Long = MARKET_FALLBACK_CONFIRM_TIMEOUT_MS,
        pollIntervalMs: Long = POLL_INTERVAL_MS
    ): Result {
        gateway.cancelOpenOrdersForSymbol(symbol, preserveStopLoss = true)

        val targetStop = OpenDeadlineTightStopPrice.compute(
            position = knownPosition,
            quote = quote,
            instrument = instrument,
            symbol = symbol
        )
        if (targetStop != null) {
            gateway.tightenOpenDeadlineProtectiveStop(
                symbol = symbol,
                position = knownPosition,
                newStopPrice = targetStop
            )
        }
        refreshBrokerSnapshot(gateway)

        if (awaitPositionFlat(symbol, positions, confirmTimeoutMs, pollIntervalMs, gateway)) {
            cancelRemainingOrdersWhenFlat(gateway, symbol, positions.value)
            return Result.PositionConfirmedFlat
        }

        val stopMissing = protectiveStopLossCount(symbol, openOrders.value) == 0
        if (stopMissing && targetStop != null) {
            gateway.tightenOpenDeadlineProtectiveStop(
                symbol = symbol,
                position = knownPosition,
                newStopPrice = targetStop
            )
            refreshBrokerSnapshot(gateway)
            if (awaitPositionFlat(symbol, positions, marketFallbackConfirmTimeoutMs, pollIntervalMs, gateway)) {
                cancelRemainingOrdersWhenFlat(gateway, symbol, positions.value)
                return Result.PositionConfirmedFlat
            }
        }

        gateway.closeOpenPositionForSymbol(symbol, knownPosition, purpose = "open_deadline_fallback")
        refreshBrokerSnapshot(gateway)
        val marketFallbackAttempted = true
        if (awaitPositionFlat(symbol, positions, marketFallbackConfirmTimeoutMs, pollIntervalMs, gateway)) {
            cancelRemainingOrdersWhenFlat(gateway, symbol, positions.value)
            return Result.PositionConfirmedFlatAfterMarketFallback
        }

        return Result.CloseUnconfirmedStopLossRetained(
            reason = "position_still_open_after_tight_stop_and_market_fallback",
            stopLossOrderCount = protectiveStopLossCount(symbol, openOrders.value),
            marketFallbackAttempted = marketFallbackAttempted
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

    internal fun cancelRemainingOrdersWhenFlat(
        gateway: BrokerGateway,
        symbol: String,
        positions: List<AccountPosition>
    ) {
        if (!OpenDeadlineProtectiveStopGuard.mayCancelProtectiveStops(symbol, positions)) return
        gateway.cancelOpenOrdersForSymbol(symbol, preserveStopLoss = false)
    }

    private fun refreshBrokerSnapshot(gateway: BrokerGateway) {
        gateway.refreshFills()
        gateway.refreshPositions()
    }

    private fun protectiveStopLossCount(symbol: String, openOrders: List<WorkingOrder>): Int =
        SymbolMarkets.openOrdersForSymbol(symbol, openOrders)
            .count { SessionOrderClassification.isProtectiveStopLoss(it) }
}
