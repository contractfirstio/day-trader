package daytrader.data.persistence

import daytrader.gateway.BrokerFill

object TradesPersistence {
    const val FLEX_EXEC_ID_PREFIX = "flex-"

    /**
     * Replaces all stored Flex rows with the latest Flex statement, then merges in [incoming].
     * Live TWS fills (non-flex execIds) are preserved. Use for IB Flex sync so trade dates
     * refresh even when execIds were already stored from an earlier query definition.
     */
    fun mergeFlexFills(stored: List<BrokerFill>, incoming: List<BrokerFill>): MergeFillsResult {
        if (incoming.isEmpty()) return MergeFillsResult(stored, added = 0, updated = 0)
        val withoutFlex = stored.filterNot { it.execId.startsWith(FLEX_EXEC_ID_PREFIX) }
        return mergeFills(withoutFlex, incoming)
    }

    fun toRecord(fill: BrokerFill): BrokerFillRecord =
        BrokerFillRecord(
            execId = fill.execId,
            orderId = fill.orderId,
            permId = fill.permId,
            parentOrderId = fill.parentOrderId,
            symbol = fill.symbol,
            side = fill.side,
            quantity = fill.quantity,
            price = fill.price,
            time = fill.time,
            currency = fill.currency,
            commission = fill.commission,
            realizedPnL = fill.realizedPnL,
        )

    fun toDomain(record: BrokerFillRecord): BrokerFill =
        BrokerFill(
            execId = record.execId,
            orderId = record.orderId,
            permId = record.permId,
            parentOrderId = record.parentOrderId,
            symbol = record.symbol,
            side = record.side,
            quantity = record.quantity,
            price = record.price,
            time = record.time,
            currency = record.currency,
            commission = record.commission,
            realizedPnL = record.realizedPnL,
        )

    fun mergeFills(stored: List<BrokerFill>, incoming: List<BrokerFill>): MergeFillsResult {
        if (incoming.isEmpty()) return MergeFillsResult(stored, added = 0, updated = 0)
        val byExecId = stored.associateBy { it.execId }.toMutableMap()
        var added = 0
        var updated = 0
        incoming.forEach { fill ->
            if (fill.execId.isBlank()) return@forEach
            val existing = byExecId[fill.execId]
            if (existing == null) {
                byExecId[fill.execId] = fill
                added++
            } else {
                val merged = enrichFill(existing, fill)
                if (merged != existing) {
                    byExecId[fill.execId] = merged
                    updated++
                }
            }
        }
        return MergeFillsResult(
            fills = byExecId.values.sortedWith(compareBy<BrokerFill> { it.time }.thenBy { it.execId }),
            added = added,
            updated = updated,
        )
    }

    private fun enrichFill(existing: BrokerFill, incoming: BrokerFill): BrokerFill =
        existing.copy(
            orderId = incoming.orderId,
            permId = incoming.permId,
            parentOrderId = incoming.parentOrderId.takeIf { it != 0 } ?: existing.parentOrderId,
            symbol = incoming.symbol.ifBlank { existing.symbol },
            side = incoming.side.ifBlank { existing.side },
            quantity = incoming.quantity.takeIf { it > 0 } ?: existing.quantity,
            price = incoming.price.takeIf { it > 0.0 } ?: existing.price,
            time = when {
                incoming.time.isNotBlank() -> incoming.time
                else -> existing.time
            },
            currency = incoming.currency.ifBlank { existing.currency },
            commission = incoming.commission ?: existing.commission,
            realizedPnL = incoming.realizedPnL ?: existing.realizedPnL,
        )
}

data class MergeFillsResult(
    val fills: List<BrokerFill>,
    val added: Int,
    val updated: Int,
)
