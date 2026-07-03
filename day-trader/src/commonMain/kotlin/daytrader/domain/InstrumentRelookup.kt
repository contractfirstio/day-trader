package daytrader.domain

import daytrader.broker.SymbolMarkets
import daytrader.gateway.BrokerGateway
import daytrader.gateway.GatewayConnectionState

/** Re-resolves IB contract details (including board lot size) for saved instruments. */
object InstrumentRelookup {
    data class Outcome(
        val identity: InstrumentIdentity,
        val companyName: String?,
        val listingLabel: String,
        val orderSizeRules: InstrumentOrderSizeRules
    )

    fun supportsBrokerKind(brokerKind: daytrader.gateway.BrokerKind): Boolean =
        brokerKind == daytrader.gateway.BrokerKind.INTERACTIVE_BROKERS ||
            brokerKind == daytrader.gateway.BrokerKind.EMULATOR_LIVE_IB_MARKET_DATA

    suspend fun relookup(
        gateway: BrokerGateway,
        symbol: String,
        existingInstrument: InstrumentIdentity?,
        marketZoneId: String? = null
    ): Result<Outcome> {
        if (gateway.connectionState.value != GatewayConnectionState.Connected) {
            return Result.failure(IllegalStateException("Connect to Interactive Brokers first"))
        }
        val trimmed = symbol.trim().uppercase()
        if (trimmed.isBlank()) {
            return Result.failure(IllegalArgumentException("Symbol is blank"))
        }
        return gateway.resolveInstrument(trimmed).mapCatching { resolution ->
            val candidate = selectCandidate(
                resolution = resolution,
                symbol = trimmed,
                existingInstrument = existingInstrument,
                marketZoneId = marketZoneId
            ) ?: throw IllegalStateException("No IB listing found for $trimmed")
            val identity = mergeIdentity(
                existing = existingInstrument,
                candidate = candidate,
                symbol = trimmed
            ) ?: throw IllegalStateException("IB listing for $trimmed has no contract details")
            Outcome(
                identity = identity,
                companyName = candidate.companyName,
                listingLabel = InstrumentListingCandidates.listingLabel(candidate),
                orderSizeRules = identity.orderSizeRules()
            )
        }
    }

    fun selectCandidate(
        resolution: InstrumentResolution,
        symbol: String,
        existingInstrument: InstrumentIdentity?,
        marketZoneId: String? = null
    ): ResolvedInstrument? {
        val candidates = InstrumentListingCandidates.prepareForUi(resolution.candidates)
        if (candidates.isEmpty()) return null
        existingInstrument?.dedupeKey()?.let { savedKey ->
            candidates.firstOrNull { it.identity?.dedupeKey() == savedKey }?.let { return it }
        }
        marketZoneId?.let { zoneId ->
            candidates.firstOrNull { candidate ->
                candidate.marketZoneId == zoneId &&
                    InstrumentListingCandidates.matchesSymbol(candidate, symbol)
            }?.let { return it }
        }
        resolution.singleOrNull()?.let { return it }
        return candidates.firstOrNull { it.source == MarketSource.IB } ?: candidates.firstOrNull()
    }

    fun mergeIdentity(
        existing: InstrumentIdentity?,
        candidate: ResolvedInstrument,
        symbol: String
    ): InstrumentIdentity? {
        val ibIdentity = candidate.identity ?: return null
        val base = existing ?: ibIdentity
        return base.copy(
            symbol = ibIdentity.symbol.ifBlank { SymbolMarkets.normalizeSymbol(symbol) },
            exchange = ibIdentity.exchange.ifBlank { base.exchange },
            primaryExch = ibIdentity.primaryExch ?: base.primaryExch,
            currency = ibIdentity.currency.ifBlank { base.currency },
            conId = ibIdentity.conId ?: base.conId,
            localSymbol = ibIdentity.localSymbol ?: base.localSymbol,
            tradingClass = ibIdentity.tradingClass ?: base.tradingClass,
            minOrderSize = ibIdentity.minOrderSize,
            orderSizeIncrement = ibIdentity.orderSizeIncrement,
            minPriceTick = ibIdentity.minPriceTick ?: base.minPriceTick,
            marketRuleId = ibIdentity.marketRuleId ?: base.marketRuleId,
            priceIncrements = ibIdentity.priceIncrements.ifEmpty { base.priceIncrements }
        )
    }

    fun lotSizeLabel(rules: InstrumentOrderSizeRules): String =
        if (rules.isUnitLot()) {
            "1 share minimum"
        } else {
            "Min ${rules.minOrderSize}, step ${rules.orderSizeIncrement}"
        }

    fun tickRuleLabel(identity: InstrumentIdentity): String {
        val bands = identity.priceIncrements
        if (bands.isNotEmpty()) {
            val active = bands.lastOrNull()?.let { "≥${it.lowEdge} → ${it.increment}" }
            return "${bands.size} price band(s)${active?.let { " ($it)" }.orEmpty()}"
        }
        identity.minPriceTick?.let { return "Min tick $it" }
        return "Tick rules not loaded"
    }
}
