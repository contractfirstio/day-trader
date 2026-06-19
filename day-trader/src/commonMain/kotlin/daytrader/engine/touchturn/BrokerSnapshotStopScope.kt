package daytrader.engine.touchturn

import daytrader.broker.SymbolMarkets
import daytrader.engine.BrokerSnapshotSource
import daytrader.engine.TouchTurnCommand
import daytrader.gateway.AccountPosition
import daytrader.gateway.BrokerFill
import daytrader.gateway.WorkingOrder

/**
 * Merges a partial broker snapshot into the engine's cached broker state.
 * Each gateway flow dispatches only its own dimension; stale companion fields in the
 * command must not overwrite fresher merged state (fills are append-only).
 */
internal object BrokerSnapshotMerger {
    fun apply(
        source: BrokerSnapshotSource,
        command: TouchTurnCommand.BrokerSnapshot,
        currentPositions: List<AccountPosition>,
        currentOpenOrders: List<WorkingOrder>,
        currentFills: List<BrokerFill>
    ): Triple<List<AccountPosition>, List<WorkingOrder>, List<BrokerFill>> =
        when (source) {
            BrokerSnapshotSource.POSITIONS -> Triple(
                command.positions,
                currentOpenOrders,
                currentFills
            )
            BrokerSnapshotSource.OPEN_ORDERS -> Triple(
                currentPositions,
                command.openOrders,
                currentFills
            )
            BrokerSnapshotSource.FILLS -> {
                val nextFills = if (command.fills.size >= currentFills.size) {
                    command.fills
                } else {
                    currentFills
                }
                Triple(currentPositions, currentOpenOrders, nextFills)
            }
        }
}

/**
 * Derives which deployment symbols need auto-stop evaluation after a broker snapshot.
 * Mark-only position updates (PnL / price) are ignored so parallel replay quote drips
 * do not fan out stop-rule work to unrelated sessions.
 */
internal object BrokerSnapshotStopScope {
    fun affectedSymbols(
        previousPositions: List<AccountPosition>,
        previousOpenOrders: List<WorkingOrder>,
        previousFills: List<BrokerFill>,
        positions: List<AccountPosition>,
        openOrders: List<WorkingOrder>,
        fills: List<BrokerFill>
    ): Set<String> {
        val symbols = linkedSetOf<String>()
        symbols += positionQuantityChanges(previousPositions, positions)
        symbols += openOrderChanges(previousOpenOrders, openOrders)
        symbols += newFillSymbols(previousFills, fills)
        return symbols
    }

    private fun positionQuantityChanges(
        previous: List<AccountPosition>,
        current: List<AccountPosition>
    ): Set<String> {
        val prevQty = previous.associate { SymbolMarkets.normalizeSymbol(it.symbol) to it.quantity }
        val nextQty = current.associate { SymbolMarkets.normalizeSymbol(it.symbol) to it.quantity }
        val symbols = linkedSetOf<String>()
        for (symbol in prevQty.keys + nextQty.keys) {
            if (prevQty[symbol] != nextQty[symbol]) {
                symbols += symbol
            }
        }
        return symbols
    }

    private fun openOrderChanges(
        previous: List<WorkingOrder>,
        current: List<WorkingOrder>
    ): Set<String> {
        val prevSig = orderSignatureBySymbol(previous)
        val nextSig = orderSignatureBySymbol(current)
        val symbols = linkedSetOf<String>()
        for (symbol in prevSig.keys + nextSig.keys) {
            if (prevSig[symbol] != nextSig[symbol]) {
                symbols += symbol
            }
        }
        return symbols
    }

    private fun orderSignatureBySymbol(orders: List<WorkingOrder>): Map<String, String> =
        orders.groupBy { SymbolMarkets.normalizeSymbol(it.symbol) }
            .mapValues { (_, symbolOrders) ->
                symbolOrders
                    .sortedBy { it.orderId }
                    .joinToString("|") { "${it.orderId}:${it.status}:${it.remaining}" }
            }

    private fun newFillSymbols(previous: List<BrokerFill>, current: List<BrokerFill>): Set<String> {
        if (current.size <= previous.size) return emptySet()
        return current.drop(previous.size)
            .map { SymbolMarkets.normalizeSymbol(it.symbol) }
            .toSet()
    }
}

internal data class AutoStopCheckSnapshot(
    val wouldStop: Boolean,
    val hasOpenPosition: Boolean,
    val hasOpenOrders: Boolean,
    val tradeCycleComplete: Boolean
)
