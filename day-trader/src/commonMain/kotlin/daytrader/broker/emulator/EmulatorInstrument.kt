package daytrader.broker.emulator

import daytrader.broker.SymbolMarkets

internal data class EmulatorInstrument(
    val symbol: String,
    val companyName: String,
    val currency: String,
    val priorClose: Double,
    val referencePrice: Double
) {
    val marketZoneId: String get() = SymbolMarkets.zoneId(symbol)
}
