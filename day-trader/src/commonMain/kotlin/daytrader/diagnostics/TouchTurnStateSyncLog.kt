package daytrader.diagnostics

import daytrader.domain.DeploymentStatus
import daytrader.domain.FirstCandleCloseStatus
import daytrader.domain.FiveMinuteConfirmationStatus
import daytrader.domain.LiquidityCandleEvaluation
import daytrader.domain.SessionTrade
import daytrader.domain.StrategyDeployment
import daytrader.domain.TouchTurnCandleStatus
import daytrader.domain.TouchTurnCloseConfirmation
import daytrader.domain.TouchTurnSessionContext
import daytrader.domain.TouchTurnSessionOutcome
import daytrader.domain.inProgressSession
import daytrader.presentation.strategies.TouchTurnBreadcrumbStep
import daytrader.presentation.strategies.TouchTurnBreadcrumbStepState
import daytrader.presentation.strategies.TouchTurnPipelineGraph
import daytrader.presentation.strategies.TouchTurnPipelineNodeId

/**
 * Persists paired engine + UI pipeline snapshots to session `application.jsonl` so post-hoc
 * analysis can verify the Touch Turn engine and pipeline graph stay aligned.
 *
 * Disabled when `DAY_TRADER_TOUCH_TURN_STATE_SYNC_LOG=false`.
 */
object TouchTurnStateSyncLog {
    private val enabled: Boolean
        get() = System.getenv("DAY_TRADER_TOUCH_TURN_STATE_SYNC_LOG")
            ?.equals("false", ignoreCase = true) != true

    private val lastFingerprintByDeployment = mutableMapOf<String, String>()

    data class EngineSnapshot(
        val sessionStatus: TouchTurnCandleStatus?,
        val decisionOutcome: TouchTurnSessionOutcome?,
        val entryOrdersPermitted: Boolean?,
        val ordersPlacedForSession: Boolean,
        val candleCloseStatus: FirstCandleCloseStatus?,
        val liquidityEvaluation: LiquidityCandleEvaluation?,
        val closeConfirmation: TouchTurnCloseConfirmation?,
        val hasOpenPosition: Boolean,
        val hasOpenOrders: Boolean,
        val closingPhase: Boolean,
        val tradeCycleComplete: Boolean,
        val sweepActive: Boolean,
        val fiveMinConfirmationStatus: FiveMinuteConfirmationStatus?,
        val fiveMinBarsEvaluated: Int,
        val fiveMinSweepPrice: Double?
    )

    data class UiSnapshot(
        val activePath: List<TouchTurnPipelineNodeId>,
        val stepStates: List<TouchTurnBreadcrumbStepState>,
        val phaseIndex: Int,
        val phaseSkippedFrom: Int?,
        val phaseTerminal: Boolean,
        val usesNoTradePipeline: Boolean,
        val caption: String
    )

