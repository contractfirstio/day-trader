package daytrader.replay

import daytrader.broker.SymbolMarkets

/** Per-symbol catalog of captured [SessionBundle]s for parallel replay (hybrid-style routing). */
class ReplayCaptureRegistry(
    initialBundle: SessionBundle? = null,
    private val maxBundles: Int = DEFAULT_MAX_BUNDLES
) {
    private val bundlesBySymbol = linkedMapOf<String, SessionBundle>()

    init {
        initialBundle?.let(::register)
    }

    fun register(bundle: SessionBundle) {
        val norm = SymbolMarkets.normalizeSymbol(bundle.symbol)
        bundlesBySymbol[norm] = bundle
        evictOverflow(keep = norm)
    }

    fun bundleFor(symbol: String): SessionBundle? =
        bundlesBySymbol[SymbolMarkets.normalizeSymbol(symbol)]

    fun allBundles(): Collection<SessionBundle> = bundlesBySymbol.values

    fun evictSymbol(symbol: String) {
        bundlesBySymbol.remove(SymbolMarkets.normalizeSymbol(symbol))
    }

    fun registeredSymbols(): Set<String> = bundlesBySymbol.keys.toSet()

    val primaryBundle: SessionBundle?
        get() = bundlesBySymbol.values.firstOrNull()

    private fun evictOverflow(keep: String) {
        while (bundlesBySymbol.size > maxBundles) {
            val oldest = bundlesBySymbol.keys.firstOrNull { it != keep } ?: break
            bundlesBySymbol.remove(oldest)
        }
    }

    companion object {
        /** Safety cap for concurrent replay; active symbols are re-registered on session start. */
        const val DEFAULT_MAX_BUNDLES = 64
    }
}
