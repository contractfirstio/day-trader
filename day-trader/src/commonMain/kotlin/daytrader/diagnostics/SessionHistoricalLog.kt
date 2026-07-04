package daytrader.diagnostics

import daytrader.data.persistence.AppDataFiles
import daytrader.domain.OhlcBar
import daytrader.domain.TouchTurnSignalContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Per-session historical payloads for Touch Turn replay (bootstrap + closed-bar refetch + 5m bars).
 *
 * Files: `sessions/{deploymentId}/{sessionId}/historical.jsonl`
 *
 * Disabled when `DAY_TRADER_SESSION_HISTORICAL_LOGS=false`.
 */
object SessionHistoricalLog {
    private val json = Json { encodeDefaults = false }

    private val enabled: Boolean
        get() = System.getenv("DAY_TRADER_SESSION_HISTORICAL_LOGS")
            ?.equals("false", ignoreCase = true) != true

    fun recordSignalContext(
        deploymentId: String,
        sessionId: String?,
        symbol: String,
        context: TouchTurnSignalContext,
        isClosedBarRefetch: Boolean,
        attempt: Int? = null,
        validation: String? = null
    ) {
        if (!enabled || sessionId == null) return
        val stamp = LogTimestamps.now()
        val line = json.encodeToString(
            SessionHistoricalLine.serializer(),
            SessionHistoricalLine(
                at = stamp.at,
                epochMs = stamp.epochMs,
                kind = "signal_context",
                symbol = symbol,
                isClosedBarRefetch = isClosedBarRefetch,
                attempt = attempt,
                validation = validation,
                context = context
            )
        )
        val path = AppDataFiles.sessionHistoricalLogFileName(deploymentId, sessionId)
        DiagnosticJsonlWriter.appendLine(path, line)
    }

    fun recordFiveMinuteBar(
        deploymentId: String,
        sessionId: String?,
        symbol: String,
        bar: OhlcBar,
        sweepPrice: Double
    ) {
        if (!enabled || sessionId == null) return
        val stamp = LogTimestamps.now()
        val line = json.encodeToString(
            SessionHistoricalLine.serializer(),
            SessionHistoricalLine(
                at = stamp.at,
                epochMs = stamp.epochMs,
                kind = "five_minute_bar",
                symbol = symbol,
                isClosedBarRefetch = false,
                bar = bar,
                sweepPrice = sweepPrice
            )
        )
        val path = AppDataFiles.sessionHistoricalLogFileName(deploymentId, sessionId)
        DiagnosticJsonlWriter.appendLine(path, line)
    }
}

@Serializable
internal data class SessionHistoricalLine(
    val at: String,
    val epochMs: Long,
    val kind: String,
    val symbol: String,
    val isClosedBarRefetch: Boolean,
    val attempt: Int? = null,
    val validation: String? = null,
    val context: TouchTurnSignalContext? = null,
    val bar: OhlcBar? = null,
    val sweepPrice: Double? = null
)
