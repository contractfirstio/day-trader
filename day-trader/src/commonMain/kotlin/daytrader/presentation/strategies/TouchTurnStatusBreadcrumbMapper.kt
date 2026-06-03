package daytrader.presentation.strategies

import daytrader.domain.DeploymentSessionStopAction
import daytrader.domain.DeploymentSessionStopLogic
import daytrader.domain.FirstCandleCloseStatus
import daytrader.domain.LiquidityCandleEvaluation
import daytrader.domain.SessionTrade
import daytrader.domain.SessionStatus
import daytrader.domain.DeploymentStatus
import daytrader.domain.StrategyDeployment
import daytrader.domain.StrategyType
import daytrader.domain.TouchTurnCandleStatus
import daytrader.domain.TouchTurnCloseConfirmation
import daytrader.domain.TouchTurnMilestoneTimestamps
import daytrader.domain.TouchTurnSessionOutcome
import daytrader.diagnostics.TouchTurnStateSyncLog
import daytrader.domain.TouchTurnSessionContext
import daytrader.domain.inProgressSession
import daytrader.domain.lastClosedTouchTurnSession
import daytrader.domain.toTouchTurnAnalysisContext
import daytrader.presentation.Formatters

enum class TouchTurnBreadcrumbStepState {
    COMPLETED,
    CURRENT,
    UPCOMING,
    FAILED,
    /** Step does not apply for this session (e.g. orders after a non-liquidity bar). */
    SKIPPED
}

data class TouchTurnBreadcrumbStep(
    val label: String,
    val state: TouchTurnBreadcrumbStepState,
    /** Formatted HH:mm when this step completed (null while still upcoming). */
    val timestamp: String? = null
)

/**
 * Touch Turn run pipeline above live position P&L:
 * Starting session → Data → Bar → Liquidity → Orders → Position → Closing session.
 */
object TouchTurnStatusBreadcrumbMapper {
    private const val IDX_START = 0
    private const val IDX_DATA = 1
    private const val IDX_BAR = 2
    private const val IDX_LIQUIDITY = 3
    private const val IDX_CONFIRM = 4
    private const val IDX_ORDERS = 5
    private const val IDX_POSITION = 6
    private const val IDX_CLOSE = 7

    private val pipelineLabels = listOf(
        "Starting session",
        "Data",
        "Bar",
        "Liquidity",
        "Confirm",
        "Orders",
        "Position",
        "Closing session"
    )

    fun steps(
        instance: StrategyDeployment,
        hasOpenPosition: Boolean,
        hasOpenOrders: Boolean = false,
        sessionTrades: List<SessionTrade> = emptyList(),
        nowEpochMillis: Long = System.currentTimeMillis()
    ): List<TouchTurnBreadcrumbStep> {
        val session = instance.touchTurnSession
        val milestones = session?.milestones ?: TouchTurnMilestoneTimestamps()
        val phase = resolveEffectivePhase(
            instance = instance,
            session = session,
            hasOpenPosition = hasOpenPosition,
            hasOpenOrders = hasOpenOrders,
            sessionTrades = sessionTrades,
            nowEpochMillis = nowEpochMillis
        )
        if (phase.failed) {
            return pipelineLabels.mapIndexed { index, label ->
                val state = when {
                    index == phase.index -> TouchTurnBreadcrumbStepState.FAILED
                    index < phase.index -> TouchTurnBreadcrumbStepState.COMPLETED
                    else -> TouchTurnBreadcrumbStepState.UPCOMING
                }
                TouchTurnBreadcrumbStep(
                    label = label,
                    state = state,
                    timestamp = timestampForStep(index, milestones, instance, phase, state)
                )
            }
        }
        val confirmFailed = confirmationStepFailed(session, nowEpochMillis)
        val liquidityRefetchFailed = session?.failedDuringLiquidityRefetch() == true &&
            session.decisionOutcome == TouchTurnSessionOutcome.NO_TRADE_DATA_FAILED
        return pipelineLabels.mapIndexed { index, label ->
            val state = when {
                index == IDX_LIQUIDITY && liquidityRefetchFailed ->
                    TouchTurnBreadcrumbStepState.FAILED
                index == IDX_CLOSE && phase.index == IDX_CLOSE && phase.terminal ->
                    TouchTurnBreadcrumbStepState.COMPLETED
                index == IDX_CONFIRM && confirmFailed ->
                    TouchTurnBreadcrumbStepState.FAILED
                confirmFailed && (index == IDX_ORDERS || index == IDX_POSITION) ->
                    TouchTurnBreadcrumbStepState.SKIPPED
                phase.skippedFromIndex != null && index >= phase.skippedFromIndex &&
                    index < IDX_CLOSE ->
                    TouchTurnBreadcrumbStepState.SKIPPED
                phase.terminal && index <= phase.index ->
                    TouchTurnBreadcrumbStepState.COMPLETED
                index < phase.index -> TouchTurnBreadcrumbStepState.COMPLETED
                index == phase.index && !phase.terminal -> TouchTurnBreadcrumbStepState.CURRENT
                else -> TouchTurnBreadcrumbStepState.UPCOMING
            }
            TouchTurnBreadcrumbStep(
                label = label,
                state = state,
                timestamp = timestampForStep(index, milestones, instance, phase, state)
            )
        }
    }

