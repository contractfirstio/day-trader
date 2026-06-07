package daytrader.data

import daytrader.domain.ReversalScoreAlignmentBadge
import daytrader.domain.ReversalScoreInputs
import daytrader.domain.ReversalScoreCalculator
import daytrader.domain.ContextualAlignmentEvaluator
import daytrader.domain.ReversalScoreInsightGenerator
import daytrader.domain.MacroTrendState
import daytrader.domain.ReversalScoreHistoricalSnapshot
import daytrader.domain.ReversalScoreLiveSnapshot
import daytrader.domain.ReversalScoreMacroVolSnapshot
import daytrader.domain.ReversalScoreResult
import daytrader.domain.ReversalScoreSymbolSnapshot
import daytrader.domain.ReversalScoreYieldCurveSnapshot
import daytrader.domain.SpyRegimeSnapshot
import daytrader.domain.SpyRegimeEvaluator
import daytrader.domain.WatchlistEntry
import daytrader.diagnostics.ReversalScoreLog
import daytrader.gateway.BrokerGateway
import daytrader.gateway.GatewayConnectionState
import kotlinx.coroutines.delay

data class ReversalScoreProgress(
    val stage: ReversalScoreCalculationStage,
    val completed: Int = 0,
    val total: Int = 0,
    val symbol: String = "",
    val entryId: String = ""
)

enum class ReversalScoreCalculationStage {
    MACRO_VOL,
    YIELD_CURVE,
    SPY_REGIME,
    SYMBOLS
}

data class ReversalScoreEntryResult(
    val entryId: String,
    val symbol: String,
    val result: ReversalScoreResult? = null,
    val errorMessage: String? = null
) {
    val score: Int? get() = result?.compositeScore
    val rawComposite: Double? get() = result?.rawComposite
    val alignmentBadge: ReversalScoreAlignmentBadge?
        get() = result?.contextBadge?.let { label ->
            ReversalScoreAlignmentBadge.entries.firstOrNull { it.label == label }
        }
}

data class ReversalScoreBatchResult(
    val calculatedAtEpochMs: Long,
    val macroTrendState: MacroTrendState? = null,
    val spyLastPrice: Double? = null,
    val spySma200: Double? = null,
    val entryResults: List<ReversalScoreEntryResult>
) {
    val failedCount: Int = entryResults.count { it.score == null }
}

