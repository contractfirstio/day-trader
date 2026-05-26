package daytrader.domain

import daytrader.broker.SymbolMarkets

object InstrumentListingCandidates {
    private val supportedMarketZones = setOf(
        RthMarketSessions.US.zoneId,
        RthMarketSessions.EUR.zoneId,
        RthMarketSessions.HK.zoneId
    )

    fun prepareForUi(candidates: Collection<ResolvedInstrument>): List<ResolvedInstrument> {
        val filtered = candidates.filter { it.marketZoneId in supportedMarketZones }
        val source = if (filtered.isNotEmpty()) filtered else candidates.toList()
        return source.sortedWith(
            compareBy(
                { it.currencyCode.uppercase() },
                { listingVenue(it).uppercase() }
            )
        )
    }

    fun listingVenue(candidate: ResolvedInstrument): String {
        val identity = candidate.identity
        return identity?.primaryExch?.takeIf { it.isNotBlank() }
            ?: identity?.exchange?.takeIf { it.isNotBlank() && !it.equals("SMART", ignoreCase = true) }
            ?: candidate.venueLabel.substringBefore("·").trim()
    }

    fun listingLabel(candidate: ResolvedInstrument): String =
        "${listingVenue(candidate)} · ${candidate.currencyCode.uppercase()}"

    fun matchesSymbol(candidate: ResolvedInstrument, symbol: String): Boolean =
        candidate.identity?.symbol?.let { SymbolMarkets.symbolsMatch(symbol, it) } != false
}