    private fun timestampForStep(
        index: Int,
        milestones: TouchTurnMilestoneTimestamps,
        instance: StrategyDeployment,
        phase: Phase,
        state: TouchTurnBreadcrumbStepState? = null
    ): String? {
        if (state == TouchTurnBreadcrumbStepState.UPCOMING) return null
        val iso = when (index) {
            IDX_START -> milestones.startingSessionAt ?: instance.inProgressSession()?.startedAt
            IDX_DATA -> when {
                phase.failed && phase.index == IDX_DATA -> milestones.dataFailedAt
                else -> milestones.dataReadyAt
            }
            IDX_BAR -> milestones.barClosedAt
            IDX_LIQUIDITY -> when {
                phase.failed && phase.index == IDX_LIQUIDITY -> milestones.dataFailedAt
                else -> milestones.liquidityEvaluatedAt
            }
            IDX_CONFIRM -> milestones.closeConfirmedAt
            IDX_ORDERS -> milestones.ordersPlacedAt
            IDX_POSITION -> milestones.positionOpenedAt
            IDX_CLOSE -> milestones.closingSessionAt
                ?: milestones.liquidityEvaluatedAt.takeIf {
                    phase.index == IDX_CLOSE && phase.terminal
                }
                ?: instance.inProgressSession()?.stoppedAt?.takeIf { it.isNotBlank() }
            else -> null
        }
        return Formatters.milestoneTimeFromIso(iso)
    }

    private fun resolveEffectivePhase(
        instance: StrategyDeployment,
        session: TouchTurnSessionContext?,
        hasOpenPosition: Boolean,
        hasOpenOrders: Boolean,
        sessionTrades: List<SessionTrade>,
        nowEpochMillis: Long
    ): Phase {
        val closing = isClosingPhase(
            instance = instance,
            hasOpenPosition = hasOpenPosition,
            hasOpenOrders = hasOpenOrders,
            sessionTrades = sessionTrades,
            nowEpochMillis = nowEpochMillis
        )
        val resolved = resolvePhase(session, hasOpenPosition, hasOpenOrders, nowEpochMillis)
        if (!closing) return resolved
        val skipFrom = when {
            confirmationStepFailed(session, nowEpochMillis) -> IDX_ORDERS
            resolved.failed -> (resolved.index + 1).coerceAtMost(IDX_ORDERS)
            resolved.skippedFromIndex != null -> resolved.skippedFromIndex
            entryNeverFilled(session, hasOpenPosition) -> IDX_POSITION
            else -> null
        }
        return Phase(
            index = IDX_CLOSE,
            skippedFromIndex = skipFrom,
            terminal = true
        )
    }

    private fun isClosingPhase(
        instance: StrategyDeployment,
        hasOpenPosition: Boolean,
        hasOpenOrders: Boolean,
        sessionTrades: List<SessionTrade>,
        nowEpochMillis: Long
    ): Boolean {
        if (DeploymentSessionStopLogic.shouldStopAfterNoTradeDecision(instance)) return true
        if (instance.touchTurnSession?.milestones?.closingSessionAt != null) return true
        if (instance.touchTurnSession?.status != TouchTurnCandleStatus.READY) return false
        if (!hasOpenPosition && !hasOpenOrders &&
            instance.touchTurnSession?.ordersPlacedForSession == true &&
            DeploymentSessionStopLogic.tradeCycleComplete(sessionTrades)
        ) {
            return true
        }
        return DeploymentSessionStopLogic.evaluateDeadlineForInstance(instance, nowEpochMillis) ==
            DeploymentSessionStopAction.STOP_AFTER_OPEN_DEADLINE
    }

    private data class Phase(
        val index: Int,
        val failed: Boolean = false,
        val skippedFromIndex: Int? = null,
        val terminal: Boolean = false
    )

    /**
     * Live pipeline phase follows engine [TouchTurnMilestoneTimestamps], not calendar-derived
     * liquidity/close hints. Broker position/order flags advance Orders and Position only after
     * the engine has committed the corresponding milestones.
     */
    private fun resolvePhase(
        session: TouchTurnSessionContext?,
        hasOpenPosition: Boolean,
        hasOpenOrders: Boolean,
        nowEpochMillis: Long
    ): Phase {
        if (session == null) return Phase(index = IDX_START)
        when (session.status) {
            TouchTurnCandleStatus.LOADING -> return Phase(index = IDX_DATA)
            TouchTurnCandleStatus.FAILED -> return failedPhase(session)
            TouchTurnCandleStatus.READY -> Unit
        }

        if (hasOpenPosition) {
            return Phase(index = IDX_POSITION)
        }

        when (session.decisionOutcome) {
            TouchTurnSessionOutcome.NO_TRADE_DATA_FAILED ->
                return failedPhase(session)
            TouchTurnSessionOutcome.NO_TRADE_NOT_LIQUIDITY,
            TouchTurnSessionOutcome.NO_TRADE_VOLUME_EXHAUSTION ->
                return Phase(index = IDX_CLOSE, skippedFromIndex = IDX_CONFIRM, terminal = true)
            TouchTurnSessionOutcome.NO_TRADE_DOJI,
            TouchTurnSessionOutcome.NO_TRADE_CLOSE_CONFIRMATION_FAILED,
            TouchTurnSessionOutcome.NO_TRADE_LIVE_CLOSE_CONFIRMATION_FAILED,
            TouchTurnSessionOutcome.NO_TRADE_BAR_LIVE_DIVERGENCE,
            TouchTurnSessionOutcome.NO_TRADE_ENTRY_NOT_TOUCHABLE,
            TouchTurnSessionOutcome.NO_TRADE_LIVE_QUOTE_UNAVAILABLE,
            TouchTurnSessionOutcome.NO_TRADE_ENTRY_WINDOW_EXPIRED ->
                return Phase(index = IDX_CLOSE, skippedFromIndex = IDX_ORDERS, terminal = true)
            TouchTurnSessionOutcome.NO_TRADE_ORDER_REJECTED ->
                return Phase(index = IDX_CLOSE, skippedFromIndex = IDX_POSITION, terminal = true)
            TouchTurnSessionOutcome.TRADE_BRACKET_SUBMITTED,
            null -> Unit
        }

        val milestones = session.milestones
        if (milestones.liquidityEvaluatedAt == null) {
            if (milestones.dataReadyAt == null) return Phase(index = IDX_DATA)
            if (milestones.barClosedAt == null) return Phase(index = IDX_BAR)
            return Phase(index = IDX_LIQUIDITY)
        }

        when (session.entryOrdersPermitted) {
            false -> return Phase(index = IDX_CLOSE, skippedFromIndex = IDX_ORDERS, terminal = true)
            true, null -> Unit
        }

        if (tradeOrdersCommitted(session)) {
            return Phase(index = IDX_ORDERS)
        }

        return Phase(index = IDX_CONFIRM)
    }