    fun recordLive(
        instance: StrategyDeployment,
        steps: List<TouchTurnBreadcrumbStep>,
        graph: TouchTurnPipelineGraph,
        session: TouchTurnSessionContext?,
        hasOpenPosition: Boolean,
        hasOpenOrders: Boolean,
        sessionTrades: List<SessionTrade>,
        closingPhase: Boolean,
        phaseIndex: Int,
        phaseSkippedFrom: Int?,
        phaseTerminal: Boolean,
        usesNoTradePipeline: Boolean,
        closeConfirmation: TouchTurnCloseConfirmation?,
        nowEpochMillis: Long,
        trigger: String,
        triggerDetails: Map<String, String> = emptyMap()
    ) {
        if (!enabled) return
        if (instance.status != DeploymentStatus.RUNNING) return
        val sessionId = instance.inProgressSession()?.id ?: return

        val confirmation = session?.fiveMinuteConfirmation
        val engine = EngineSnapshot(
            sessionStatus = session?.status,
            decisionOutcome = session?.decisionOutcome,
            entryOrdersPermitted = session?.entryOrdersPermitted,
            ordersPlacedForSession = session?.ordersPlacedForSession == true,
            candleCloseStatus = session?.candleCloseStatus(nowEpochMillis),
            liquidityEvaluation = session?.liquidityEvaluation(nowEpochMillis),
            closeConfirmation = closeConfirmation ?: session?.pipelineCloseConfirmation(nowEpochMillis),
            hasOpenPosition = hasOpenPosition,
            hasOpenOrders = hasOpenOrders,
            closingPhase = closingPhase,
            tradeCycleComplete = closingPhase && !hasOpenPosition && !hasOpenOrders,
            sweepActive = session?.sweepActive == true,
            fiveMinConfirmationStatus = confirmation?.status,
            fiveMinBarsEvaluated = confirmation?.processedBarTimes?.size ?: 0,
            fiveMinSweepPrice = confirmation?.sweepPrice
        )
        val ui = UiSnapshot(
            activePath = graph.activePath,
            stepStates = steps.map { it.state },
            phaseIndex = phaseIndex,
            phaseSkippedFrom = phaseSkippedFrom,
            phaseTerminal = phaseTerminal,
            usesNoTradePipeline = usesNoTradePipeline,
            caption = graph.caption
        )
        val mismatches = findMismatches(engine, ui, session)
        val milestoneDetails = milestoneDetails(session)
        val volumeDetails = volumeCheckDetails(session)
        val fingerprint = fingerprint(engine, ui)
        if (trigger == "ui_graph_refresh" && mismatches.isEmpty() &&
            lastFingerprintByDeployment[instance.id] == fingerprint
        ) {
            return
        }
        lastFingerprintByDeployment[instance.id] = fingerprint

        SessionTrace.log(
            type = "touch_turn_state_sync",
            deploymentId = instance.id,
            sessionId = sessionId,
            symbol = instance.symbol,
            details = buildMap {
                put("trigger", trigger)
                put("syncFingerprint", fingerprint)
                put("mismatchCount", mismatches.size.toString())
                if (mismatches.isNotEmpty()) {
                    put("mismatches", mismatches.joinToString("; "))
                }
                putAll(triggerDetails)
                putAll(engineDetails(engine))
                putAll(volumeDetails)
                putAll(milestoneDetails)
                putAll(uiDetails(ui))
                put("sessionTradeCount", sessionTrades.size.toString())
            }
        )
    }

    fun clearForTesting() {
        lastFingerprintByDeployment.clear()
    }

    fun clearDeployment(deploymentId: String) {
        lastFingerprintByDeployment.remove(deploymentId)
    }

