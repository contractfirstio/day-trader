package daytrader.diagnostics

import daytrader.data.persistence.AppDataFiles
import daytrader.data.persistence.JsonFileStore
import daytrader.domain.ReversalScoreComponents
import daytrader.domain.ReversalScoreMacroVolSnapshot
import daytrader.domain.ReversalScoreResult
import daytrader.domain.ReversalScoreSymbolSnapshot
import daytrader.domain.ReversalScoreYieldCurveSnapshot
import daytrader.gateway.BrokerId
import daytrader.gateway.GatewayConnectionState
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Reversal score batch diagnostics — console + `watchlist/reversal-score.jsonl`.
 *
 * Enabled by default. Set `DAY_TRADER_REVERSAL_SCORE_LOG=false` to disable.
 */
object ReversalScoreLog {
    private val json = Json { encodeDefaults = false }

    private val enabled: Boolean
        get() = System.getenv("DAY_TRADER_REVERSAL_SCORE_LOG")
            ?.equals("false", ignoreCase = true) != true

    fun batchStarted(
        brokerId: BrokerId?,
        connectionState: GatewayConnectionState,
        symbolCount: Int,
        symbols: List<String>
    ) {
        event(
            type = "batch_started",
            brokerId = brokerId,
            details = buildMap {
                put("connectionState", connectionState.toString())
                put("symbolCount", symbolCount.toString())
                put("symbols", symbols.joinToString(","))
            }
        )
    }

    fun batchFailedEarly(reason: String, brokerId: BrokerId?, symbolCount: Int) {
        event(
            type = "batch_failed_early",
            brokerId = brokerId,
            details = mapOf(
                "reason" to reason,
                "symbolCount" to symbolCount.toString()
            )
        )
    }

    fun macroVolFetchStarted(brokerId: BrokerId?) {
        event(type = "macro_vol_fetch_started", brokerId = brokerId)
    }

    fun macroVolFetchFinished(brokerId: BrokerId?, result: Result<ReversalScoreMacroVolSnapshot>) {
        result.fold(
            onSuccess = { macro ->
                event(
                    type = "macro_vol_fetch_succeeded",
                    brokerId = brokerId,
                    details = buildMap {
                        put("vix", macro.vix.toString())
                        put("vix1d", macro.vix1d?.toString() ?: "null")
                        put("vvix", macro.vvix?.toString() ?: "null")
                        put("vixHistoryCount", macro.vixHistory.size.toString())
                        put("vix1dHistoryCount", macro.vix1dHistory.size.toString())
                        put("vvixHistoryCount", macro.vvixHistory.size.toString())
                        put("vixHistoryLast", macro.vixHistory.lastOrNull()?.toString() ?: "null")
                    }
                )
            },
            onFailure = { error ->
                event(
                    type = "macro_vol_fetch_failed",
                    brokerId = brokerId,
                    details = mapOf(
                        "error" to (error.message ?: error.toString()),
                        "errorType" to error::class.simpleName.orEmpty()
                    )
                )
            }
        )
    }

    fun yieldCurveFetchStarted(source: String) {
        event(
            type = "yield_curve_fetch_started",
            details = mapOf("source" to source)
        )
    }

    fun yieldCurveFetchFinished(source: String, result: Result<ReversalScoreYieldCurveSnapshot>) {
        result.fold(
            onSuccess = { yield ->
                event(
                    type = "yield_curve_fetch_succeeded",
                    details = buildMap {
                        put("source", source)
                        put("tenYearYield", yield.tenYearYield.toString())
                        put("twoYearYield", yield.twoYearYield.toString())
                        put("spread", yield.spread.toString())
                        put("spreadHistoryCount", yield.spreadHistory.size.toString())
                        put("spreadHistoryLast", yield.spreadHistory.lastOrNull()?.toString() ?: "null")
                    }
                )
            },
            onFailure = { error ->
                event(
                    type = "yield_curve_fetch_failed",
                    details = mapOf(
                        "source" to source,
                        "error" to (error.message ?: error.toString()),
                        "errorType" to error::class.simpleName.orEmpty()
                    )
                )
            }
        )
    }

    fun symbolFetchStarted(symbol: String, entryId: String, brokerId: BrokerId?) {
        event(
            type = "symbol_fetch_started",
            brokerId = brokerId,
            symbol = symbol,
            details = mapOf("entryId" to entryId)
        )
    }