    /** Most recent closed session with a persisted pipeline log (Live tab after stop). */
    fun pipelineForLastClosedSession(instance: StrategyDeployment): List<TouchTurnBreadcrumbStep>? {
        if (instance.strategyType != StrategyType.TOUCH_AND_TURN_SCALPER) return null
        val run = instance.sessionHistory
            .filter {
                it.status == SessionStatus.CLOSED &&
                    (it.touchTurnMilestones != null || it.touchTurnRunRecord != null)
            }
            .maxByOrNull { it.stoppedAt.ifBlank { it.startedAt } }
            ?: return null
        val milestones = run.touchTurnMilestones ?: run.touchTurnRunRecord?.milestones ?: return null
        return stepsFromHistory(
            milestones = milestones,
            startedAt = run.startedAt,
            stoppedAt = run.stoppedAt,
            hadLiquidityCandle = run.hadLiquidityCandle,
            ordersPlacedForCandle = run.ordersPlacedForCandle,
            positionOpened = run.positionOpened,
            decisionOutcome = run.touchTurnRunRecord?.decision?.outcome
        )
    }

    /**
     * Reconstructs the pipeline for a closed session-history row (all steps settled).
     */
    fun stepsFromHistory(
        milestones: TouchTurnMilestoneTimestamps,
        startedAt: String,
        stoppedAt: String,
        hadLiquidityCandle: Boolean?,
        ordersPlacedForCandle: Boolean?,
        positionOpened: Boolean?,
        decisionOutcome: TouchTurnSessionOutcome? = null
    ): List<TouchTurnBreadcrumbStep> {
        val notLiquidity = hadLiquidityCandle == false ||
            decisionOutcome == TouchTurnSessionOutcome.NO_TRADE_NOT_LIQUIDITY
        val noTradeAfterConfirm = decisionOutcome in noTradeAfterConfirmationOutcomes
        val ordersSkipped = notLiquidity || noTradeAfterConfirm
        val positionSkipped = ordersSkipped ||
            positionOpened != true && (ordersPlacedForCandle != true || milestones.positionOpenedAt == null)
        val dataFailed = milestones.dataFailedAt != null
        return pipelineLabels.mapIndexed { index, label ->
            val state = when (index) {
                IDX_DATA -> when {
                    dataFailed -> TouchTurnBreadcrumbStepState.FAILED
                    milestones.dataReadyAt != null -> TouchTurnBreadcrumbStepState.COMPLETED
                    else -> TouchTurnBreadcrumbStepState.UPCOMING
                }
                IDX_BAR -> if (milestones.barClosedAt != null) {
                    TouchTurnBreadcrumbStepState.COMPLETED
                } else {
                    TouchTurnBreadcrumbStepState.UPCOMING
                }
                IDX_LIQUIDITY -> if (milestones.liquidityEvaluatedAt != null) {
                    TouchTurnBreadcrumbStepState.COMPLETED
                } else {
                    TouchTurnBreadcrumbStepState.UPCOMING
                }
                IDX_CONFIRM -> when {
                    noTradeAfterConfirm -> TouchTurnBreadcrumbStepState.FAILED
                    milestones.closeConfirmedAt != null -> TouchTurnBreadcrumbStepState.COMPLETED
                    notLiquidity -> TouchTurnBreadcrumbStepState.UPCOMING
                    else -> TouchTurnBreadcrumbStepState.UPCOMING
                }
                IDX_ORDERS -> when {
                    ordersSkipped -> TouchTurnBreadcrumbStepState.SKIPPED
                    milestones.ordersPlacedAt != null || ordersPlacedForCandle == true ->
                        TouchTurnBreadcrumbStepState.COMPLETED
                    else -> TouchTurnBreadcrumbStepState.UPCOMING
                }
                IDX_POSITION -> when {
                    positionSkipped -> TouchTurnBreadcrumbStepState.SKIPPED
                    milestones.positionOpenedAt != null || positionOpened == true ->
                        TouchTurnBreadcrumbStepState.COMPLETED
                    else -> TouchTurnBreadcrumbStepState.UPCOMING
                }
                IDX_CLOSE -> TouchTurnBreadcrumbStepState.COMPLETED
                else -> if (milestones.startingSessionAt != null || startedAt.isNotBlank()) {
                    TouchTurnBreadcrumbStepState.COMPLETED
                } else {
                    TouchTurnBreadcrumbStepState.UPCOMING
                }
            }
            TouchTurnBreadcrumbStep(
                label = label,
                state = state,
                timestamp = historyTimestampForStep(
                    index = index,
                    milestones = milestones,
                    startedAt = startedAt,
                    stoppedAt = stoppedAt,
                    dataFailed = dataFailed
                )
            )
        }
    }

    private fun historyTimestampForStep(
        index: Int,
        milestones: TouchTurnMilestoneTimestamps,
        startedAt: String,
        stoppedAt: String,
        dataFailed: Boolean
    ): String? {
        val iso = when (index) {
            IDX_START -> milestones.startingSessionAt ?: startedAt.takeIf { it.isNotBlank() }
            IDX_DATA -> if (dataFailed) milestones.dataFailedAt else milestones.dataReadyAt
            IDX_BAR -> milestones.barClosedAt
            IDX_LIQUIDITY -> milestones.liquidityEvaluatedAt
            IDX_CONFIRM -> milestones.closeConfirmedAt
            IDX_ORDERS -> milestones.ordersPlacedAt
            IDX_POSITION -> milestones.positionOpenedAt
            IDX_CLOSE -> milestones.closingSessionAt ?: stoppedAt.takeIf { it.isNotBlank() }
            else -> null
        }
        return Formatters.milestoneTimeFromIso(iso)
    }

