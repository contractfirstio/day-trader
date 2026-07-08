package daytrader.diagnostics

import daytrader.data.persistence.AppDataFiles
import daytrader.broker.emulator.EmulatorLogScope
import daytrader.data.persistence.JsonFileStore
import daytrader.data.persistence.TouchTurnRuleConfigPersistence
import daytrader.data.persistence.TouchTurnRuleConfigRecord
import daytrader.data.persistence.TouchTurnRunPersistence
import daytrader.data.persistence.TouchTurnRunRecordRecord
import daytrader.domain.StrategyDeployment
import daytrader.domain.StrategySession
import daytrader.domain.isTouchTurn
import daytrader.domain.SessionTrade
import daytrader.domain.TouchTurnLogic
import daytrader.domain.TouchTurnRunRecord
import daytrader.domain.dedupeByExecId
import daytrader.domain.hasClosingFill
import daytrader.domain.sessionRealizedPnL
import daytrader.diagnostics.LogTimestamps
import daytrader.gateway.BrokerFill
import daytrader.gateway.LiveQuote
import daytrader.gateway.TouchTurnBracketAck
import daytrader.gateway.WorkingOrder
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
 * - [AppDataFiles.SESSION_HISTORICAL_LOG] — Touch Turn bootstrap/refetch payloads (see [SessionHistoricalLog])
 * - [AppDataFiles.SESSION_MANIFEST] — session metadata for replay (see [SessionManifestWriter])
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
        DiagnosticJsonlWriter.appendLine(path, line)
    }

    fun sessionStarted(deployment: StrategyDeployment, session: StrategySession) {
        DiagnosticJsonlWriter.awaitIdleBlocking()
        flushPendingIntoSession(deployment.id, session.id)
        EmulatorLogScope.bind(deployment.id, session.id)
        val stamp = LogTimestamps.now()
        val touchTurnRulesData = if (deployment.isTouchTurn) {
            val rules = deployment.touchTurnSession?.rules ?: deployment.touchTurnRules
            buildJsonObject {
                put(
                    "touchTurnRules",
                    json.encodeToJsonElement(
                        TouchTurnRuleConfigRecord.serializer(),
                        TouchTurnRuleConfigPersistence.toRecord(rules)
                    )
                )
            }
        } else {
            null
        }
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
            ),
            data = touchTurnRulesData
        )
        SessionManifestWriter.sessionStarted(deployment, session, stamp.epochMs)
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
        val stamp = LogTimestamps.now()
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
        SessionManifestWriter.sessionClosed(deployment, session, runRecord, stamp.epochMs)
        EmulatorLogScope.clear()
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
        atr14: Double? = null,
        volumeSma20: Double? = null,
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
                atr14?.let { put("atr14", it.toString()) }
                volumeSma20?.let { put("volumeSma20", it.toString()) }
                barTime?.let { put("barTime", it) }
            }
        )
    }

    /** Post-close OHLC refetch after [TouchTurnSessionContext.openingBarTime] (application.jsonl). */
    fun closedBarRefetch(
        deploymentId: String,
        sessionId: String?,
        symbol: String,
        event: String,
        openingBarTime: String? = null,
        attempt: Int? = null,
        maxAttempts: Int? = null,
        waitMs: Long? = null,
        refetchedBarTime: String? = null,
        validation: String? = null,
        reason: String? = null,
        openingBarVolume: Double? = null,
        volumeSma20: Double? = null
    ) {
        log(
            type = "closed_bar_refetch",
            deploymentId = deploymentId,
            sessionId = sessionId,
            symbol = symbol,
            details = buildMap {
                put("event", event)
                openingBarTime?.let { put("openingBarTime", it) }
                attempt?.let { put("attempt", it.toString()) }
                maxAttempts?.let { put("maxAttempts", it.toString()) }
                waitMs?.let { put("waitMs", it.toString()) }
                refetchedBarTime?.let { put("refetchedBarTime", it) }
                validation?.let { put("validation", it) }
                reason?.let { put("reason", it) }
                openingBarVolume?.let { put("openingBarVolume", it.toString()) }
                volumeSma20?.let { put("volumeSma20", it.toString()) }
            }
        )
    }

    /** Interactive replay virtual-time playback ([daytrader.replay.ReplayPlaybackOrchestrator]). */
    fun replayPlayback(
        deploymentId: String,
        event: String,
        sessionId: String? = null,
        symbol: String? = null,
        details: Map<String, String> = emptyMap()
    ) {
        log(
            type = "replay_playback",
            deploymentId = deploymentId,
            sessionId = sessionId,
            symbol = symbol,
            details = buildMap {
                put("event", event)
                putAll(details)
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

    /** Engine queued [placeTouchTurnBracket] — session not committed until [bracketAcknowledged]. */
    fun bracketSubmitSkipped(
        deploymentId: String,
        sessionId: String?,
        symbol: String,
        reason: String,
        extraDetails: Map<String, String> = emptyMap(),
    ) {
        log(
            type = "bracket_submit_skipped",
            deploymentId = deploymentId,
            sessionId = sessionId,
            symbol = symbol,
            details = buildMap {
                put("reason", reason)
                putAll(extraDetails)
            }
        )
    }

    fun bracketSubmitRequested(
        deploymentId: String,
        sessionId: String?,
        symbol: String,
        orderCount: Int,
        entryPrice: Double?,
        currencyCode: String,
        pendingBracketCount: Int,
        extraDetails: Map<String, String> = emptyMap()
    ) {
        log(
            type = "bracket_submit_requested",
            deploymentId = deploymentId,
            sessionId = sessionId,
            symbol = symbol,
            details = buildMap {
                put("orderCount", orderCount.toString())
                entryPrice?.let { put("entryPrice", it.toString()) }
                put("currencyCode", currencyCode)
                put("pendingBracketCount", pendingBracketCount.toString())
                putAll(extraDetails)
            }
        )
    }

    /** Sweep armed after liquidity pass; 5m hammer watch started. */
    fun fiveMinuteConfirmationStarted(
        deploymentId: String,
        sessionId: String?,
        symbol: String,
        sweepPrice: Double,
        side: String,
        expiresAtEpochMs: Long
    ) {
        log(
            type = "five_minute_confirmation_started",
            deploymentId = deploymentId,
            sessionId = sessionId,
            symbol = symbol,
            details = mapOf(
                "sweepPrice" to sweepPrice.toString(),
                "side" to side,
                "expiresAtEpochMs" to expiresAtEpochMs.toString()
            )
        )
    }

    /** A synthetic or live 5m bar was evaluated against hammer rules. */
    fun fiveMinuteBarEvaluated(
        deploymentId: String,
        sessionId: String?,
        symbol: String,
        barTime: String,
        isHammer: Boolean,
        invalidatesSetup: Boolean,
        processedBarCount: Int,
        open: Double,
        high: Double,
        low: Double,
        close: Double
    ) {
        log(
            type = "five_minute_bar_evaluated",
            deploymentId = deploymentId,
            sessionId = sessionId,
            symbol = symbol,
            details = mapOf(
                "barTime" to barTime,
                "isHammer" to isHammer.toString(),
                "invalidatesSetup" to invalidatesSetup.toString(),
                "processedBarCount" to processedBarCount.toString(),
                "open" to open.toString(),
                "high" to high.toString(),
                "low" to low.toString(),
                "close" to close.toString()
            )
        )
    }

    /** Valid hammer closed; bracket submission follows. */
    fun fiveMinuteConfirmationConfirmed(
        deploymentId: String,
        sessionId: String?,
        symbol: String,
        barTime: String,
        entryPrice: Double,
        confirmedAt: String
    ) {
        log(
            type = "five_minute_confirmation_confirmed",
            deploymentId = deploymentId,
            sessionId = sessionId,
            symbol = symbol,
            details = mapOf(
                "barTime" to barTime,
                "entryPrice" to entryPrice.toString(),
                "confirmedAt" to confirmedAt
            )
        )
        milestone(
            deploymentId = deploymentId,
            sessionId = sessionId,
            symbol = symbol,
            name = "five_min_confirmed",
            at = confirmedAt
        )
    }

    /** Bracket blocked because projected gross profit to take-profit is below minimum. */
    fun grossProfitRejected(
        deploymentId: String,
        sessionId: String?,
        symbol: String,
        entryPrice: Double,
        takeProfit: Double,
        quantity: Int,
        projectedGrossProfit: Double,
        minGrossProfit: Double,
        currencyCode: String,
        path: String
    ) {
        log(
            type = "gross_profit_rejected",
            deploymentId = deploymentId,
            sessionId = sessionId,
            symbol = symbol,
            details = mapOf(
                "entryPrice" to entryPrice.toString(),
                "takeProfit" to takeProfit.toString(),
                "quantity" to quantity.toString(),
                "projectedGrossProfit" to projectedGrossProfit.toString(),
                "minGrossProfit" to minGrossProfit.toString(),
                "currencyCode" to currencyCode,
                "path" to path,
                "message" to daytrader.domain.TouchTurnGrossProfitGate.INSUFFICIENT_GROSS_PROFIT_MESSAGE
            )
        )
    }

    /** @deprecated Use [grossProfitRejected] */
    fun fiveMinuteGrossProfitRejected(
        deploymentId: String,
        sessionId: String?,
        symbol: String,
        barTime: String,
        marketEntry: Double,
        takeProfit: Double,
        quantity: Int,
        projectedGrossProfit: Double,
        minGrossProfit: Double,
        currencyCode: String
    ) {
        grossProfitRejected(
            deploymentId = deploymentId,
            sessionId = sessionId,
            symbol = symbol,
            entryPrice = marketEntry,
            takeProfit = takeProfit,
            quantity = quantity,
            projectedGrossProfit = projectedGrossProfit,
            minGrossProfit = minGrossProfit,
            currencyCode = currencyCode,
            path = "five_minute_hammer"
        )
    }

    fun bracketAcknowledged(
        deploymentId: String,
        sessionId: String?,
        symbol: String,
        ack: TouchTurnBracketAck,
        ackLatencyMs: Long,
        openOrdersForSymbol: Int,
        openOrdersTotal: Int,
        openOrderSummary: String
    ) {
        log(
            type = "bracket_acknowledged",
            deploymentId = deploymentId,
            sessionId = sessionId,
            symbol = symbol,
            details = buildMap {
                put("success", ack.result.isSuccess.toString())
                ack.result.exceptionOrNull()?.message?.let { put("error", it) }
                put("ackLatencyMs", ackLatencyMs.toString())
                put("ackOrderIds", ack.orderIds.joinToString(","))
                put("openOrdersForSymbol", openOrdersForSymbol.toString())
                put("openOrdersTotal", openOrdersTotal.toString())
                put("openOrderSummary", openOrderSummary)
            }
        )
    }

    fun bracketAckOrphan(
        symbol: String,
        ack: TouchTurnBracketAck,
        pendingBracketCount: Int
    ) {
        log(
            type = "bracket_ack_orphan",
            symbol = symbol,
            details = buildMap {
                put("success", ack.result.isSuccess.toString())
                ack.result.exceptionOrNull()?.message?.let { put("error", it) }
                put("ackOrderIds", ack.orderIds.joinToString(","))
                put("pendingBracketCount", pendingBracketCount.toString())
            }
        )
    }

    fun brokerOpenOrders(
        deploymentId: String,
        sessionId: String?,
        symbol: String,
        ordersForSymbol: List<WorkingOrder>,
        ordersTotal: Int,
        trigger: String
    ) {
        log(
            type = "broker_open_orders",
            deploymentId = deploymentId,
            sessionId = sessionId,
            symbol = symbol,
            details = buildMap {
                put("trigger", trigger)
                put("countForSymbol", ordersForSymbol.size.toString())
                put("countTotal", ordersTotal.toString())
                put(
                    "orders",
                    ordersForSymbol.joinToString(";") { o ->
                        "${o.orderId}:${o.status}:${o.orderType}@${o.limitPrice ?: o.stopPrice}"
                    }.ifEmpty { "none" }
                )
            }
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
        val hasExit = trades.hasClosingFill()
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
