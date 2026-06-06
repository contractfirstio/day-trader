package daytrader.presentation.strategies

import daytrader.domain.DeploymentMarket
import daytrader.domain.InstrumentIdentity
import daytrader.domain.MarketSource
import daytrader.domain.ResolvedInstrument
import daytrader.domain.RthMarketSessions
import daytrader.domain.StrategyType

data class StrategyDeploymentAddPrefill(
    val symbol: String,
    val marketZoneId: String,
    val currencyCode: String,
    val companyName: String? = null,
    val instrument: InstrumentIdentity? = null,
    val marketSource: MarketSource = if (instrument != null) MarketSource.IB else MarketSource.USER,
    val strategyType: StrategyType? = null
) {
    fun toResolvedInstrument(): ResolvedInstrument {
        val session = RthMarketSessions.forZoneId(marketZoneId)
        return ResolvedInstrument(
            marketZoneId = marketZoneId,
            currencyCode = currencyCode,
            venueLabel = "${DeploymentMarket.sessionDisplayLabel(session)} · $currencyCode",
            source = marketSource,
            companyName = companyName?.takeIf { it.isNotBlank() },
            identity = instrument
        )
    }
}