    fun graph(
        instance: StrategyDeployment,
        hasOpenPosition: Boolean,
        hasOpenOrders: Boolean = false,
        sessionTrades: List<SessionTrade> = emptyList(),
        nowEpochMillis: Long = System.currentTimeMillis(),
        syncTrigger: String = "ui_graph_refresh",
        syncTriggerDetails: Map<String, String> = emptyMap()
    ): TouchTurnPipelineGraph {
        val stepList = steps(
            instance = instance,
            hasOpenPosition = hasOpenPosition,
            hasOpenOrders = hasOpenOrders,
            sessionTrades = sessionTrades,
            nowEpochMillis = nowEpochMillis
        )
        val closing = isClosingPhase(
            instance = instance,
            hasOpenPosition = hasOpenPosition,
            hasOpenOrders = hasOpenOrders,
            sessionTrades = sessionTrades,
            nowEpochMillis = nowEpochMillis
        )
        val graph = buildGraph(
            steps = stepList,
            session = instance.touchTurnSession,
            nowEpochMillis = nowEpochMillis,
            hasOpenPosition = hasOpenPosition,
            hasOpenOrders = hasOpenOrders,
            closing = closing,
            deploymentRunning = instance.status == DeploymentStatus.RUNNING
        )
        logPipelineGraph(
            instanceId = instance.id,
            symbol = instance.symbol,
            instance = instance,
            steps = stepList,
            graph = graph,
            hasOpenPosition = hasOpenPosition,
            hasOpenOrders = hasOpenOrders,
            sessionTrades = sessionTrades,
            nowEpochMillis = nowEpochMillis,
            source = "live",
            syncTrigger = syncTrigger,
            syncTriggerDetails = syncTriggerDetails
        )
        return graph
    }

    fun graphForLastClosedSession(instance: StrategyDeployment): TouchTurnPipelineGraph? {
        val run = instance.lastClosedTouchTurnSession() ?: return null
        val runRecord = run.touchTurnRunRecord
        val milestones = run.touchTurnMilestones ?: runRecord?.milestones ?: return null
        return graphFromHistory(
            milestones = milestones,
            startedAt = run.startedAt,
            stoppedAt = run.stoppedAt,
            hadLiquidityCandle = run.hadLiquidityCandle
                ?: runRecord?.let { record ->
                    record.decision.outcome != TouchTurnSessionOutcome.NO_TRADE_NOT_LIQUIDITY
                },
            ordersPlacedForCandle = run.ordersPlacedForCandle
                ?: runRecord?.let { record ->
                    record.decision.outcome == TouchTurnSessionOutcome.TRADE_BRACKET_SUBMITTED
                },
            positionOpened = run.positionOpened,
            decisionOutcome = runRecord?.decision?.outcome,
            instanceId = instance.id,
            symbol = instance.symbol
        )
    }

    fun graphFromHistory(
        milestones: TouchTurnMilestoneTimestamps,
        startedAt: String,
        stoppedAt: String,
        hadLiquidityCandle: Boolean?,
        ordersPlacedForCandle: Boolean?,
        positionOpened: Boolean?,
        decisionOutcome: TouchTurnSessionOutcome? = null,
        instanceId: String = "history",
        symbol: String = "?"
    ): TouchTurnPipelineGraph {
        val session = decisionOutcome?.let { outcome ->
            TouchTurnSessionContext(
                sessionDate = startedAt.take(10).ifBlank { "unknown" },
                status = TouchTurnCandleStatus.READY,
                decisionOutcome = outcome,
                milestones = milestones
            )
        }
        val steps = stepsFromHistory(
            milestones = milestones,
            startedAt = startedAt,
            stoppedAt = stoppedAt,
            hadLiquidityCandle = hadLiquidityCandle,
            ordersPlacedForCandle = ordersPlacedForCandle,
            positionOpened = positionOpened,
            decisionOutcome = decisionOutcome
        )
        val graph = buildGraph(
            steps = steps,
            session = session,
            nowEpochMillis = System.currentTimeMillis(),
            hasOpenPosition = false,
            hasOpenOrders = false,
            closing = true
        )
        TouchTurnPipelineLog.graphBuilt(
            instanceId = instanceId,
            symbol = symbol,
            session = session,
            steps = steps,
            graph = graph,
            hasOpenPosition = false,
            hasOpenOrders = false,
            closingPhase = true,
            phaseIndex = IDX_CLOSE,
            phaseSkippedFrom = null,
            phaseTerminal = true,
            usesNoTradePipeline = usesNoTradePipeline(session, steps, System.currentTimeMillis()),
            closeConfirmation = null,
            nowEpochMillis = System.currentTimeMillis(),
            source = "history"
        )
        return graph
    }

    fun buildGraph(
        steps: List<TouchTurnBreadcrumbStep>,
        session: TouchTurnSessionContext? = null,
        nowEpochMillis: Long = System.currentTimeMillis(),
        hasOpenPosition: Boolean = false,
        hasOpenOrders: Boolean = false,
        closing: Boolean = false,
        deploymentRunning: Boolean = false
    ): TouchTurnPipelineGraph {
        val noTradeState = noTradeNodeState(steps, session, nowEpochMillis)
        val activePath = activePathFor(steps, noTradeState, session, nowEpochMillis)
        val nodes = pipelineNodes(steps, noTradeState)
        val edges = pipelineEdges(activePath, nodes)
        val caption = pipelineCaption(steps, nodes, activePath, session, nowEpochMillis)
        val statusBanner = TouchTurnSessionReasonUi.liveStatus(
            session = session,
            hasOpenPosition = hasOpenPosition,
            hasOpenOrders = hasOpenOrders,
            closing = closing,
            nowEpochMillis = nowEpochMillis,
            deploymentRunning = deploymentRunning
        )
        return TouchTurnPipelineGraph(
            nodes = nodes,
            edges = edges,
            activePath = activePath,
            caption = caption,
            statusBanner = statusBanner
        )
    }

