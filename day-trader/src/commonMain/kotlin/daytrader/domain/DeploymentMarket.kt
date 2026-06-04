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
    val companyName: String? = null,
    /** Selected or suggested IB contract; null for legacy heuristic-only rows. */
    val identity: InstrumentIdentity? = null
)

object DeploymentMarket {
    fun effectiveInstrument(deployment: StrategyDeployment): InstrumentIdentity =
        deployment.instrument
            ?: InstrumentIdentity.heuristic(deployment.symbol, deployment.currencyCode)

    fun effectiveZoneId(deployment: StrategyDeployment): String =
        deployment.marketZoneId ?: SymbolMarkets.zoneId(deployment.symbol)

    /** Trading session ISO date in the deployment's market zone (matches auto-start and IB bar day). */
    fun sessionDateIso(
        deployment: StrategyDeployment,
        nowEpochMillis: Long = System.currentTimeMillis()
    ): String = TouchTurnLogic.sessionDateIsoInMarketZone(effectiveZoneId(deployment), nowEpochMillis)

    fun effectiveCurrencyCode(deployment: StrategyDeployment): String =
        deployment.currencyCode.ifBlank {
            deployment.instrument?.currency ?: currencyForZone(effectiveZoneId(deployment))
        }

    fun currencyForZone(marketZoneId: String): String = when (marketZoneId) {
        RthMarketSessions.HK.zoneId -> "HKD"
        RthMarketSessions.EUR.zoneId -> "GBP"
        else -> "USD"
    }

    fun sessionForZone(marketZoneId: String): RthMarketSession =
        RthMarketSessions.forZoneId(marketZoneId)

    fun sessionDisplayLabel(session: RthMarketSession): String = session.label

    /** Market zone for a closed/in-progress session (frozen run record when available). */
    fun sessionMarketZoneId(session: StrategySession, deployment: StrategyDeployment): String {
        session.touchTurnRunRecord?.marketInputs?.marketZoneId?.let { return it }
        if (session.status == SessionStatus.IN_PROGRESS) {
            deployment.touchTurnSession?.marketZoneId?.let { return it }
        }
        return effectiveZoneId(deployment)
    }

    fun zonesMatch(filterZoneId: String, candidateZoneId: String): Boolean =
        RthMarketSessions.forZoneId(filterZoneId).zoneId ==
            RthMarketSessions.forZoneId(candidateZoneId).zoneId

    fun sessionMatchesMarketFilter(
        session: StrategySession,
        deployment: StrategyDeployment,
        filterZoneId: String?
    ): Boolean = filterZoneId == null ||
        zonesMatch(filterZoneId, sessionMarketZoneId(session, deployment))

    fun fromSymbolHeuristic(symbol: String): ResolvedInstrument {
        val zoneId = SymbolMarkets.zoneId(symbol)
        val currency = currencyForZone(zoneId)
        val session = RthMarketSessions.forZoneId(zoneId)
        return ResolvedInstrument(
            marketZoneId = zoneId,
            currencyCode = currency,
            venueLabel = "${sessionDisplayLabel(session)} · $currency (estimated)",
            source = MarketSource.SYMBOL_INFERRED,
            companyName = EmulatorSymbolLookup.companyName(symbol),
            identity = InstrumentIdentity.heuristic(symbol, currency)
        )
    }
}
