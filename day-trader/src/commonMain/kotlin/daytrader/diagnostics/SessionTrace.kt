package daytrader.diagnostics

import daytrader.data.persistence.AppDataFiles
import daytrader.data.persistence.JsonFileStore
import daytrader.data.persistence.TouchTurnRunPersistence
import daytrader.data.persistence.TouchTurnRunRecordRecord
import daytrader.domain.StrategyDeployment
import daytrader.domain.StrategySession
import daytrader.domain.SessionTrade
import daytrader.domain.TouchTurnRunRecord
import daytrader.domain.dedupeByExecId
import daytrader.domain.sessionRealizedPnL
import daytrader.diagnostics.LogTimestamps
import daytrader.gateway.BrokerFill
import daytrader.gateway.LiveQuote
import daytrader.platform.AppFileSystem
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.put
import kotlinx.serialization.serializer

/**
 * Append-only JSONL for post-hoc analysis (not shown in the UI).
 * Paired per session under `sessions/{deploymentId}/{sessionId}/`:
 * - [AppDataFiles.SESSION_APPLICATION_LOG] — lifecycle, decisions, and engine/UI sync (`touch_turn_state_sync`)
 * - [AppDataFiles.SESSION_PRICES_LOG] — live IB quote updates (see [SessionPriceLog])
 *
 * Each JSONL line includes `at` (ISO local with millis) and `epochMs` for cross-file correlation.
 */
object SessionTrace {
    private val json = Json { encodeDefaults = false }

    fun log(
        type: String,
        deploymentId: String? = null,
        sessionId: String? = null,
        symbol: String? = null,
        details: Map<String, String> = emptyMap(),
        data: JsonObject? = null
    ) {
        val stamp = LogTimestamps.now()
        val line = json.encodeToString(
            serializer = SessionTraceLine.serializer(),
            value = SessionTraceLine(
                at = stamp.at,
                epochMs = stamp.epochMs,
                type = type,
                brokerKind = runCatching { AppFileSystem.currentDataScope().name }.getOrNull(),
                deploymentId = deploymentId,
                sessionId = sessionId,
                symbol = symbol,
                details = details,
                data = data
            )
        )
        val path = resolveTracePath(deploymentId, sessionId) ?: return
        runCatching { JsonFileStore.appendSessionTraceLine(path, line) }
    }

    fun sessionStarted(deployment: StrategyDeployment, session: StrategySession) {
        flushPendingIntoSession(deployment.id, session.id)
        log(
            type = "session_started",
            deploymentId = deployment.id,
            sessionId = session.id,
            symbol = deployment.symbol,
            details = mapOf(
                "strategy" to deployment.strategyType.name,
                "sessionDate" to session.date,
                "startedAt" to session.startedAt,
                "maxAtRisk" to session.maxAtRisk.toString(),
                "startedBy" to (session.touchTurnStartedBy?.name ?: "unknown")
            )
        )
    }

    fun sessionClosed(
        deployment: StrategyDeployment,
        session: StrategySession,
        rawTrades: List<SessionTrade>,
        runRecord: TouchTurnRunRecord?,
        stopTrigger: String?,
        brokerUnrealizedPnL: Double?,
        hadOpenPosition: Boolean,
        hadOpenOrders: Boolean
    ) {
        val deduped = rawTrades.dedupeByExecId()
        val fillPnl = deduped.sessionRealizedPnL()
        log(
            type = "session_closed",
            deploymentId = deployment.id,
            sessionId = session.id,
            symbol = deployment.symbol,
            details = mapOf(
                "stoppedAt" to session.stoppedAt,
                "recordedPnl" to session.pnl.toString(),
                "pnlFromDedupedFills" to fillPnl.toString(),
                "rawFillRows" to rawTrades.size.toString(),
                "dedupedFillRows" to deduped.size.toString(),
                "roundTrips" to roundTripLabel(deduped),
                "stopTrigger" to (stopTrigger ?: "unknown"),
                "brokerUnrealizedPnL" to (brokerUnrealizedPnL?.toString() ?: "null"),
                "hadOpenPosition" to hadOpenPosition.toString(),
                "hadOpenOrders" to hadOpenOrders.toString(),
                "positionOpened" to (session.positionOpened?.toString() ?: "null"),
                "hadLiquidityCandle" to (session.hadLiquidityCandle?.toString() ?: "null"),
                "ordersPlacedForCandle" to (session.ordersPlacedForCandle?.toString() ?: "null")
            ),
            data = buildJsonObject {
                put("rawFills", JsonFileStore.encodeSessionTradesForTrace(rawTrades))
                put("dedupedFills", JsonFileStore.encodeSessionTradesForTrace(deduped))
                runRecord?.let { record ->
                    TouchTurnRunPersistence.toRecord(record)?.let { persisted ->
                        put(
                            "touchTurnRunRecord",
                            json.encodeToJsonElement(
                                TouchTurnRunRecordRecord.serializer(),
                                persisted
                            )
                        )
                    }
                }
            }
        )
    }

