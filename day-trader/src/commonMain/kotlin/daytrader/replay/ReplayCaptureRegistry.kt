package daytrader.replay

import daytrader.broker.SymbolMarkets

/** Per-symbol catalog of captured [SessionBundle]s for parallel replay (hybrid-style routing). */
class ReplayCaptureRegistry(initialBundle: SessionBundle? = null) {
    private val bundlesBySymbol = linkedMapOf<String, SessionBundle>()

    init {
        initialBundle?.let(::register)
    }

    fun register(bundle: SessionBundle) {
        bundlesBySymbol[SymbolMarkets.normalizeSymbol(bundle.symbol)] = bundle
    }

    fun bundleFor(symbol: String): SessionBundle? =
        bundlesBySymbol[SymbolMarkets.normalizeSymbol(symbol)]

    fun allBundles(): Collection<SessionBundle> = bundlesBySymbol.values

    val primaryBundle: SessionBundle?
        get() = bundlesBySymbol.values.firstOrNull()
}
