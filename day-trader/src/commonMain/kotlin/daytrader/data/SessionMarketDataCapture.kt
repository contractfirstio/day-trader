package daytrader.data

import daytrader.broker.SymbolMarkets
import daytrader.domain.InstrumentIdentity

/**
 * Tracks IB market-data recording targets that outlive a stopped Touch Turn session.
 * While active, [daytrader.diagnostics.SessionPriceLog] continues appending to
 * `sessions/{deploymentId}/{sessionId}/prices.jsonl` and streaming subscriptions stay up.
 *
 * The engine retains capture after early session stops (e.g. no-trade guards) until
 * RTH open + [daytrader.domain.TouchTurnRuleConfig.stopAfterOpenMinutes] (default 90),
 * so replay has post-decision quotes for alternate rule scenarios.
 */
object SessionMarketDataCapture {
    data class Target(
        val deploymentId: String,
        val sessionId: String,
        val symbol: String,
        val instrument: InstrumentIdentity?
    )

    private val activeByDeployment = linkedMapOf<String, Target>()

    fun start(
        deploymentId: String,
        sessionId: String,
        symbol: String,
        instrument: InstrumentIdentity?
    ): Target {
        val target = Target(
            deploymentId = deploymentId,
            sessionId = sessionId,
            symbol = SymbolMarkets.normalizeSymbol(symbol),
            instrument = instrument
        )
        synchronized(activeByDeployment) {
            activeByDeployment[deploymentId] = target
        }
        return target
    }

    fun activeForDeployment(deploymentId: String): Target? =
        synchronized(activeByDeployment) { activeByDeployment[deploymentId] }

    fun activeTargets(): List<Target> =
        synchronized(activeByDeployment) { activeByDeployment.values.toList() }

    fun targetsForSymbol(symbol: String): List<Target> {
        val norm = SymbolMarkets.normalizeSymbol(symbol)
        return activeTargets().filter { SymbolMarkets.symbolsMatch(it.symbol, norm) }
    }

    fun stop(deploymentId: String): Target? =
        synchronized(activeByDeployment) { activeByDeployment.remove(deploymentId) }

    fun stopAll(): List<Target> =
        synchronized(activeByDeployment) {
            val removed = activeByDeployment.values.toList()
            activeByDeployment.clear()
            removed
        }
}
