package daytrader.broker.emulator

import daytrader.broker.SymbolMarkets

/** Public lookup for emulator catalog metadata (company names during symbol resolve). */
object EmulatorSymbolLookup {
    fun companyName(symbol: String): String? {
        val norm = SymbolMarkets.normalizeSymbol(symbol)
        return EmulatorSeedCatalog.instruments()[norm]?.companyName
    }
}
