package daytrader.diagnostics

import daytrader.domain.DeploymentStatus
import daytrader.domain.FirstCandleCloseStatus
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
        val tradeCycleComplete: Boolean
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

        val engine = EngineSnapshot(
            sessionStatus = session?.status,
            decisionOutcome = session?.decisionOutcome,
            entryOrdersPermitted = session?.entryOrdersPermitted,
            ordersPlacedForSession = session?.ordersPlacedForSession == true,
            candleCloseStatus = session?.candleCloseStatus(nowEpochMillis),
            liquidityEvaluation = session?.liquidityEvaluation(nowEpochMillis),
            closeConfirmation = closeConfirmation ?: session?.closeConfirmation(nowEpochMillis),
            hasOpenPosition = hasOpenPosition,
            hasOpenOrders = hasOpenOrders,
            closingPhase = closingPhase,
            tradeCycleComplete = closingPhase && !hasOpenPosition && !hasOpenOrders
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
            if (ui.phaseTerminal && TouchTurnPipelineNodeId.NoTrade !in ui.activePath) {
                mismatches += "engine decisionOutcome=$outcome but NoTrade missing from ui activePath"
            }
        }
        if (outcome == TouchTurnSessionOutcome.TRADE_BRACKET_SUBMITTED &&
            TouchTurnPipelineNodeId.NoTrade in ui.activePath
        ) {
            mismatches += "engine decisionOutcome=TRADE_BRACKET_SUBMITTED but NoTrade on ui activePath"
        }

        if (engine.ordersPlacedForSession) {
            val ordersState = ui.stepStates.getOrNull(5)
            if (ordersState != TouchTurnBreadcrumbStepState.COMPLETED &&
                ordersState != TouchTurnBreadcrumbStepState.CURRENT
            ) {
                mismatches += "engine ordersPlacedForSession=true but ui Orders step=${ordersState?.name}"
            }
        }

        if (engine.hasOpenPosition) {
            val positionState = ui.stepStates.getOrNull(6)
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
        if (!ui.usesNoTradePipeline && outcome in noTradeOutcomes && ui.phaseTerminal) {
            mismatches += "engine decisionOutcome=$outcome but ui usesNoTradePipeline=false at terminal phase"
        }

        if (engine.closingPhase && TouchTurnPipelineNodeId.Close !in ui.activePath) {
            mismatches += "engine closingPhase=true but Close not on ui activePath"
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
            ui.activePath.joinToString(">") { it.name },
            ui.stepStates.joinToString(",") { it.name },
            ui.phaseIndex.toString(),
            ui.phaseSkippedFrom?.toString(),
            ui.phaseTerminal.toString(),
            ui.usesNoTradePipeline.toString(),
            ui.caption
        ).joinToString("|")

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
        "engine.tradeCycleComplete" to engine.tradeCycleComplete.toString()
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
        TouchTurnSessionOutcome.NO_TRADE_DOJI,
        TouchTurnSessionOutcome.NO_TRADE_CLOSE_CONFIRMATION_FAILED,
        TouchTurnSessionOutcome.NO_TRADE_ENTRY_WINDOW_EXPIRED,
        TouchTurnSessionOutcome.NO_TRADE_ORDER_REJECTED
    )
}