    fun findMismatches(
        engine: EngineSnapshot,
        ui: UiSnapshot,
        session: TouchTurnSessionContext?
    ): List<String> {
        if (session == null) return emptyList()
        val mismatches = mutableListOf<String>()

        when (engine.sessionStatus) {
            TouchTurnCandleStatus.LOADING ->
                if (ui.phaseIndex != 1) {
                    mismatches += "engine status=LOADING but ui phaseIndex=${ui.phaseIndex}"
                }
            TouchTurnCandleStatus.FAILED ->
                if (ui.stepStates.getOrNull(1) != TouchTurnBreadcrumbStepState.FAILED) {
                    mismatches += "engine status=FAILED but ui Data step=${ui.stepStates.getOrNull(1)?.name}"
                }
            TouchTurnCandleStatus.READY -> Unit
            null -> return emptyList()
        }

        val outcome = engine.decisionOutcome
        if (outcome in noTradeOutcomes) {
            if (TouchTurnPipelineNodeId.Orders in ui.activePath) {
                mismatches += "engine decisionOutcome=$outcome but Orders on ui activePath"
            }
            if (ui.phaseTerminal &&
                TouchTurnPipelineNodeId.Close !in ui.activePath
            ) {
                mismatches += "engine decisionOutcome=$outcome but Close missing from ui activePath"
            }
        }
        if (outcome == TouchTurnSessionOutcome.TRADE_BRACKET_SUBMITTED &&
            TouchTurnPipelineNodeId.Rules in ui.activePath &&
            TouchTurnPipelineNodeId.Close in ui.activePath &&
            TouchTurnPipelineNodeId.Orders !in ui.activePath &&
            !ui.phaseTerminal
        ) {
            mismatches += "engine decisionOutcome=TRADE_BRACKET_SUBMITTED but ui activePath skips Orders"
        }

        if (engine.ordersPlacedForSession) {
            val ordersState = ui.stepStates.getOrNull(4)
            if (ordersState != TouchTurnBreadcrumbStepState.COMPLETED &&
                ordersState != TouchTurnBreadcrumbStepState.CURRENT
            ) {
                mismatches += "engine ordersPlacedForSession=true but ui Orders step=${ordersState?.name}"
            }
        }

        if (engine.ordersPlacedForSession && !engine.hasOpenOrders && !engine.hasOpenPosition && !engine.closingPhase) {
            mismatches += "engine ordersPlacedForSession=true but broker hasOpenOrders=false (see bracket_submit_requested / bracket_acknowledged / emulator bracket_placed)"
        }

        if (engine.ordersPlacedForSession && !engine.hasOpenPosition && !engine.closingPhase) {
            val ordersState = ui.stepStates.getOrNull(4)
            val positionState = ui.stepStates.getOrNull(5)
            if (ordersState != TouchTurnBreadcrumbStepState.CURRENT) {
                mismatches += "engine waiting for entry but ui Orders step=${ordersState?.name}"
            }
            if (positionState != TouchTurnBreadcrumbStepState.UPCOMING) {
                mismatches += "engine waiting for entry but ui Position step=${positionState?.name}"
            }
        }

        if (engine.hasOpenPosition) {
            val positionState = ui.stepStates.getOrNull(5)
            if (positionState != TouchTurnBreadcrumbStepState.COMPLETED &&
                positionState != TouchTurnBreadcrumbStepState.CURRENT
            ) {
                mismatches += "engine hasOpenPosition=true but ui Position step=${positionState?.name}"
            }
        }

        if (engine.entryOrdersPermitted == false && TouchTurnPipelineNodeId.Orders in ui.activePath) {
            mismatches += "engine entryOrdersPermitted=false but Orders on ui activePath"
        }

        if (ui.usesNoTradePipeline && outcome == TouchTurnSessionOutcome.TRADE_BRACKET_SUBMITTED) {
            mismatches += "ui usesNoTradePipeline=true but engine decisionOutcome=TRADE_BRACKET_SUBMITTED"
        }
        if (engine.entryOrdersPermitted == true && ui.usesNoTradePipeline) {
            mismatches += "engine entryOrdersPermitted=true but ui usesNoTradePipeline=true"
        }
        if (!ui.usesNoTradePipeline && outcome in noTradeOutcomes && ui.phaseTerminal) {
            mismatches += "engine decisionOutcome=$outcome but ui usesNoTradePipeline=false at terminal phase"
        }

        if (engine.closingPhase && TouchTurnPipelineNodeId.Close !in ui.activePath) {
            mismatches += "engine closingPhase=true but Close not on ui activePath"
        }

        val milestones = session.milestones
        if (milestones.barClosedAt == null && ui.phaseIndex > 1) {
            mismatches += "ui phaseIndex=${ui.phaseIndex} but engine barClosedAt=null"
        }
        if (milestones.liquidityEvaluatedAt == null && ui.phaseIndex > 2) {
            mismatches += "ui phaseIndex=${ui.phaseIndex} but engine liquidityEvaluatedAt=null"
        }
        if (!engine.ordersPlacedForSession && ui.phaseIndex in 4..5 &&
            engine.decisionOutcome != TouchTurnSessionOutcome.TRADE_BRACKET_SUBMITTED &&
            !(engine.hasOpenPosition && engine.fiveMinConfirmationStatus == FiveMinuteConfirmationStatus.CONFIRMED)
        ) {
            mismatches += "ui phaseIndex=${ui.phaseIndex} but engine ordersPlacedForSession=false"
        }
        if (engine.closingPhase && ui.phaseTerminal && ui.phaseIndex != 6) {
            mismatches += "engine closingPhase=true but ui phaseIndex=${ui.phaseIndex} (expected Close=6)"
        }
        if (!engine.closingPhase && milestones.liquidityEvaluatedAt == null &&
            ui.phaseIndex > 2 &&
            TouchTurnPipelineNodeId.Rules in ui.activePath
        ) {
            mismatches += "ui Rules on path but engine liquidityEvaluatedAt=null"
        }
        if (session.failedDuringLiquidityRefetch() &&
            ui.phaseIndex < 2 &&
            engine.decisionOutcome == TouchTurnSessionOutcome.NO_TRADE_DATA_FAILED
        ) {
            mismatches += "engine liquidity refetch failed but ui phaseIndex=${ui.phaseIndex} (expected Rules=2)"
        }
        if (session.failedDuringLiquidityRefetch() &&
            milestones.barClosedAt == null
        ) {
            mismatches += "engine liquidity refetch failed but engine barClosedAt was cleared"
        }

        if (engine.sweepActive &&
            engine.fiveMinConfirmationStatus == FiveMinuteConfirmationStatus.AWAITING &&
            TouchTurnPipelineNodeId.FiveMinConfirmation !in ui.activePath
        ) {
            mismatches += "engine sweepActive=true but FiveMinConfirmation missing from ui activePath"
        }
        if (engine.sweepActive &&
            ui.phaseIndex != 3 &&
            engine.fiveMinConfirmationStatus == FiveMinuteConfirmationStatus.AWAITING
        ) {
            mismatches += "engine awaiting 5m hammer but ui phaseIndex=${ui.phaseIndex} (expected 5m=3)"
        }

        if (engine.closingPhase &&
            engine.ordersPlacedForSession &&
            !engine.hasOpenPosition &&
            session?.milestones?.positionOpenedAt == null
        ) {
            val positionState = ui.stepStates.getOrNull(5)
            if (positionState != TouchTurnBreadcrumbStepState.SKIPPED) {
                mismatches += "engine closed without entry fill but ui Position step=${positionState?.name}"
            }
            if (TouchTurnPipelineNodeId.Close in ui.activePath &&
                TouchTurnPipelineNodeId.Orders in ui.activePath &&
                TouchTurnPipelineNodeId.Position in ui.activePath
            ) {
                mismatches += "engine closed without entry fill but ui activePath includes Position after Orders"
            }
        }

        return mismatches
    }

