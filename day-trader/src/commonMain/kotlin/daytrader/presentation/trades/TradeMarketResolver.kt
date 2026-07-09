package daytrader.presentation.trades

import daytrader.broker.SymbolMarkets
import daytrader.domain.RthMarketSessions
import daytrader.gateway.BrokerFill

/** Infers RTH market from fill symbol and currency for the trades ledger. */
object TradeMarketResolver {
    fun zoneId(fill: BrokerFill): String = when {
        SymbolMarkets.isHongKong(fill.symbol) -> RthMarketSessions.HK.zoneId
        fill.currency.equals("HKD", ignoreCase = true) -> RthMarketSessions.HK.zoneId
        fill.currency.equals("GBP", ignoreCase = true) -> RthMarketSessions.EUR.zoneId
        else -> RthMarketSessions.US.zoneId
    }

    fun shortLabel(zoneId: String): String = when (zoneId) {
        RthMarketSessions.HK.zoneId -> "HK"
        RthMarketSessions.EUR.zoneId -> "UK"
        else -> "US"
    }

    fun shortLabel(fill: BrokerFill): String = shortLabel(zoneId(fill))
}
