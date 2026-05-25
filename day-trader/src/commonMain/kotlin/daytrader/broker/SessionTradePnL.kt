package daytrader.broker

import daytrader.domain.SessionTrade
import daytrader.domain.sessionRealizedPnL
import daytrader.gateway.AccountPosition
import daytrader.gateway.BrokerFill

object SessionTradePnL {
    fun unrealizedForSymbol(symbol: String, position: AccountPosition?): Double =
        position?.takeIf { SymbolMarkets.symbolsMatch(symbol, it.symbol) && it.quantity != 0 }
            ?.totalUnrealizedPnL
            ?: 0.0

    fun totalSessionPnL(trades: List<SessionTrade>, unrealizedPnL: Double): Double =
        trades.sessionRealizedPnL() + unrealizedPnL

    fun fillsForDisplay(
        symbol: String,
        startedAt: String,
        stoppedAt: String?,
        fills: List<BrokerFill>
    ): List<BrokerFill> = SessionTradeMatcher.fillsForSession(symbol, startedAt, stoppedAt, fills)
}
