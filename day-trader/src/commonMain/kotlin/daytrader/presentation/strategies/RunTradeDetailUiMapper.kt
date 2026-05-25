package daytrader.presentation.strategies

import daytrader.domain.SessionFillDisplay
import daytrader.domain.SessionTrade
import daytrader.domain.SessionTradeDetails
import daytrader.domain.SessionTradeDetailsBuilder
import daytrader.domain.sessionRealizedPnL
import daytrader.broker.SessionTradePnL
import daytrader.presentation.Formatters

data class RunTradeFillUi(
    val execId: String,
    val roleLabel: String,
    val sideLabel: String,
    val quantity: Int,
    val formattedPrice: String,
    val formattedTime: String,
    val formattedRealizedPnL: String?,
    val isPositivePnL: Boolean
)

data class RunTradeDetailUiState(
    val sideLabel: String,
    val isLong: Boolean,
    val isOpen: Boolean,
    val headline: String,
    val detailLine: String,
    val formattedEntryPrice: String?,
    val formattedExitPrice: String?,
    val lifecycleLabel: String?,
    val formattedRealizedPnL: String,
    val realizedPnL: Double,
    val isPositiveRealizedPnL: Boolean,
    val formattedUnrealizedPnL: String?,
    val unrealizedPnL: Double?,
    val formattedSessionPnL: String?,
    val sessionPnL: Double?,
    val isPositiveSessionPnL: Boolean?,
    val fills: List<RunTradeFillUi>,
    val emptyMessage: String?
)

object RunTradeDetailUiMapper {
    fun fromSessionTrades(
        trades: List<SessionTrade>,
        unrealizedPnL: Double = 0.0,
        lifecycleLabel: String? = null,
        runLabel: String? = null
    ): RunTradeDetailUiState? {
        if (trades.isEmpty()) return null
        val details = SessionTradeDetailsBuilder.build(trades) ?: return null
        val currency = details.currency
        val realized = details.realizedPnL
        val sessionTotal = SessionTradePnL.totalSessionPnL(trades, unrealizedPnL)
        val headline = buildString {
            append(details.quantity)
            append(" @ ")
            append(Formatters.moneyPlain(details.entryPrice ?: 0.0, currency))
        }
        val detailLine = formatDetailLine(details, currency)
        val fills = SessionTradeDetailsBuilder.fillDisplays(trades).map { toFillUi(it) }
        return RunTradeDetailUiState(
            sideLabel = details.sideLabel,
            isLong = details.sideLabel == "Long",
            isOpen = details.isOpen,
            headline = headline,
            detailLine = detailLine,
            formattedEntryPrice = details.entryPrice?.let { Formatters.moneyPlain(it, currency) },
            formattedExitPrice = details.exitPrice?.let { Formatters.moneyPlain(it, currency) },
            lifecycleLabel = lifecycleLabel ?: runLabel,
            formattedRealizedPnL = Formatters.money(realized, currency, showSign = true),
            realizedPnL = realized,
            isPositiveRealizedPnL = realized >= 0,
            formattedUnrealizedPnL = if (unrealizedPnL != 0.0 || details.isOpen) {
                Formatters.money(unrealizedPnL, currency, showSign = true)
            } else {
                null
            },
            unrealizedPnL = if (unrealizedPnL != 0.0 || details.isOpen) unrealizedPnL else null,
            formattedSessionPnL = Formatters.money(sessionTotal, currency, showSign = true),
            sessionPnL = sessionTotal,
            isPositiveSessionPnL = sessionTotal >= 0,
            fills = fills,
            emptyMessage = null
        )
    }

    private fun formatDetailLine(details: SessionTradeDetails, currency: String): String {
        val entry = details.entryPrice ?: return details.sideLabel
        return when {
            details.exitPrice != null -> buildString {
                append("Entry ")
                append(Formatters.moneyPlain(entry, currency))
                append(" → exit ")
                append(Formatters.moneyPlain(details.exitPrice, currency))
                append(" · ")
                append(Formatters.money(details.realizedPnL, currency, showSign = true))
                append(" realized")
            }
            details.isOpen -> buildString {
                append("Entry ")
                append(Formatters.moneyPlain(entry, currency))
                append(" · position open")
            }
            else -> buildString {
                append(details.sideLabel)
                append(" · ")
                append(details.quantity)
                append(" @ ")
                append(Formatters.moneyPlain(entry, currency))
            }
        }
    }

    private fun toFillUi(fill: SessionFillDisplay): RunTradeFillUi {
        val pnl = fill.realizedPnL
        return RunTradeFillUi(
            execId = fill.execId,
            roleLabel = fill.roleLabel,
            sideLabel = fill.actionLabel,
            quantity = fill.quantity,
            formattedPrice = Formatters.moneyPlain(fill.price, fill.currency),
            formattedTime = fill.time.ifBlank { "—" },
            formattedRealizedPnL = pnl?.let { Formatters.money(it, fill.currency, showSign = true) },
            isPositivePnL = (pnl ?: 0.0) >= 0
        )
    }

    fun tradeSummaryForRow(trades: List<SessionTrade>): Pair<String?, String?> {
        val details = SessionTradeDetailsBuilder.build(trades) ?: return null to null
        val currency = details.currency
        val side = details.sideLabel
        val summary = when {
            details.exitPrice != null -> buildString {
                append(side)
                append(" ")
                append(details.quantity)
                append(" · ")
                append(Formatters.moneyPlain(details.entryPrice ?: 0.0, currency))
                append(" → ")
                append(Formatters.moneyPlain(details.exitPrice, currency))
            }
            details.isOpen -> "$side ${details.quantity} · open"
            else -> "$side ${details.quantity}"
        }
        return side to summary
    }
}
