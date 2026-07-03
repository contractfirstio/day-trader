package daytrader.domain

import daytrader.broker.SymbolMarkets

/**
 * Maps IB contract metadata (first [reqContractDetails] hit) to a trading session zone.
 */
object InstrumentMarketResolver {
    data class ContractSnapshot(
        val symbol: String,
        val exchange: String?,
        val primaryExch: String?,
        val currency: String?,
        val companyName: String? = null,
        val minOrderSize: Int? = null,
        val orderSizeIncrement: Int? = null,
        val minPriceTick: Double? = null,
        val marketRuleId: Int? = null,
        val priceIncrements: List<InstrumentPriceIncrement> = emptyList()
    )

    private val UK_EXCHANGES = listOf("LSE", "LSEETF", "IOB", "CHIX")

    fun fromIbContract(snapshot: ContractSnapshot): ResolvedInstrument {
        val exchange = snapshot.exchange.orEmpty().uppercase()
        val primary = snapshot.primaryExch.orEmpty().uppercase()
        val currency = snapshot.currency.orEmpty().uppercase()

        val zoneId = when {
            primary.contains("SEHK") || exchange == "SEHK" -> RthMarketSessions.HK.zoneId
            UK_EXCHANGES.any { primary.contains(it) || exchange.contains(it) } ->
                RthMarketSessions.EUR.zoneId
            currency == "HKD" && SymbolMarkets.isHongKong(snapshot.symbol) ->
                RthMarketSessions.HK.zoneId
            currency == "GBP" -> RthMarketSessions.EUR.zoneId
            currency == "USD" && !primary.contains("SEHK") && !UK_EXCHANGES.any { primary.contains(it) } ->
                RthMarketSessions.US.zoneId
            currency == "HKD" -> RthMarketSessions.HK.zoneId
            else -> DeploymentMarket.fromSymbolHeuristic(snapshot.symbol).marketZoneId
        }

        val resolvedCurrency = currency.ifBlank { DeploymentMarket.currencyForZone(zoneId) }
        val venue = buildVenueLabel(primary, exchange, resolvedCurrency)
        val session = RthMarketSessions.forZoneId(zoneId)
        return ResolvedInstrument(
            marketZoneId = zoneId,
            currencyCode = resolvedCurrency,
            venueLabel = venue,
            source = MarketSource.IB,
            companyName = snapshot.companyName?.takeIf { it.isNotBlank() },
            identity = InstrumentIdentity.fromContractSnapshot(snapshot)
        )
    }

    private fun buildVenueLabel(primary: String, exchange: String, currency: String): String {
        val venue = when {
            primary.isNotBlank() -> primary
            exchange.isNotBlank() -> exchange
            else -> "—"
        }
        return "$venue · $currency"
    }
}