    fun fillRecorded(
        deploymentId: String?,
        sessionId: String?,
        symbol: String,
        fill: BrokerFill,
        positionQtyAfter: Int?
    ) {
        log(
            type = "fill_recorded",
            deploymentId = deploymentId,
            sessionId = sessionId,
            symbol = symbol,
            details = buildMap {
                put("execId", fill.execId)
                put("orderId", fill.orderId.toString())
                put("parentOrderId", fill.parentOrderId.toString())
                put("side", fill.side)
                put("qty", fill.quantity.toString())
                put("price", fill.price.toString())
                put("time", fill.time)
                fill.realizedPnL?.let { put("realizedPnL", it.toString()) }
                positionQtyAfter?.let { put("positionQtyAfter", it.toString()) }
            }
        )
    }

    fun quoteAtMilestone(
        deploymentId: String?,
        sessionId: String?,
        symbol: String,
        milestone: String,
        quote: LiveQuote
    ) {
        log(
            type = "quote_snapshot",
            deploymentId = deploymentId,
            sessionId = sessionId,
            symbol = symbol,
            details = mapOf(
                "milestone" to milestone,
                "bid" to (quote.bid?.toString() ?: "null"),
                "ask" to (quote.ask?.toString() ?: "null"),
                "last" to (quote.last?.toString() ?: "null")
            )
        )
    }

    fun touchTurnData(
        deploymentId: String,
        sessionId: String? = null,
        symbol: String,
        event: String,
        message: String? = null,
        adr14: Double? = null,
        barTime: String? = null
    ) {
        log(
            type = "touch_turn_data",
            deploymentId = deploymentId,
            sessionId = sessionId,
            symbol = symbol,
            details = buildMap {
                put("event", event)
                message?.let { put("message", it) }
                adr14?.let { put("adr14", it.toString()) }
                barTime?.let { put("barTime", it) }
            }
        )
    }

    fun autoStopCheck(
        deploymentId: String,
        symbol: String,
        sessionId: String?,
        wouldStop: Boolean,
        hasOpenPosition: Boolean,
        hasOpenOrders: Boolean,
        tradeCycleComplete: Boolean
    ) {
        log(
            type = "auto_stop_check",
            deploymentId = deploymentId,
            sessionId = sessionId,
            symbol = symbol,
            details = mapOf(
                "wouldStop" to wouldStop.toString(),
                "hasOpenPosition" to hasOpenPosition.toString(),
                "hasOpenOrders" to hasOpenOrders.toString(),
                "tradeCycleComplete" to tradeCycleComplete.toString()
            )
        )
    }

    fun milestone(
        deploymentId: String,
        sessionId: String?,
        symbol: String,
        name: String,
        at: String
    ) {
        log(
            type = "milestone",
            deploymentId = deploymentId,
            sessionId = sessionId,
            symbol = symbol,
            details = mapOf("name" to name, "at" to at)
        )
    }

    private fun resolveTracePath(deploymentId: String?, sessionId: String?): String? =
        when {
            deploymentId != null && sessionId != null ->
                AppDataFiles.sessionApplicationLogFileName(deploymentId, sessionId)
            deploymentId != null ->
                AppDataFiles.sessionPendingLogFileName(deploymentId)
            else ->
                AppDataFiles.sessionOrphanLogFileName()
        }

    private fun flushPendingIntoSession(deploymentId: String, sessionId: String) {
        val pendingPath = AppDataFiles.sessionPendingLogFileName(deploymentId)
        val sessionPath = AppDataFiles.sessionApplicationLogFileName(deploymentId, sessionId)
        val pending = runCatching { AppFileSystem.readText(pendingPath) }.getOrNull() ?: return
        pending.lineSequence()
            .filter { it.isNotBlank() }
            .forEach { line ->
                runCatching { JsonFileStore.appendSessionTraceLine(sessionPath, line) }
            }
        runCatching { AppFileSystem.deleteIfExists(pendingPath) }
    }

    private fun roundTripLabel(trades: List<SessionTrade>): String {
        val hasEntry = trades.any { it.parentOrderId == 0 }
        val hasExit = trades.any { it.parentOrderId != 0 }
        return when {
            hasEntry && hasExit -> "complete"
            hasEntry -> "entry_only"
            hasExit -> "exit_only"
            else -> "none"
        }
    }
}

@Serializable
private data class SessionTraceLine(
    val at: String,
    val epochMs: Long,
    val type: String,
    val brokerKind: String? = null,
    val deploymentId: String? = null,
    val sessionId: String? = null,
    val symbol: String? = null,
    val details: Map<String, String> = emptyMap(),
    val data: JsonObject? = null
)