    private fun noTradeNodeState(
        steps: List<TouchTurnBreadcrumbStep>,
        session: TouchTurnSessionContext?,
        nowEpochMillis: Long
    ): TouchTurnBreadcrumbStepState {
        if (usesNoTradePipeline(session, steps, nowEpochMillis)) {
            return when (steps[IDX_CLOSE].state) {
                TouchTurnBreadcrumbStepState.UPCOMING -> TouchTurnBreadcrumbStepState.CURRENT
                else -> TouchTurnBreadcrumbStepState.COMPLETED
            }
        }
        val orders = steps[IDX_ORDERS].state
        val position = steps[IDX_POSITION].state
        val liquidity = steps[IDX_LIQUIDITY].state
        if (orders != TouchTurnBreadcrumbStepState.SKIPPED &&
            position != TouchTurnBreadcrumbStepState.SKIPPED
        ) {
            return TouchTurnBreadcrumbStepState.UPCOMING
        }
        if (orders == TouchTurnBreadcrumbStepState.SKIPPED &&
            position == TouchTurnBreadcrumbStepState.SKIPPED
        ) {
            return when (liquidity) {
                TouchTurnBreadcrumbStepState.COMPLETED ->
                    if (steps[IDX_CLOSE].state == TouchTurnBreadcrumbStepState.UPCOMING) {
                        TouchTurnBreadcrumbStepState.COMPLETED
                    } else {
                        steps[IDX_CLOSE].state
                    }
                TouchTurnBreadcrumbStepState.CURRENT -> TouchTurnBreadcrumbStepState.CURRENT
                else -> TouchTurnBreadcrumbStepState.UPCOMING
            }
        }
        if (position == TouchTurnBreadcrumbStepState.SKIPPED &&
            orders == TouchTurnBreadcrumbStepState.COMPLETED
        ) {
            return TouchTurnBreadcrumbStepState.UPCOMING
        }
        if (position == TouchTurnBreadcrumbStepState.SKIPPED &&
            orders == TouchTurnBreadcrumbStepState.CURRENT
        ) {
            return TouchTurnBreadcrumbStepState.UPCOMING
        }
        return TouchTurnBreadcrumbStepState.UPCOMING
    }

    private fun activePathFor(
        steps: List<TouchTurnBreadcrumbStep>,
        noTradeState: TouchTurnBreadcrumbStepState,
        session: TouchTurnSessionContext?,
        nowEpochMillis: Long
    ): List<TouchTurnPipelineNodeId> {
        val path = mutableListOf<TouchTurnPipelineNodeId>()
        fun trunkStep(i: Int): TouchTurnBreadcrumbStepState = steps[i].state

        for (i in IDX_START..IDX_LIQUIDITY) {
            when (val state = trunkStep(i)) {
                TouchTurnBreadcrumbStepState.COMPLETED,
                TouchTurnBreadcrumbStepState.FAILED -> {
                    path.add(indexToNodeId(i))
                    if (state == TouchTurnBreadcrumbStepState.FAILED) return path
                }
                TouchTurnBreadcrumbStepState.CURRENT -> {
                    path.add(indexToNodeId(i))
                    return path
                }
                else -> return path
            }
        }

        when (steps[IDX_CONFIRM].state) {
            TouchTurnBreadcrumbStepState.COMPLETED,
            TouchTurnBreadcrumbStepState.FAILED,
            TouchTurnBreadcrumbStepState.CURRENT -> path.add(TouchTurnPipelineNodeId.Confirmation)
            else -> Unit
        }

        if (usesNoTradePipeline(session, steps, nowEpochMillis)) {
            path.add(TouchTurnPipelineNodeId.NoTrade)
            path.add(TouchTurnPipelineNodeId.Close)
            return path
        }

        if (TouchTurnPipelineNodeId.Confirmation !in path) {
            return path
        }

        when (steps[IDX_CONFIRM].state) {
            TouchTurnBreadcrumbStepState.CURRENT -> return path
            TouchTurnBreadcrumbStepState.UPCOMING -> return path
            else -> Unit
        }

        val ordersSkipped = steps[IDX_ORDERS].state == TouchTurnBreadcrumbStepState.SKIPPED
        val positionSkipped = steps[IDX_POSITION].state == TouchTurnBreadcrumbStepState.SKIPPED

        if (ordersSkipped && positionSkipped) {
            if (noTradeState != TouchTurnBreadcrumbStepState.UPCOMING) {
                path.add(TouchTurnPipelineNodeId.NoTrade)
            }
            if (steps[IDX_CLOSE].state != TouchTurnBreadcrumbStepState.UPCOMING) {
                path.add(TouchTurnPipelineNodeId.Close)
            }
            return path
        }

        when (steps[IDX_ORDERS].state) {
            TouchTurnBreadcrumbStepState.COMPLETED,
            TouchTurnBreadcrumbStepState.CURRENT -> {
                path.add(TouchTurnPipelineNodeId.Orders)
                if (steps[IDX_ORDERS].state == TouchTurnBreadcrumbStepState.CURRENT) {
                    return path
                }
            }
            else -> return path
        }

        if (!positionSkipped) {
            when (steps[IDX_POSITION].state) {
                TouchTurnBreadcrumbStepState.COMPLETED,
                TouchTurnBreadcrumbStepState.CURRENT -> {
                    path.add(TouchTurnPipelineNodeId.Position)
                    if (steps[IDX_POSITION].state == TouchTurnBreadcrumbStepState.CURRENT) {
                        return path
                    }
                }
                else -> return path
            }
        } else if (
            (noTradeState == TouchTurnBreadcrumbStepState.COMPLETED ||
                noTradeState == TouchTurnBreadcrumbStepState.CURRENT) &&
            steps[IDX_ORDERS].state == TouchTurnBreadcrumbStepState.SKIPPED
        ) {
            path.add(TouchTurnPipelineNodeId.NoTrade)
            if (noTradeState == TouchTurnBreadcrumbStepState.CURRENT) {
                return path
            }
        }

        if (steps[IDX_CLOSE].state != TouchTurnBreadcrumbStepState.UPCOMING) {
            path.add(TouchTurnPipelineNodeId.Close)
        }
        return path
    }