    fun symbolFetchFinished(
        symbol: String,
        entryId: String,
        brokerId: BrokerId?,
        result: Result<ReversalScoreSymbolSnapshot>
    ) {
        result.fold(
            onSuccess = { snapshot ->
                event(
                    type = "symbol_fetch_succeeded",
                    brokerId = brokerId,
                    symbol = symbol,
                    details = snapshotDetails(snapshot) + mapOf("entryId" to entryId)
                )
            },
            onFailure = { error ->
                event(
                    type = "symbol_fetch_failed",
                    brokerId = brokerId,
                    symbol = symbol,
                    details = mapOf(
                        "entryId" to entryId,
                        "error" to (error.message ?: error.toString()),
                        "errorType" to error::class.simpleName.orEmpty()
                    )
                )
            }
        )
    }

    fun symbolComputeSucceeded(
        symbol: String,
        entryId: String,
        result: ReversalScoreResult
    ) {
        event(
            type = "symbol_compute_succeeded",
            symbol = symbol,
            details = buildMap {
                put("entryId", entryId)
                put("score", result.compositeScore.toString())
                put("rawComposite", result.rawComposite.toString())
                putAll(componentDetails(result.components))
            }
        )
    }

    fun symbolComputeFailed(symbol: String, entryId: String, error: Throwable) {
        event(
            type = "symbol_compute_failed",
            symbol = symbol,
            details = mapOf(
                "entryId" to entryId,
                "error" to (error.message ?: error.toString()),
                "errorType" to error::class.simpleName.orEmpty()
            )
        )
    }

    fun batchFinished(
        brokerId: BrokerId?,
        total: Int,
        succeeded: Int,
        failed: Int,
        failures: List<Pair<String, String>>
    ) {
        event(
            type = "batch_finished",
            brokerId = brokerId,
            details = buildMap {
                put("total", total.toString())
                put("succeeded", succeeded.toString())
                put("failed", failed.toString())
                put(
                    "failures",
                    failures.joinToString(";") { (symbol, message) -> "$symbol=$message" }
                        .ifBlank { "none" }
                )
            }
        )
    }

    fun ibSymbolStage(symbol: String, gatewayRequestId: Long, stage: String, detail: String = "") {
        line("ib symbol=$symbol requestId=$gatewayRequestId stage=$stage${if (detail.isNotBlank()) " $detail" else ""}")
    }

    fun logLine(message: String) = line(message)

    fun ibSymbolFailed(symbol: String, gatewayRequestId: Long, error: Throwable) {
        event(
            type = "ib_symbol_failed",
            symbol = symbol,
            details = mapOf(
                "gatewayRequestId" to gatewayRequestId.toString(),
                "error" to (error.message ?: error.toString()),
                "errorType" to error::class.simpleName.orEmpty()
            )
        )
    }

    fun ibSymbolDelivered(symbol: String, gatewayRequestId: Long, snapshot: ReversalScoreSymbolSnapshot) {
        event(
            type = "ib_symbol_delivered",
            symbol = symbol,
            details = snapshotDetails(snapshot) + mapOf("gatewayRequestId" to gatewayRequestId.toString())
        )
    }

    fun ibMacroStage(gatewayRequestId: Long, stage: String, detail: String = "") {
        line("ib macro requestId=$gatewayRequestId stage=$stage${if (detail.isNotBlank()) " $detail" else ""}")
    }

    fun ibMacroFailed(gatewayRequestId: Long, error: Throwable) {
        event(
            type = "ib_macro_failed",
            details = mapOf(
                "gatewayRequestId" to gatewayRequestId.toString(),
                "error" to (error.message ?: error.toString()),
                "errorType" to error::class.simpleName.orEmpty()
            )
        )
    }

    fun ibMacroDelivered(gatewayRequestId: Long, macro: ReversalScoreMacroVolSnapshot) {
        event(
            type = "ib_macro_delivered",
            brokerId = BrokerId.INTERACTIVE_BROKERS,
            details = buildMap {
                put("gatewayRequestId", gatewayRequestId.toString())
                put("vix", macro.vix.toString())
                put("vix1d", macro.vix1d?.toString() ?: "null")
                put("vvix", macro.vvix?.toString() ?: "null")
                put("vixHistoryCount", macro.vixHistory.size.toString())
                put("vix1dHistoryCount", macro.vix1dHistory.size.toString())
                put("vvixHistoryCount", macro.vvixHistory.size.toString())
            }
        )
    }