class ReversalScoreService(
    private val macroYieldProvider: MacroYieldDataProvider = StubMacroYieldDataProvider(),
    private val batchSize: Int = DEFAULT_BATCH_SIZE,
    private val batchDelayMs: Long = DEFAULT_BATCH_DELAY_MS
) {
    suspend fun calculateScores(
        entries: List<WatchlistEntry>,
        gateway: BrokerGateway,
        onProgress: (ReversalScoreProgress) -> Unit = {}
    ): ReversalScoreBatchResult {
        ReversalScoreLog.batchStarted(
            brokerId = gateway.brokerId,
            connectionState = gateway.connectionState.value,
            symbolCount = entries.size,
            symbols = entries.map { it.symbol }
        )
        if (gateway.connectionState.value != GatewayConnectionState.Connected) {
            val message = "Broker not connected (state=${gateway.connectionState.value})"
            ReversalScoreLog.batchFailedEarly(message, gateway.brokerId, entries.size)
            return failureBatch(entries = entries, message = message)
        }

        ReversalScoreLog.macroVolFetchStarted(gateway.brokerId)
        onProgress(ReversalScoreProgress(stage = ReversalScoreCalculationStage.MACRO_VOL))
        val macroVolResult = gateway.fetchReversalScoreMacroVolatility()
        ReversalScoreLog.macroVolFetchFinished(gateway.brokerId, macroVolResult)
        if (macroVolResult.isFailure) {
            val message = macroVolResult.exceptionOrNull()?.let { error ->
                "${error.message ?: error.toString()} (${error::class.simpleName})"
            } ?: "Macro volatility fetch failed"
            ReversalScoreLog.batchFailedEarly(message, gateway.brokerId, entries.size)
            return failureBatch(entries = entries, message = message)
        }

        val yieldSource = macroYieldProvider::class.simpleName ?: "MacroYieldDataProvider"
        ReversalScoreLog.yieldCurveFetchStarted(yieldSource)
        onProgress(ReversalScoreProgress(stage = ReversalScoreCalculationStage.YIELD_CURVE))
        val yieldResult = macroYieldProvider.fetchYieldCurveSnapshot()
        ReversalScoreLog.yieldCurveFetchFinished(yieldSource, yieldResult)
        if (yieldResult.isFailure) {
            val message = yieldResult.exceptionOrNull()?.let { error ->
                "${error.message ?: error.toString()} (${error::class.simpleName})"
            } ?: "Yield curve fetch failed"
            ReversalScoreLog.batchFailedEarly(message, gateway.brokerId, entries.size)
            return failureBatch(entries = entries, message = message)
        }

        val macroVol = macroVolResult.getOrThrow()
        val yieldCurve = yieldResult.getOrThrow()

        ReversalScoreLog.logLine("spy_regime_fetch_started")
        onProgress(ReversalScoreProgress(stage = ReversalScoreCalculationStage.SPY_REGIME))
        val spyRegimeResult = gateway.fetchSpyRegimeSnapshot()
        if (spyRegimeResult.isFailure) {
            val message = spyRegimeResult.exceptionOrNull()?.let { error ->
                "${error.message ?: error.toString()} (${error::class.simpleName})"
            } ?: "SPY regime fetch failed"
            ReversalScoreLog.batchFailedEarly(message, gateway.brokerId, entries.size)
            return failureBatch(entries = entries, message = message)
        }
        val spyRegime = spyRegimeResult.getOrThrow()
        val macroTrend = spyRegime.macroTrendState()
        ReversalScoreLog.logLine(
            "spy_regime_fetch_succeeded last=${spyRegime.lastPrice} sma200=${spyRegime.sma200} trend=${macroTrend?.name ?: "NEUTRAL"}"
        )

        val results = mutableListOf<ReversalScoreEntryResult>()

        entries.forEachIndexed { index, entry ->
            onProgress(
                ReversalScoreProgress(
                    stage = ReversalScoreCalculationStage.SYMBOLS,
                    completed = index + 1,
                    total = entries.size,
                    symbol = entry.symbol,
                    entryId = entry.id
                )
            )
            ReversalScoreLog.symbolFetchStarted(entry.symbol, entry.id, gateway.brokerId)
            val snapshotResult = gateway.fetchReversalScoreSymbolSnapshot(entry.symbol, entry.instrument)
            ReversalScoreLog.symbolFetchFinished(entry.symbol, entry.id, gateway.brokerId, snapshotResult)
            val entryResult = snapshotResult.fold(
                onSuccess = { snapshot ->
                    runCatching {
                        val inputs = ReversalScoreInputs(
                            symbol = entry.symbol,
                            symbolSnapshot = snapshot,
                            macroVol = macroVol,
                            yieldCurve = yieldCurve
                        )
                        ReversalScoreCalculator.compute(inputs)
                    }.fold(
                        onSuccess = { computed ->
                            val badge = ContextualAlignmentEvaluator.badgeLabel(
                                computed.compositeScore,
                                macroTrend
                            )
                            val enriched = ReversalScoreInsightGenerator.enrich(
                                base = computed,
                                macroState = macroTrend,
                                contextBadge = badge
                            )
                            ReversalScoreLog.symbolComputeSucceeded(entry.symbol, entry.id, enriched)
                            ReversalScoreEntryResult(
                                entryId = entry.id,
                                symbol = entry.symbol,
                                result = enriched,
                                errorMessage = null
                            )
                        },
                        onFailure = { error ->
                            ReversalScoreLog.symbolComputeFailed(entry.symbol, entry.id, error)
                            ReversalScoreEntryResult(
                                entryId = entry.id,
                                symbol = entry.symbol,
                                result = null,
                                errorMessage = error.message ?: error.toString()
                            )
                        }
                    )
                },
                onFailure = { error ->
                    ReversalScoreEntryResult(
                        entryId = entry.id,
                        symbol = entry.symbol,
                        result = null,
                        errorMessage = error.message ?: error.toString()
                    )
                }
            )
            results.add(entryResult)
            if ((index + 1) % batchSize == 0 && index + 1 < entries.size) {
                delay(batchDelayMs)
            }
        }

        val failures = results.filter { it.score == null }.map { it.symbol to (it.errorMessage ?: "unknown") }
        ReversalScoreLog.batchFinished(
            brokerId = gateway.brokerId,
            total = results.size,
            succeeded = results.count { it.score != null },
            failed = results.count { it.score == null },
            failures = failures
        )

        return ReversalScoreBatchResult(
            calculatedAtEpochMs = System.currentTimeMillis(),
            macroTrendState = macroTrend,
            spyLastPrice = spyRegime.lastPrice,
            spySma200 = spyRegime.sma200,
            entryResults = results
        )
    }

    private fun failureBatch(entries: List<WatchlistEntry>, message: String): ReversalScoreBatchResult =
        ReversalScoreBatchResult(
            calculatedAtEpochMs = System.currentTimeMillis(),
            entryResults = entries.map { entry ->
                ReversalScoreEntryResult(
                    entryId = entry.id,
                    symbol = entry.symbol,
                    result = null,
                    errorMessage = message
                )
            }
        )

    companion object {
        /** Synthesizes a symbol snapshot from a daily close when live feeds are unavailable. */
        fun syntheticSymbolSnapshot(lastPrice: Double): ReversalScoreSymbolSnapshot {
            val closes = List(30) { index -> lastPrice * (1.0 - (29 - index) * 0.002) }
            val volumes = List(30) { index -> 1_000_000.0 + index * 10_000.0 }
            val ivHistory = List(60) { index -> 0.18 + (index % 8) * 0.01 }
            return ReversalScoreSymbolSnapshot(
                live = ReversalScoreLiveSnapshot(
                    lastPrice = lastPrice,
                    volume = volumes.last(),
                    impliedVolatility = ivHistory.last()
                ),
                historical = ReversalScoreHistoricalSnapshot(
                    dailyCloses = closes,
                    dailyVolumes = volumes,
                    historicalIvValues = ivHistory
                )
            )
        }

        fun syntheticMacroVolSnapshot(): ReversalScoreMacroVolSnapshot =
            ReversalScoreMacroVolSnapshot(
                vix = 18.0,
                vix1d = 20.0,
                vvix = 95.0,
                vixHistory = List(60) { 16.0 + (it % 6) },
                vix1dHistory = List(60) { 18.0 + (it % 5) },
                vvixHistory = List(60) { 90.0 + (it % 7) }
            )

        fun syntheticSpyRegimeSnapshot(lastPrice: Double): Result<SpyRegimeSnapshot> {
            val closes = List(SpyRegimeEvaluator.SMA_WINDOW) { index ->
                lastPrice * (1.0 - (SpyRegimeEvaluator.SMA_WINDOW - index) * 0.0004)
            }
            return SpyRegimeEvaluator.buildSnapshot(lastPrice, closes)
        }

        const val DEFAULT_BATCH_SIZE = 3
        const val DEFAULT_BATCH_DELAY_MS = 2_000L
    }
}