    private data class PipelineNodeMeta(
        val stepIndex: Int,
        val label: String,
        val shortLabel: String,
        val isDecision: Boolean
    )

    private fun pipelineNodes(
        steps: List<TouchTurnBreadcrumbStep>,
        noTradeState: TouchTurnBreadcrumbStepState
    ): List<TouchTurnPipelineNode> {
        val noTradeOnPath = noTradeState != TouchTurnBreadcrumbStepState.UPCOMING

        fun stepState(index: Int): TouchTurnBreadcrumbStepState = steps[index].state
        fun stepTime(index: Int): String? = steps[index].timestamp

        return TouchTurnPipelineNodeId.entries.map { id ->
            val meta = when (id) {
                TouchTurnPipelineNodeId.Start -> PipelineNodeMeta(
                    IDX_START, pipelineLabels[IDX_START], "Start", false
                )
                TouchTurnPipelineNodeId.Data -> PipelineNodeMeta(
                    IDX_DATA, pipelineLabels[IDX_DATA], "Data", false
                )
                TouchTurnPipelineNodeId.Bar -> PipelineNodeMeta(
                    IDX_BAR, pipelineLabels[IDX_BAR], "Bar", false
                )
                TouchTurnPipelineNodeId.Liquidity -> PipelineNodeMeta(
                    IDX_LIQUIDITY, pipelineLabels[IDX_LIQUIDITY], "Liq", true
                )
                TouchTurnPipelineNodeId.Confirmation -> PipelineNodeMeta(
                    IDX_CONFIRM, pipelineLabels[IDX_CONFIRM], "Confirm", true
                )
                TouchTurnPipelineNodeId.Orders -> PipelineNodeMeta(
                    IDX_ORDERS, pipelineLabels[IDX_ORDERS], "Orders", true
                )
                TouchTurnPipelineNodeId.Position -> PipelineNodeMeta(
                    IDX_POSITION, pipelineLabels[IDX_POSITION], "Pos", false
                )
                TouchTurnPipelineNodeId.NoTrade -> PipelineNodeMeta(
                    -1, "No trade", "No trade", false
                )
                TouchTurnPipelineNodeId.Close -> PipelineNodeMeta(
                    IDX_CLOSE, pipelineLabels[IDX_CLOSE], "Close", false
                )
            }
            val index = meta.stepIndex
            val state = when (id) {
                TouchTurnPipelineNodeId.NoTrade ->
                    if (noTradeOnPath) noTradeState else TouchTurnBreadcrumbStepState.SKIPPED
                TouchTurnPipelineNodeId.Orders,
                TouchTurnPipelineNodeId.Position ->
                    if (noTradeOnPath) TouchTurnBreadcrumbStepState.SKIPPED else stepState(index)
                else -> if (index >= 0) stepState(index) else TouchTurnBreadcrumbStepState.UPCOMING
            }
            val timestamp = when (id) {
                TouchTurnPipelineNodeId.NoTrade ->
                    if (state == TouchTurnBreadcrumbStepState.COMPLETED) {
                        stepTime(IDX_LIQUIDITY) ?: stepTime(IDX_ORDERS)
                    } else {
                        null
                    }
                else -> if (index >= 0) stepTime(index) else null
            }
            val (x, y) = TouchTurnPipelineLayout.position(id)
            TouchTurnPipelineNode(
                id = id,
                label = meta.label,
                shortLabel = meta.shortLabel,
                state = state,
                timestamp = timestamp,
                x = x,
                y = y,
                isDecision = meta.isDecision
            )
        }
    }

    private fun pipelineEdges(
        activePath: List<TouchTurnPipelineNodeId>,
        nodes: List<TouchTurnPipelineNode>
    ): List<TouchTurnPipelineEdge> {
        val nodeById = nodes.associateBy { it.id }
        return TouchTurnPipelineLayout.edgeDefinitions.map { (from, to, label) ->
            TouchTurnPipelineEdge(
                from = from,
                to = to,
                label = label,
                state = edgeState(from, to, activePath, nodeById)
            )
        }
    }

    private fun edgeState(
        from: TouchTurnPipelineNodeId,
        to: TouchTurnPipelineNodeId,
        activePath: List<TouchTurnPipelineNodeId>,
        nodeById: Map<TouchTurnPipelineNodeId, TouchTurnPipelineNode>
    ): TouchTurnPipelineEdgeState {
        val fromIndex = activePath.indexOf(from)
        val toIndex = activePath.indexOf(to)
        if (fromIndex >= 0 && toIndex == fromIndex + 1) {
            val toNode = nodeById[to] ?: return TouchTurnPipelineEdgeState.Taken
            return if (toNode.state == TouchTurnBreadcrumbStepState.CURRENT) {
                TouchTurnPipelineEdgeState.Active
            } else {
                TouchTurnPipelineEdgeState.Taken
            }
        }
        val fromNode = nodeById[from] ?: return TouchTurnPipelineEdgeState.Unreachable
        if (from == TouchTurnPipelineNodeId.Confirmation &&
            fromNode.state == TouchTurnBreadcrumbStepState.FAILED &&
            (to == TouchTurnPipelineNodeId.Orders || to == TouchTurnPipelineNodeId.Position)
        ) {
            return TouchTurnPipelineEdgeState.Unreachable
        }
        if (from in activePath && to !in activePath && fromNode.state == TouchTurnBreadcrumbStepState.COMPLETED) {
            return TouchTurnPipelineEdgeState.Dimmed
        }
        if (fromNode.state == TouchTurnBreadcrumbStepState.SKIPPED ||
            nodeById[to]?.state == TouchTurnBreadcrumbStepState.SKIPPED
        ) {
            return TouchTurnPipelineEdgeState.Unreachable
        }
        return TouchTurnPipelineEdgeState.Unreachable
    }