    fun fredFetchStarted(hasApiKey: Boolean) {
        event(
            type = "fred_fetch_started",
            details = mapOf("hasApiKey" to hasApiKey.toString())
        )
    }

    fun fredUsingStub(reason: String) {
        event(type = "fred_using_stub", details = mapOf("reason" to reason))
    }

    fun fredSeriesFetched(seriesId: String, observationCount: Int, lastDate: String?, lastValue: Double?) {
        event(
            type = "fred_series_fetched",
            details = buildMap {
                put("seriesId", seriesId)
                put("observationCount", observationCount.toString())
                put("lastDate", lastDate ?: "null")
                put("lastValue", lastValue?.toString() ?: "null")
            }
        )
    }

    fun fredFetchFailed(error: Throwable, httpStatus: Int? = null, responseBodyPreview: String? = null) {
        event(
            type = "fred_fetch_failed",
            details = buildMap {
                put("error", error.message ?: error.toString())
                put("errorType", error::class.simpleName.orEmpty())
                httpStatus?.let { put("httpStatus", it.toString()) }
                responseBodyPreview?.let { put("responseBodyPreview", it.take(500)) }
            }
        )
    }

    fun gatewayRequestFailed(kind: String, symbol: String?, requestId: Long, error: Throwable) {
        event(
            type = "gateway_request_failed",
            symbol = symbol,
            details = mapOf(
                "kind" to kind,
                "requestId" to requestId.toString(),
                "error" to (error.message ?: error.toString()),
                "errorType" to error::class.simpleName.orEmpty()
            )
        )
    }

    private fun snapshotDetails(snapshot: ReversalScoreSymbolSnapshot): Map<String, String> =
        buildMap {
            put("lastPrice", snapshot.live.lastPrice.toString())
            put("volume", snapshot.live.volume.toString())
            put("impliedVolatility", snapshot.live.impliedVolatility?.toString() ?: "null")
            put("dailyCloseCount", snapshot.historical.dailyCloses.size.toString())
            put("dailyVolumeCount", snapshot.historical.dailyVolumes.size.toString())
            put("ivHistoryCount", snapshot.historical.historicalIvValues.size.toString())
            put("lastDailyClose", snapshot.historical.dailyCloses.lastOrNull()?.toString() ?: "null")
            put("lastDailyVolume", snapshot.historical.dailyVolumes.lastOrNull()?.toString() ?: "null")
            put("lastIvHistory", snapshot.historical.historicalIvValues.lastOrNull()?.toString() ?: "null")
        }

    private fun componentDetails(components: ReversalScoreComponents): Map<String, String> =
        mapOf(
            "priceZ" to components.priceZ.toString(),
            "ivRankZ" to components.ivRankZ.toString(),
            "rvolZ" to components.rvolZ.toString(),
            "hfMacroFearZ" to components.hfMacroFearZ.toString(),
            "structuralVixZ" to components.structuralVixZ.toString(),
            "yieldCurveZ" to components.yieldCurveZ.toString()
        )

    private fun line(message: String) {
        if (!enabled) return
        TimestampedConsoleLog.line("ReversalScore", message)
    }

    private fun event(
        type: String,
        brokerId: BrokerId? = null,
        symbol: String? = null,
        details: Map<String, String> = emptyMap()
    ) {
        if (!enabled) return
        val stamp = LogTimestamps.now()
        val summary = buildString {
            append("type=$type")
            brokerId?.let { append(" broker=${it.name}") }
            symbol?.let { append(" symbol=$it") }
            if (details.isNotEmpty()) {
                append(" ")
                append(details.entries.joinToString(" ") { (key, value) -> "$key=$value" })
            }
        }
        TimestampedConsoleLog.line("ReversalScore", summary)
        val line = json.encodeToString(
            ReversalScoreLogLine.serializer(),
            ReversalScoreLogLine(
                at = stamp.at,
                epochMs = stamp.epochMs,
                type = type,
                brokerId = brokerId?.name,
                symbol = symbol,
                details = details
            )
        )
        runCatching {
            JsonFileStore.appendReversalScoreLine(AppDataFiles.reversalScoreLogFileName(), line)
        }
    }
}

@Serializable
private data class ReversalScoreLogLine(
    val at: String,
    val epochMs: Long,
    val type: String,
    val brokerId: String? = null,
    val symbol: String? = null,
    val details: Map<String, String> = emptyMap()
)
