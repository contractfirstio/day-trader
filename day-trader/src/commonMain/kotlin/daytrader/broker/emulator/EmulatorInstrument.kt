package daytrader.broker.emulator

import daytrader.broker.SymbolMarkets
import daytrader.domain.RthMarketSessions

internal data class EmulatorInstrument(
    val symbol: String,
    val companyName: String,
    val currency: String,
    val priorClose: Double,
    val referencePrice: Double,
    val primaryExch: String? = null
) {
    val marketZoneId: String
        get() = if (SymbolMarkets.isHongKong(symbol)) {
            RthMarketSessions.HK.zoneId
        } else {
            SymbolMarkets.zoneIdForCurrency(currency)
        }
}
