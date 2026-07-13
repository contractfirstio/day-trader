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
    /** Re-issue market close while still open (COIN session-50b76edb44905955 pacing miss). */
    const val MARKET_FALLBACK_RETRY_INTERVAL_MS = 5_000L
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
        pollIntervalMs: Long = POLL_INTERVAL_MS,
        marketFallbackRetryIntervalMs: Long = MARKET_FALLBACK_RETRY_INTERVAL_MS
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

        if (awaitPositionFlat(
                symbol = symbol,
                positions = positions,
                openOrders = openOrders,
                timeoutMs = confirmTimeoutMs,
                pollIntervalMs = pollIntervalMs,
                gateway = gateway,
                marketFallbackAttempted = false
            )
        ) {
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
            if (awaitPositionFlat(
                    symbol = symbol,
                    positions = positions,
                    openOrders = openOrders,
                    timeoutMs = marketFallbackConfirmTimeoutMs,
                    pollIntervalMs = pollIntervalMs,
                    gateway = gateway,
                    marketFallbackAttempted = false
                )
            ) {
                cancelRemainingOrdersWhenFlat(gateway, symbol, positions.value)
                return Result.PositionConfirmedFlat
            }
        }

        gateway.closeOpenPositionForSymbol(symbol, knownPosition, purpose = "open_deadline_fallback")
        refreshBrokerSnapshot(gateway)
        val marketFallbackAttempted = true
        if (awaitPositionFlatWithMarketRetries(
                gateway = gateway,
                symbol = symbol,
                knownPosition = knownPosition,
                positions = positions,
                openOrders = openOrders,
                timeoutMs = marketFallbackConfirmTimeoutMs,
                pollIntervalMs = pollIntervalMs,
                retryIntervalMs = marketFallbackRetryIntervalMs
            )
        ) {
            cancelRemainingOrdersWhenFlat(gateway, symbol, positions.value)
            return Result.PositionConfirmedFlatAfterMarketFallback
        }

        // Market close failed — ensure a protective stop remains (may have been dropped for the MKT).
        if (targetStop != null && protectiveStopLossCount(symbol, openOrders.value) == 0) {
            gateway.tightenOpenDeadlineProtectiveStop(
                symbol = symbol,
                position = knownPosition,
                newStopPrice = targetStop
            )
            refreshBrokerSnapshot(gateway)
        }

        return Result.CloseUnconfirmedStopLossRetained(
            reason = "position_still_open_after_tight_stop_and_market_fallback",
            stopLossOrderCount = protectiveStopLossCount(symbol, openOrders.value),
            marketFallbackAttempted = marketFallbackAttempted
        )
    }

    /**
     * After the first market-close, keep polling and re-issue MKT every [retryIntervalMs] until flat
     * or [timeoutMs] elapses. Matches COIN 2026-07-13 where a single paced placeOrder never filled.
     */
    private suspend fun awaitPositionFlatWithMarketRetries(
        gateway: BrokerGateway,
        symbol: String,
        knownPosition: AccountPosition,
        positions: StateFlow<List<AccountPosition>>,
        openOrders: StateFlow<List<WorkingOrder>>,
        timeoutMs: Long,
        pollIntervalMs: Long,
        retryIntervalMs: Long
    ): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        val effectiveRetryMs = retryIntervalMs
            .coerceAtMost((timeoutMs / 3).coerceAtLeast(1L))
            .coerceAtLeast(pollIntervalMs)
        var nextCloseAt = System.currentTimeMillis() + effectiveRetryMs
        while (System.currentTimeMillis() < deadline) {
            if (isDurableFlat(symbol, positions.value, openOrders.value, marketFallbackAttempted = true)) {
                return true
            }
            val now = System.currentTimeMillis()
            if (now >= nextCloseAt) {
                gateway.closeOpenPositionForSymbol(symbol, knownPosition, purpose = "open_deadline_fallback")
                nextCloseAt = now + effectiveRetryMs
            }
            refreshBrokerSnapshot(gateway)
            delay(pollIntervalMs)
        }
        return isDurableFlat(symbol, positions.value, openOrders.value, marketFallbackAttempted = true)
    }

    /**
     * True when the broker no longer shows an open position for [symbol].
     *
     * Before market fallback, an empty position cache **with** a working protective stop is treated
     * as inconclusive (shared-cache wipe during batch OPEN_DEADLINE). Confirming that as flat would
     * cancel the stop and leave a naked position — F session-3f4deedbb5fb0a07 on 2026-07-13.
     */
    internal fun isDurableFlat(
        symbol: String,
        positions: List<AccountPosition>,
        openOrders: List<WorkingOrder>,
        marketFallbackAttempted: Boolean
    ): Boolean {
        if (SymbolMarkets.hasOpenPosition(symbol, positions)) return false
        if (!marketFallbackAttempted && protectiveStopLossCount(symbol, openOrders) > 0) {
            return false
        }
        return true
    }

    internal suspend fun awaitPositionFlat(
        symbol: String,
        positions: StateFlow<List<AccountPosition>>,
        timeoutMs: Long,
        pollIntervalMs: Long,
        gateway: BrokerGateway? = null,
        onPoll: () -> Unit = {},
        openOrders: StateFlow<List<WorkingOrder>>? = null,
        marketFallbackAttempted: Boolean = true
    ): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            val orders = openOrders?.value.orEmpty()
            if (isDurableFlat(symbol, positions.value, orders, marketFallbackAttempted)) {
                return true
            }
            if (gateway != null) {
                refreshBrokerSnapshot(gateway)
            } else {
                onPoll()
            }
            delay(pollIntervalMs)
        }
        val orders = openOrders?.value.orEmpty()
        return isDurableFlat(symbol, positions.value, orders, marketFallbackAttempted)
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
