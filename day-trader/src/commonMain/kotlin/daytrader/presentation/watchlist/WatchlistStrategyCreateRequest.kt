package daytrader.presentation.watchlist

import daytrader.domain.InstrumentIdentity
import daytrader.domain.StrategyType
import daytrader.presentation.strategies.StrategyDeploymentAddPrefill

data class WatchlistStrategyCreateRequest(
    val entryId: String,
    val symbol: String,
    val marketZoneId: String,
    val currencyCode: String,
    val companyName: String?,
    val instrument: InstrumentIdentity?,
    val strategyType: StrategyType
) {
    fun toAddPrefill(): StrategyDeploymentAddPrefill = StrategyDeploymentAddPrefill(
        symbol = symbol,
        marketZoneId = marketZoneId,
        currencyCode = currencyCode,
        companyName = companyName,
        instrument = instrument,
        strategyType = strategyType
    )
}