    private fun fingerprint(engine: EngineSnapshot, ui: UiSnapshot): String =
        listOf(
            engine.sessionStatus?.name,
            engine.decisionOutcome?.name,
            engine.entryOrdersPermitted?.toString(),
            engine.ordersPlacedForSession.toString(),
            engine.candleCloseStatus?.name,
            engine.liquidityEvaluation?.name,
            engine.closeConfirmation?.name,
            engine.hasOpenPosition.toString(),
            engine.hasOpenOrders.toString(),
            engine.closingPhase.toString(),
            engine.sweepActive.toString(),
            engine.fiveMinConfirmationStatus?.name,
            engine.fiveMinBarsEvaluated.toString(),
            engine.fiveMinSweepPrice?.toString(),
            ui.activePath.joinToString(">") { it.name },
            ui.stepStates.joinToString(",") { it.name },
            ui.phaseIndex.toString(),
            ui.phaseSkippedFrom?.toString(),
            ui.phaseTerminal.toString(),
            ui.usesNoTradePipeline.toString(),
            ui.caption
        ).joinToString("|")

    private fun milestoneDetails(session: TouchTurnSessionContext?): Map<String, String> {
        val m = session?.milestones ?: return emptyMap()
        return mapOf(
            "engine.milestones.barClosed" to (m.barClosedAt != null).toString(),
            "engine.milestones.liquidityEvaluated" to (m.liquidityEvaluatedAt != null).toString(),
            "engine.milestones.fiveMinConfirmed" to (m.fiveMinConfirmedAt != null).toString(),
            "engine.milestones.ordersPlaced" to (m.ordersPlacedAt != null).toString()
        )
    }

    private fun volumeCheckDetails(session: TouchTurnSessionContext?): Map<String, String> {
        session ?: return emptyMap()
        return buildMap {
            put("engine.volumeSma20", session.volumeSma20?.toString() ?: "null")
            session.atr14?.let { put("engine.atr14", it.toString()) }
        }
    }

