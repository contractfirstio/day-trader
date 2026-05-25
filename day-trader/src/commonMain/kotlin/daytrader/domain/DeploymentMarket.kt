package daytrader.domain

import daytrader.broker.SymbolMarkets
import daytrader.broker.emulator.EmulatorSymbolLookup

enum class MarketSource {
    USER,
    IB,
    SYMBOL_INFERRED,
    LEGACY_INFERRED
}

data class ResolvedInstrument(
    val marketZoneId: String,
    val currencyCode: String,
    /** Short venue line for UI, e.g. "LSE · GBP". */
    val venueLabel: String,
    val source: MarketSource,
    /** IB long name when available. */
    val companyName: String? = null
)

object DeploymentMarket {
    fun effectiveZoneId(deployment: StrategyDeployment): String =
        deployment.marketZoneId ?: SymbolMarkets.zoneId(deployment.symbol)

    fun effectiveCurrencyCode(deployment: StrategyDeployment): String =
        deployment.currencyCode.ifBlank { currencyForZone(effectiveZoneId(deployment)) }

    fun currencyForZone(marketZoneId: String): String = when (marketZoneId) {
        RthMarketSessions.HK.zoneId -> "HKD"
        RthMarketSessions.EUR.zoneId -> "GBP"
        else -> "USD"
    }

    fun sessionForZone(marketZoneId: String): RthMarketSession =
        RthMarketSessions.forZoneId(marketZoneId)

    fun sessionDisplayLabel(session: RthMarketSession): String = session.label

    fun fromSymbolHeuristic(symbol: String): ResolvedInstrument {
        val zoneId = SymbolMarkets.zoneId(symbol)
        val currency = currencyForZone(zoneId)
        val session = RthMarketSessions.forZoneId(zoneId)
        return ResolvedInstrument(
            marketZoneId = zoneId,
            currencyCode = currency,
            venueLabel = "${sessionDisplayLabel(session)} · $currency (estimated)",
            source = MarketSource.SYMBOL_INFERRED,
            companyName = EmulatorSymbolLookup.companyName(symbol)
        )
    }
}
