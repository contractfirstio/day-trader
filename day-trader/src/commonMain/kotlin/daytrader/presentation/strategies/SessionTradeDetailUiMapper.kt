package daytrader.presentation.strategies

import daytrader.domain.SessionFillDisplay
import daytrader.domain.SessionTrade
import daytrader.domain.SessionTradeDetails
import daytrader.domain.SessionTradeDetailsBuilder
import daytrader.domain.sessionCommissionTotal
import daytrader.domain.sessionNetPnL
import daytrader.broker.SessionTradePnL
import daytrader.presentation.Formatters

data class SessionTradeFillUi(
    val execId: String,
    val roleLabel: String,
    val sideLabel: String,
    val quantity: Int,
    val formattedPrice: String,
    val formattedTime: String,
    val formattedRealizedPnL: String?,
    val isPositivePnL: Boolean,
    val formattedCommission: String?,
)

data class SessionTradeDetailUiState(
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
    val hasCommissionData: Boolean,
    val formattedTotalCommission: String?,
    val totalCommission: Double?,
    val formattedNetPnL: String?,
    val netPnL: Double?,
    val isPositiveNetPnL: Boolean?,
    val fills: List<SessionTradeFillUi>,
    val emptyMessage: String?,
) {
    val showNetAsPrimary: Boolean
        get() = !isOpen && hasCommissionData && formattedNetPnL != null
}

object SessionTradeDetailUiMapper {
    fun fromSessionTrades(
        trades: List<SessionTrade>,
        unrealizedPnL: Double = 0.0,
        lifecycleLabel: String? = null,
        runLabel: String? = null
    ): SessionTradeDetailUiState? {
        if (trades.isEmpty()) return null
        val details = SessionTradeDetailsBuilder.build(trades) ?: return null
        val currency = details.currency
        val realized = details.realizedPnL
        val hasCommissionData = trades.isNotEmpty() && trades.all { it.commission != null }
        val totalCommission = if (hasCommissionData) trades.sessionCommissionTotal() else null
        val netPnL = if (hasCommissionData && !details.isOpen) trades.sessionNetPnL() else null
        val sessionTotal = SessionTradePnL.totalSessionPnL(trades, unrealizedPnL)
        val headline = buildString {
            append(details.quantity)
            append(" @ ")
            append(Formatters.moneyPlain(details.entryPrice ?: 0.0, currency))
        }
        val detailLine = formatDetailLine(
            details = details,
            currency = currency,
            hasCommissionData = hasCommissionData,
            totalCommission = totalCommission,
            netPnL = netPnL,
        )
        val fills = SessionTradeDetailsBuilder.fillDisplays(trades).map { toFillUi(it) }
        return SessionTradeDetailUiState(
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
            hasCommissionData = hasCommissionData,
            formattedTotalCommission = totalCommission?.let { commission ->
                Formatters.money(-commission, currency, showSign = true)
            },
            totalCommission = totalCommission,
            formattedNetPnL = netPnL?.let { Formatters.money(it, currency, showSign = true) },
            netPnL = netPnL,
            isPositiveNetPnL = netPnL?.let { it >= 0 },
            fills = fills,
            emptyMessage = null
        )
    }

    private fun formatDetailLine(
        details: SessionTradeDetails,
        currency: String,
        hasCommissionData: Boolean,
        totalCommission: Double?,
        netPnL: Double?,
    ): String {
        val entry = details.entryPrice ?: return details.sideLabel
        return when {
            details.exitPrice != null -> buildString {
                append("Entry ")
                append(Formatters.moneyPlain(entry, currency))
                append(" → exit ")
                append(Formatters.moneyPlain(details.exitPrice, currency))
                append(" · ")
                if (hasCommissionData && netPnL != null && totalCommission != null) {
                    append(Formatters.money(netPnL, currency, showSign = true))
                    append(" net · ")
                    append(Formatters.money(details.realizedPnL, currency, showSign = true))
                    append(" gross · ")
                    append(Formatters.money(-totalCommission, currency, showSign = true))
                    append(" commission")
                } else {
                    append(Formatters.money(details.realizedPnL, currency, showSign = true))
                    append(" realized")
                }
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

    private fun toFillUi(fill: SessionFillDisplay): SessionTradeFillUi {
        val pnl = fill.realizedPnL
        return SessionTradeFillUi(
            execId = fill.execId,
            roleLabel = fill.roleLabel,
            sideLabel = fill.actionLabel,
            quantity = fill.quantity,
            formattedPrice = Formatters.moneyPlain(fill.price, fill.currency),
            formattedTime = fill.time.ifBlank { "—" },
            formattedRealizedPnL = pnl?.let { Formatters.money(it, fill.currency, showSign = true) },
            isPositivePnL = (pnl ?: 0.0) >= 0,
            formattedCommission = fill.commission?.let {
                Formatters.money(-it, fill.currency, showSign = true)
            },
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
                if (trades.isNotEmpty() && trades.all { it.commission != null } && !details.isOpen) {
                    append(" · ")
                    append(Formatters.money(trades.sessionNetPnL(), currency, showSign = true))
                    append(" net")
                }
            }
            details.isOpen -> "$side ${details.quantity} · open"
            else -> "$side ${details.quantity}"
        }
        return side to summary
    }
}