    private fun engineDetails(engine: EngineSnapshot): Map<String, String> = mapOf(
        "engine.sessionStatus" to (engine.sessionStatus?.name ?: "null"),
        "engine.decisionOutcome" to (engine.decisionOutcome?.name ?: "null"),
        "engine.entryOrdersPermitted" to (engine.entryOrdersPermitted?.toString() ?: "null"),
        "engine.ordersPlacedForSession" to engine.ordersPlacedForSession.toString(),
        "engine.candleCloseStatus" to (engine.candleCloseStatus?.name ?: "null"),
        "engine.liquidityEvaluation" to (engine.liquidityEvaluation?.name ?: "null"),
        "engine.closeConfirmation" to (engine.closeConfirmation?.name ?: "null"),
        "engine.hasOpenPosition" to engine.hasOpenPosition.toString(),
        "engine.hasOpenOrders" to engine.hasOpenOrders.toString(),
        "engine.closingPhase" to engine.closingPhase.toString(),
        "engine.tradeCycleComplete" to engine.tradeCycleComplete.toString(),
        "engine.sweepActive" to engine.sweepActive.toString(),
        "engine.fiveMinConfirmation.status" to (engine.fiveMinConfirmationStatus?.name ?: "null"),
        "engine.fiveMinConfirmation.barsEvaluated" to engine.fiveMinBarsEvaluated.toString(),
        "engine.fiveMinConfirmation.sweepPrice" to (engine.fiveMinSweepPrice?.toString() ?: "null")
    )

    private fun uiDetails(ui: UiSnapshot): Map<String, String> = mapOf(
        "ui.activePath" to ui.activePath.joinToString(">") { it.name },
        "ui.stepStates" to ui.stepStates.joinToString(",") { it.name },
        "ui.phaseIndex" to ui.phaseIndex.toString(),
        "ui.phaseSkippedFrom" to (ui.phaseSkippedFrom?.toString() ?: "null"),
        "ui.phaseTerminal" to ui.phaseTerminal.toString(),
        "ui.usesNoTradePipeline" to ui.usesNoTradePipeline.toString(),
        "ui.caption" to ui.caption
    )

    private val noTradeOutcomes = setOf(
        TouchTurnSessionOutcome.NO_TRADE_DATA_FAILED,
        TouchTurnSessionOutcome.NO_TRADE_NOT_LIQUIDITY,
        TouchTurnSessionOutcome.NO_TRADE_OPENING_BAR_COLOR_SKIPPED,
        TouchTurnSessionOutcome.NO_TRADE_OPENING_BAR_CLOSE_POSITION_SKIPPED,
        TouchTurnSessionOutcome.NO_TRADE_DOJI,
        TouchTurnSessionOutcome.NO_TRADE_CLOSE_CONFIRMATION_FAILED,
        TouchTurnSessionOutcome.NO_TRADE_BOUNCE_REJECTION_FAILED,
        TouchTurnSessionOutcome.NO_TRADE_BOUNCE_DATA_UNAVAILABLE,
        TouchTurnSessionOutcome.NO_TRADE_LIVE_CLOSE_CONFIRMATION_FAILED,
        TouchTurnSessionOutcome.NO_TRADE_BAR_LIVE_DIVERGENCE,
        TouchTurnSessionOutcome.NO_TRADE_ENTRY_NOT_TOUCHABLE,
        TouchTurnSessionOutcome.NO_TRADE_INVERT_ENTRY_MARKETABLE,
        TouchTurnSessionOutcome.NO_TRADE_INVERT_STOP_WOULD_TRIGGER,
        TouchTurnSessionOutcome.NO_TRADE_LIVE_QUOTE_UNAVAILABLE,
        TouchTurnSessionOutcome.NO_TRADE_ENTRY_WINDOW_EXPIRED,
        TouchTurnSessionOutcome.NO_TRADE_INSUFFICIENT_MAX_DOLLARS_FOR_MIN_LOT,
        TouchTurnSessionOutcome.NO_TRADE_ORDER_REJECTED
    )
}
