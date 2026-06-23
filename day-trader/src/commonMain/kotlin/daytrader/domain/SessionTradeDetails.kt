package daytrader.domain

/** Parsed view of a run's broker fills for display and P&L verification. */
data class SessionTradeDetails(
    val sideLabel: String,
    val quantity: Int,
    val currency: String,
    val entryPrice: Double?,
    val exitPrice: Double?,
    val isOpen: Boolean,
    val realizedPnL: Double,
    val entryFill: SessionTrade?,
    val exitFills: List<SessionTrade>,
    val orderedFills: List<SessionTrade>
)

data class SessionFillDisplay(
    val execId: String,
    val roleLabel: String,
    val actionLabel: String,
    val quantity: Int,
    val price: Double,
    val currency: String,
    val time: String,
    val realizedPnL: Double?,
    val commission: Double?,
)

object SessionTradeDetailsBuilder {
    fun build(trades: List<SessionTrade>): SessionTradeDetails? {
        if (trades.isEmpty()) return null
        val currency = trades.first().currency
        val entry = trades.firstOrNull { it.parentOrderId == 0 } ?: trades.first()
        val sideLabel = sideLabelFromAction(entry.side)
        val qty = entry.quantity
        val entryPrice = entry.price
        val exitFills = trades.filter { it.parentOrderId != 0 && it.realizedPnL != null }
        val exitFill = exitFills.lastOrNull()
        val exitPrice = exitFill?.price
        val realized = trades.sessionRealizedPnL()
        val isOpen = exitFill == null && trades.isNotEmpty()
        return SessionTradeDetails(
            sideLabel = sideLabel,
            quantity = qty,
            currency = currency,
            entryPrice = entryPrice,
            exitPrice = exitPrice,
            isOpen = isOpen,
            realizedPnL = realized,
            entryFill = entry,
            exitFills = exitFills,
            orderedFills = trades.sortedWith(compareBy({ it.time }, { it.execId }))
        )
    }

    fun fillDisplays(trades: List<SessionTrade>): List<SessionFillDisplay> =
        trades.sortedWith(compareBy({ it.time }, { it.execId })).map { trade ->
            val role = when {
                trade.parentOrderId == 0 -> "Entry"
                trade.realizedPnL != null -> "Exit"
                else -> "Order"
            }
            SessionFillDisplay(
                execId = trade.execId,
                roleLabel = role,
                actionLabel = sideLabelFromAction(trade.side),
                quantity = trade.quantity,
                price = trade.price,
                currency = trade.currency,
                time = trade.time,
                realizedPnL = trade.realizedPnL,
                commission = trade.commission,
            )
        }

    fun sideLabelFromAction(action: String): String = when (action.uppercase()) {
        "BUY", "BOT" -> "Long"
        "SELL", "SLD" -> "Short"
        else -> action.ifBlank { "—" }
    }

    fun sideFromPositionQuantity(quantity: Int): String? = when {
        quantity > 0 -> "Long"
        quantity < 0 -> "Short"
        else -> null
    }
}
