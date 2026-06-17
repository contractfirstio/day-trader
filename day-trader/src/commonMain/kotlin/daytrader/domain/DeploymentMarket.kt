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
    fun effectiveZoneId(deployment: StrategyDeployment): String {
        deployment.marketZoneId?.trim()?.takeIf { it.isNotEmpty() }?.let { zone ->
            return RthMarketSessions.forZoneId(zone).zoneId
        }
        deployment.instrument?.let { identity ->
            return SymbolMarkets.marketZoneIdForSession(deployment.symbol, identity)
        }
        return SymbolMarkets.zoneId(deployment.symbol)
    }

    fun effectiveCurrencyCode(deployment: StrategyDeployment): String {
        val zoneId = effectiveZoneId(deployment)
        val zoneCurrency = currencyForZone(zoneId)
        val stored = deployment.currencyCode.trim().uppercase()
        val instrumentCurrency = deployment.instrument?.currency?.trim()?.uppercase()
        if (stored.isBlank()) {
            return instrumentCurrency?.takeIf { it.isNotEmpty() } ?: zoneCurrency
        }
        // London/HK deployments created with legacy USD default — align with the selected market.
        if (deployment.marketZoneId != null && stored != zoneCurrency) {
            if (instrumentCurrency == null || instrumentCurrency == stored) {
                return zoneCurrency
            }
        }
        return stored
    }

    fun effectiveInstrument(deployment: StrategyDeployment): InstrumentIdentity {
        val currency = effectiveCurrencyCode(deployment)
        val base = deployment.instrument
            ?: InstrumentIdentity.heuristic(deployment.symbol, currency)
        val zoneId = effectiveZoneId(deployment)
        var adjusted = if (!base.currency.equals(currency, ignoreCase = true)) {
            base.copy(currency = currency)
        } else {
            base
        }
        if (adjusted.primaryExch.isNullOrBlank()) {
            adjusted = when (zoneId) {
                RthMarketSessions.EUR.zoneId -> adjusted.copy(primaryExch = "LSE")
                RthMarketSessions.HK.zoneId -> adjusted.copy(
                    exchange = "SEHK",
                    primaryExch = "SEHK"
                )
                else -> adjusted
            }
        }
        return adjusted
    }

    /** Trading session ISO date in the deployment's market zone (matches auto-start and IB bar day). */
    fun sessionDateIso(
        deployment: StrategyDeployment,
        nowEpochMillis: Long = System.currentTimeMillis()
    ): String = TouchTurnLogic.sessionDateIsoInMarketZone(effectiveZoneId(deployment), nowEpochMillis)

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

    fun deploymentMatchesAnyMarketZoneFilter(
        deployment: StrategyDeployment,
        filterZoneIds: Set<String>
    ): Boolean {
        if (filterZoneIds.isEmpty()) return false
        val deploymentZoneId = effectiveZoneId(deployment)
        return filterZoneIds.any { zonesMatch(it, deploymentZoneId) }
    }

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
