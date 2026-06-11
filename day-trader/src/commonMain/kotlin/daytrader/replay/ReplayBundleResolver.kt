package daytrader.replay

import daytrader.broker.SymbolMarkets
import daytrader.domain.StrategyDeployment

/** Lightweight catalog row used to pick a hybrid capture for a deployment at replay session start. */
data class ReplayCaptureRef(
    val directoryPath: String,
    val deploymentId: String,
    val symbol: String?,
    val sessionDate: String?,
    val sessionStartedEpochMs: Long?
)

object ReplayBundleResolver {
    /**
     * Picks the best on-disk capture for [deployment]: same deployment id when present,
     * otherwise the newest capture for the deployment symbol.
     */
    fun selectCapture(
        deployment: StrategyDeployment,
        captures: List<ReplayCaptureRef>
    ): ReplayCaptureRef? {
        if (captures.isEmpty()) return null
        val deploymentSymbol = SymbolMarkets.normalizeSymbol(deployment.symbol)
        val matching = captures.filter { ref ->
            ref.symbol?.let(SymbolMarkets::normalizeSymbol) == deploymentSymbol
        }
        if (matching.isEmpty()) return null
        return matching.find { it.deploymentId == deployment.id }
            ?: matching.maxByOrNull { it.sessionStartedEpochMs ?: Long.MIN_VALUE }
    }
}