    private fun pipelineCaption(
        steps: List<TouchTurnBreadcrumbStep>,
        nodes: List<TouchTurnPipelineNode>,
        activePath: List<TouchTurnPipelineNodeId>,
        session: TouchTurnSessionContext?,
        nowEpochMillis: Long
    ): String {
        session?.decisionOutcome?.takeIf { it != TouchTurnSessionOutcome.TRADE_BRACKET_SUBMITTED }?.let { outcome ->
            val headline = TouchTurnSessionReasonUi.forDecisionOutcome(outcome, session).headline
            return appendTimestamp(headline, nodes)
        }
        if (session?.status == TouchTurnCandleStatus.FAILED) {
            val headline = TouchTurnSessionReasonUi.forDecisionOutcome(
                TouchTurnSessionOutcome.NO_TRADE_DATA_FAILED,
                session
            ).headline
            return appendTimestamp(headline, nodes)
        }
        nodes.firstOrNull { it.state == TouchTurnBreadcrumbStepState.CURRENT }?.let { current ->
            when (current.id) {
                TouchTurnPipelineNodeId.Orders -> if (
                    nodes.firstOrNull { it.id == TouchTurnPipelineNodeId.Position }
                        ?.state == TouchTurnBreadcrumbStepState.UPCOMING
                ) {
                    return appendTimestamp("Waiting for entry fill", nodes, current.timestamp)
                }
                TouchTurnPipelineNodeId.Position -> {
                    return appendTimestamp("In position — TP / SL working", nodes, current.timestamp)
                }
                TouchTurnPipelineNodeId.Close -> {
                    session?.decisionOutcome?.let { outcome ->
                        return appendTimestamp(
                            TouchTurnSessionReasonUi.forDecisionOutcome(outcome, session).headline,
                            nodes,
                            current.timestamp
                        )
                    }
                }
                else -> Unit
            }
            return captionForNode(current)
        }
        nodes.firstOrNull { it.state == TouchTurnBreadcrumbStepState.FAILED }?.let { failed ->
            val detail = when (failed.id) {
                TouchTurnPipelineNodeId.Data ->
                    session?.errorMessage?.takeIf { it.isNotBlank() }
                        ?: "Could not load opening bar or ATR"
                TouchTurnPipelineNodeId.Confirmation ->
                    TouchTurnSessionReasonUi.forDecisionOutcome(
                        session?.decisionOutcome ?: TouchTurnSessionOutcome.NO_TRADE_CLOSE_CONFIRMATION_FAILED,
                        session
                    ).headline.removePrefix("No trade — ")
                else -> null
            }
            return buildString {
                append(failed.label)
                append(" failed")
                detail?.let { append(" — $it") }
                failed.timestamp?.let { append(" · $it") }
            }
        }
        if (steps[IDX_ORDERS].state == TouchTurnBreadcrumbStepState.SKIPPED &&
            (steps[IDX_LIQUIDITY].state == TouchTurnBreadcrumbStepState.COMPLETED ||
                steps[IDX_CONFIRM].state == TouchTurnBreadcrumbStepState.COMPLETED)
        ) {
            val reason = session?.decisionOutcome?.let {
                TouchTurnSessionReasonUi.forDecisionOutcome(it, session).headline
            } ?: "No trade — orders skipped"
            return appendTimestamp(reason, nodes, steps[IDX_LIQUIDITY].timestamp)
        }
        if (session?.entryOrdersPermitted == false &&
            steps[IDX_CONFIRM].state != TouchTurnBreadcrumbStepState.UPCOMING
        ) {
            return appendTimestamp(
                TouchTurnSessionReasonUi.pendingEntryBlockDetail(session, nowEpochMillis),
                nodes
            )
        }
        activePath.lastOrNull()?.let { lastId ->
            nodes.firstOrNull { it.id == lastId && it.state == TouchTurnBreadcrumbStepState.COMPLETED }
                ?.let { node ->
                    val outcome = session?.decisionOutcome
                    if (node.id == TouchTurnPipelineNodeId.Close && outcome != null) {
                        return appendTimestamp(
                            TouchTurnSessionReasonUi.forDecisionOutcome(
                                outcome,
                                session
                            ).headline,
                            nodes,
                            node.timestamp
                        )
                    }
                    return captionForNode(node, suffix = "done")
                }
        }
        return ""
    }

    private fun appendTimestamp(
        text: String,
        nodes: List<TouchTurnPipelineNode>,
        explicitTimestamp: String? = null
    ): String = buildString {
        append(text)
        val ts = explicitTimestamp
            ?: nodes.firstOrNull { it.state == TouchTurnBreadcrumbStepState.CURRENT }?.timestamp
        ts?.let { append(" · $it") }
    }

    private fun captionForNode(node: TouchTurnPipelineNode, suffix: String? = null): String =
        buildString {
            append(node.label)
            suffix?.let { append(" · $it") }
            node.timestamp?.let { append(" · $it") }
        }

    private val noTradeAfterConfirmationOutcomes = setOf(
        TouchTurnSessionOutcome.NO_TRADE_DOJI,
        TouchTurnSessionOutcome.NO_TRADE_CLOSE_CONFIRMATION_FAILED,
        TouchTurnSessionOutcome.NO_TRADE_LIVE_CLOSE_CONFIRMATION_FAILED,
        TouchTurnSessionOutcome.NO_TRADE_BAR_LIVE_DIVERGENCE,
        TouchTurnSessionOutcome.NO_TRADE_ENTRY_NOT_TOUCHABLE,
        TouchTurnSessionOutcome.NO_TRADE_LIVE_QUOTE_UNAVAILABLE,
        TouchTurnSessionOutcome.NO_TRADE_ENTRY_WINDOW_EXPIRED,
        TouchTurnSessionOutcome.NO_TRADE_ORDER_REJECTED
    )

    private fun failedPhase(session: TouchTurnSessionContext): Phase =
        if (session.failedDuringLiquidityRefetch()) {
            Phase(index = IDX_LIQUIDITY, failed = true)
        } else {
            Phase(index = IDX_DATA, failed = true)
        }

