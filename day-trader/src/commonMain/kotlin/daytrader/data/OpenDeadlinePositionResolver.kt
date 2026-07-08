package daytrader.data

import daytrader.broker.SessionTradeMatcher
import daytrader.broker.SymbolMarkets
import daytrader.domain.StrategyDeployment
import daytrader.domain.inProgressSession
import daytrader.gateway.AccountPosition
import daytrader.gateway.BrokerFill

/**
 * Resolves the broker position to flatten at OPEN_DEADLINE when the live cache may be stale.
 * Falls back to session fills when [touchTurnSession.milestones.positionOpenedAt] is set.
 */
object OpenDeadlinePositionResolver {
    enum class PositionSource {
        DECISION_SNAPSHOT,
        LIVE_CACHE,
        INFERRED_FROM_FILLS,
        NONE
    }

    data class Resolved(
        val position: AccountPosition?,
        val source: PositionSource
    )

    fun resolve(
        instance: StrategyDeployment,
        brokerPositionAtDecision: AccountPosition?,
        livePositions: List<AccountPosition>,
        fills: List<BrokerFill>
    ): Resolved {
        brokerPositionAtDecision?.takeIf {
            SymbolMarkets.matchesDeployment(instance, it) && it.quantity != 0
        }?.let { return Resolved(it, PositionSource.DECISION_SNAPSHOT) }

        SymbolMarkets.findOpenPosition(instance, livePositions)?.let {
            return Resolved(it, PositionSource.LIVE_CACHE)
        }

        if (!hadPositionOpenedMilestone(instance)) {
            return Resolved(null, PositionSource.NONE)
        }

        inferFromSessionFills(instance, fills)?.let {
            return Resolved(it, PositionSource.INFERRED_FROM_FILLS)
        }

        return Resolved(null, PositionSource.NONE)
    }

    fun hadPositionOpenedMilestone(instance: StrategyDeployment): Boolean =
        instance.touchTurnSession?.milestones?.positionOpenedAt != null

    internal fun inferFromSessionFills(
        instance: StrategyDeployment,
        fills: List<BrokerFill>
    ): AccountPosition? {
        val session = instance.inProgressSession() ?: return null
        val sessionFills = SessionTradeMatcher.fillsForSession(
            symbol = instance.symbol,
            startedAt = session.startedAt,
            stoppedAt = null,
            fills = fills
        )
        val netQty = sessionFills.sumOf(::signedFillQuantity)
        if (netQty == 0) return null

        val entryPrice = sessionFills.lastOrNull { it.parentOrderId == 0 }?.price
            ?: sessionFills.last().price
        val currency = instance.currencyCode.ifBlank { SymbolMarkets.currencyCode(instance.symbol) }

        return AccountPosition(
            account = "",
            symbol = instance.symbol,
            companyName = instance.instrument?.symbol ?: instance.symbol,
            quantity = netQty,
            avgPrice = entryPrice,
            marketPrice = entryPrice,
            priorClose = null,
            totalUnrealizedPnL = 0.0,
            currency = currency
        )
    }

    internal fun signedFillQuantity(fill: BrokerFill): Int = when (fill.side.uppercase()) {
        "BUY", "BOT" -> fill.quantity
        "SELL", "SLD" -> -fill.quantity
        else -> 0
    }
}
