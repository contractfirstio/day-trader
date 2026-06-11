package daytrader.data

import daytrader.broker.SymbolMarkets
import daytrader.domain.DeploymentStatus
import daytrader.domain.StrategyDeployment

/** When to hold or release IB streaming market-data subscriptions per symbol. */
object LiveMarketDataLifecycle {
    fun anyRunningDeploymentNeedsQuotes(
        symbol: String,
        deployments: List<StrategyDeployment>
    ): Boolean =
        deployments.any { deployment ->
            deployment.status == DeploymentStatus.RUNNING &&
                SymbolMarkets.symbolsMatch(deployment.symbol, symbol)
        }

    fun anyDeploymentNeedsQuotes(
        symbol: String,
        deployments: List<StrategyDeployment>
    ): Boolean =
        anyRunningDeploymentNeedsQuotes(symbol, deployments) ||
            SessionMarketDataCapture.targetsForSymbol(symbol).isNotEmpty()
}
