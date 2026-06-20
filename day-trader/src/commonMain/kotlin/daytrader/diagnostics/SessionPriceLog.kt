package daytrader.diagnostics

import daytrader.broker.SymbolMarkets
import daytrader.data.SessionMarketDataCapture
import daytrader.data.persistence.AppDataFiles
import daytrader.domain.DeploymentStatus
import daytrader.domain.StrategyDeployment
import daytrader.domain.inProgressSession
import daytrader.diagnostics.LogTimestamps
import daytrader.gateway.BrokerId
import daytrader.gateway.LiveQuote
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Per-session price log: live IB quote updates that arrive over [daytrader.gateway.QueuedBrokerGateway]
 * after the broker bridge. Emulator-sourced quotes are logged separately — see [EmulatorPriceLog].
 *
 * Files: `sessions/{deploymentId}/{sessionId}/prices.jsonl`
 * (paired with `application.jsonl` in the same directory)
 *
 * Disabled when `DAY_TRADER_SESSION_PRICE_LOGS=false`.
 */
object SessionPriceLog {
    private val json = Json { encodeDefaults = false }

    private val enabled: Boolean
        get() = System.getenv("DAY_TRADER_SESSION_PRICE_LOGS")
            ?.equals("false", ignoreCase = true) != true

    private var sessionLookup: ((String) -> List<SessionPriceTarget>)? = null

    data class SessionPriceTarget(
        val deploymentId: String,
        val sessionId: String,
        val symbol: String
    )

    fun install(deploymentsProvider: () -> List<StrategyDeployment>) {
        sessionLookup = { symbol ->
            val fromRunning = deploymentsProvider()
                .asSequence()
                .filter { it.status == DeploymentStatus.RUNNING }
                .filter { SymbolMarkets.symbolsMatch(it.symbol, symbol) }
                .mapNotNull { deployment ->
                    val session = deployment.inProgressSession() ?: return@mapNotNull null
                    SessionPriceTarget(
                        deploymentId = deployment.id,
                        sessionId = session.id,
                        symbol = deployment.symbol
                    )
                }
            val fromCapture = SessionMarketDataCapture.targetsForSymbol(symbol)
                .map { capture ->
                    SessionPriceTarget(
                        deploymentId = capture.deploymentId,
                        sessionId = capture.sessionId,
                        symbol = capture.symbol
                    )
                }
            (fromRunning + fromCapture)
                .distinctBy { it.deploymentId to it.sessionId }
                .toList()
        }
    }

    fun clearInstall() {
        sessionLookup = null
    }

    /**
     * Records quote changes from [GatewayEvent.QuotesSnapshot] after the inbound bridge event is applied.
     */
    fun recordQuoteSnapshot(
        brokerId: BrokerId,
        incoming: Map<String, LiveQuote>,
        previous: Map<String, LiveQuote>
    ) {
        if (!enabled) return
        if (brokerId == BrokerId.EMULATOR) return
        val lookup = sessionLookup ?: return
        for ((normSymbol, quote) in incoming) {
            if (!quoteHasData(quote)) continue
            val prior = previous[normSymbol]
            if (prior != null && quotesEqual(prior, quote)) continue
            val targets = lookup(normSymbol)
            if (targets.isEmpty()) continue
            val stamp = LogTimestamps.now()
            val line = json.encodeToString(
                SessionPriceLine.serializer(),
                SessionPriceLine(
                    at = stamp.at,
                    epochMs = stamp.epochMs,
                    brokerId = brokerId.name,
                    symbol = normSymbol,
                    bid = quote.bid,
                    ask = quote.ask,
                    last = quote.last,
                    tickVolume = quote.tickVolume,
                    quoteEpochMillis = quote.quoteEpochMillis,
                    kind = "quote_snapshot"
                )
            )
            for (target in targets) {
                appendLine(target, line)
            }
        }
    }

    internal fun quoteHasData(quote: LiveQuote): Boolean =
        quote.bid != null || quote.ask != null || quote.last != null ||
            quote.tickVolume != null

    internal fun quotesEqual(a: LiveQuote, b: LiveQuote): Boolean =
        a.bid == b.bid && a.ask == b.ask && a.last == b.last && a.tickVolume == b.tickVolume

    private fun appendLine(target: SessionPriceTarget, line: String) {
        val path = AppDataFiles.sessionPriceLogFileName(target.deploymentId, target.sessionId)
        DiagnosticJsonlWriter.appendLine(path, line)
    }
}

@Serializable
internal data class SessionPriceLine(
    val at: String,
    val epochMs: Long,
    val brokerId: String,
    val symbol: String,
    val bid: Double? = null,
    val ask: Double? = null,
    val last: Double? = null,
    val tickVolume: Double? = null,
    val quoteEpochMillis: Long? = null,
    val kind: String = "quote_snapshot"
)