    private fun tradeOrdersCommitted(session: TouchTurnSessionContext?): Boolean =
        session?.ordersPlacedForSession == true ||
            session?.decisionOutcome == TouchTurnSessionOutcome.TRADE_BRACKET_SUBMITTED

    /** Brackets submitted but entry never filled before session close. */
    private fun entryNeverFilled(session: TouchTurnSessionContext?, hasOpenPosition: Boolean): Boolean =
        !hasOpenPosition &&
            tradeOrdersCommitted(session) &&
            session?.milestones?.positionOpenedAt == null

    private fun confirmationStepFailed(
        session: TouchTurnSessionContext?,
        nowEpochMillis: Long
    ): Boolean {
        if (session == null) return false
        if (tradeOrdersCommitted(session)) return false
        when (session.decisionOutcome) {
            TouchTurnSessionOutcome.NO_TRADE_NOT_LIQUIDITY,
            TouchTurnSessionOutcome.NO_TRADE_VOLUME_EXHAUSTION,
            TouchTurnSessionOutcome.NO_TRADE_DOJI -> return false
            in noTradeAfterConfirmationOutcomes -> return true
            else -> Unit
        }
        when (session.entryOrdersPermitted) {
            true -> return false
            false -> {
                if (session.milestones.liquidityEvaluatedAt == null) return false
                return session.decisionOutcome in noTradeAfterConfirmationOutcomes
            }
            null -> Unit
        }
        if (session.milestones.liquidityEvaluatedAt == null) return false
        return session.pipelineCloseConfirmation(nowEpochMillis) in setOf(
            TouchTurnCloseConfirmation.FAILED,
            TouchTurnCloseConfirmation.EXPIRED
        )
    }

    private fun usesNoTradePipeline(
        session: TouchTurnSessionContext?,
        steps: List<TouchTurnBreadcrumbStep>,
        nowEpochMillis: Long
    ): Boolean {
        if (confirmationStepFailed(session, nowEpochMillis)) return true
        if (steps[IDX_CONFIRM].state == TouchTurnBreadcrumbStepState.FAILED) return true
        if (steps[IDX_ORDERS].state == TouchTurnBreadcrumbStepState.SKIPPED &&
            steps[IDX_POSITION].state == TouchTurnBreadcrumbStepState.SKIPPED &&
            steps[IDX_LIQUIDITY].state == TouchTurnBreadcrumbStepState.COMPLETED &&
            session?.decisionOutcome != TouchTurnSessionOutcome.TRADE_BRACKET_SUBMITTED
        ) {
            return true
        }
        return false
    }

    private fun logPipelineGraph(
        instanceId: String,
        symbol: String,
        instance: StrategyDeployment,
        steps: List<TouchTurnBreadcrumbStep>,
        graph: TouchTurnPipelineGraph,
        hasOpenPosition: Boolean,
        hasOpenOrders: Boolean,
        sessionTrades: List<SessionTrade>,
        nowEpochMillis: Long,
        source: String,
        sessionOverride: TouchTurnSessionContext? = null,
        syncTrigger: String = "ui_graph_refresh",
        syncTriggerDetails: Map<String, String> = emptyMap()
    ) {
        val session = sessionOverride ?: instance.touchTurnSession
        val closing = isClosingPhase(
            instance = instance,
            hasOpenPosition = hasOpenPosition,
            hasOpenOrders = hasOpenOrders,
            sessionTrades = sessionTrades,
            nowEpochMillis = nowEpochMillis
        )
        val phase = resolveEffectivePhase(
            instance = instance,
            session = session,
            hasOpenPosition = hasOpenPosition,
            hasOpenOrders = hasOpenOrders,
            sessionTrades = sessionTrades,
            nowEpochMillis = nowEpochMillis
        )
        TouchTurnPipelineLog.graphBuilt(
            instanceId = instanceId,
            symbol = symbol,
            session = session,
            steps = steps,
            graph = graph,
            hasOpenPosition = hasOpenPosition,
            hasOpenOrders = hasOpenOrders,
            closingPhase = closing,
            phaseIndex = phase.index,
            phaseSkippedFrom = phase.skippedFromIndex,
            phaseTerminal = phase.terminal,
            usesNoTradePipeline = usesNoTradePipeline(session, steps, nowEpochMillis),
            closeConfirmation = session?.pipelineCloseConfirmation(nowEpochMillis),
            nowEpochMillis = nowEpochMillis,
            source = source
        )
        if (source == "live") {
            TouchTurnStateSyncLog.recordLive(
                instance = instance,
                steps = steps,
                graph = graph,
                session = session,
                hasOpenPosition = hasOpenPosition,
                hasOpenOrders = hasOpenOrders,
                sessionTrades = sessionTrades,
                closingPhase = closing,
                phaseIndex = phase.index,
                phaseSkippedFrom = phase.skippedFromIndex,
                phaseTerminal = phase.terminal,
                usesNoTradePipeline = usesNoTradePipeline(session, steps, nowEpochMillis),
                closeConfirmation = session?.pipelineCloseConfirmation(nowEpochMillis),
                nowEpochMillis = nowEpochMillis,
                trigger = syncTrigger,
                triggerDetails = syncTriggerDetails
            )
        }
    }

    private fun indexToNodeId(index: Int): TouchTurnPipelineNodeId = when (index) {
        IDX_START -> TouchTurnPipelineNodeId.Start
        IDX_DATA -> TouchTurnPipelineNodeId.Data
        IDX_BAR -> TouchTurnPipelineNodeId.Bar
        IDX_LIQUIDITY -> TouchTurnPipelineNodeId.Liquidity
        IDX_CONFIRM -> TouchTurnPipelineNodeId.Confirmation
        IDX_ORDERS -> TouchTurnPipelineNodeId.Orders
        IDX_POSITION -> TouchTurnPipelineNodeId.Position
        IDX_CLOSE -> TouchTurnPipelineNodeId.Close
        else -> error("Unknown pipeline index $index")
    }
}
