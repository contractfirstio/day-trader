package daytrader.presentation.strategies

import daytrader.domain.DeploymentSessionStopAction
import daytrader.domain.DeploymentSessionStopLogic
import daytrader.domain.FirstCandleCloseStatus
import daytrader.domain.LiquidityCandleEvaluation
import daytrader.domain.SessionTrade
import daytrader.domain.SessionStatus
import daytrader.domain.StrategyDeployment
import daytrader.domain.StrategyType
import daytrader.domain.TouchTurnCandleStatus
import daytrader.domain.TouchTurnMilestoneTimestamps
import daytrader.domain.TouchTurnSessionContext
import daytrader.domain.inProgressSession
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
    private const val IDX_ORDERS = 4
    private const val IDX_POSITION = 5
    private const val IDX_CLOSE = 6

    private val pipelineLabels = listOf(
        "Starting session",
        "Data",
        "Bar",
        "Liquidity",
        "Orders",
        "Position",
        "Closing session"
    )

    fun steps(
        instance: StrategyDeployment,
        hasOpenPosition: Boolean,
        hasOpenOrders: Boolean = false,
        nowEpochMillis: Long = System.currentTimeMillis()
    ): List<TouchTurnBreadcrumbStep> {
        val session = instance.touchTurnSession
        val sessionTrades = instance.inProgressSession()?.sessionTrades ?: emptyList()
        val milestones = session?.milestones ?: TouchTurnMilestoneTimestamps()
        val closing = isClosingPhase(
            instance = instance,
            hasOpenPosition = hasOpenPosition,
            hasOpenOrders = hasOpenOrders,
            sessionTrades = sessionTrades,
            nowEpochMillis = nowEpochMillis
        )
        val phase = if (closing) {
            Phase(index = IDX_CLOSE)
        } else {
            resolvePhase(session, hasOpenPosition, nowEpochMillis)
        }
        if (phase.failed) {
            return pipelineLabels.mapIndexed { index, label ->
                val state = when {
                    index == IDX_DATA -> TouchTurnBreadcrumbStepState.FAILED
                    index < IDX_DATA -> TouchTurnBreadcrumbStepState.COMPLETED
                    else -> TouchTurnBreadcrumbStepState.UPCOMING
                }
                TouchTurnBreadcrumbStep(
                    label = label,
                    state = state,
                    timestamp = timestampForStep(index, milestones, instance, phase, state)
                )
            }
        }
        return pipelineLabels.mapIndexed { index, label ->
            val state = when {
                phase.skippedFromIndex != null && index >= phase.skippedFromIndex &&
                    index < IDX_CLOSE ->
                    TouchTurnBreadcrumbStepState.SKIPPED
                phase.terminal && index <= phase.index -> TouchTurnBreadcrumbStepState.COMPLETED
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
                phase.failed -> milestones.dataFailedAt
                else -> milestones.dataReadyAt
            }
            IDX_BAR -> milestones.barClosedAt
            IDX_LIQUIDITY -> milestones.liquidityEvaluatedAt
            IDX_ORDERS -> milestones.ordersPlacedAt
            IDX_POSITION -> milestones.positionOpenedAt
            IDX_CLOSE -> milestones.closingSessionAt
            else -> null
        }
        return Formatters.milestoneTimeFromIso(iso)
    }

    private fun isClosingPhase(
        instance: StrategyDeployment,
        hasOpenPosition: Boolean,
        hasOpenOrders: Boolean,
        sessionTrades: List<SessionTrade>,
        nowEpochMillis: Long
    ): Boolean {
        if (DeploymentSessionStopLogic.shouldStopAfterTradeOutcome(
                instance = instance,
                sessionTrades = sessionTrades,
                hasOpenPosition = hasOpenPosition,
                hasOpenOrders = hasOpenOrders
            )
        ) {
            return true
        }
        if (instance.touchTurnSession?.status != TouchTurnCandleStatus.READY) return false
        return DeploymentSessionStopLogic.evaluateDeadlineForInstance(instance, nowEpochMillis) ==
            DeploymentSessionStopAction.STOP_AFTER_OPEN_DEADLINE
    }

    private data class Phase(
        val index: Int,
        val failed: Boolean = false,
        val skippedFromIndex: Int? = null,
        val terminal: Boolean = false
    )

    private fun resolvePhase(
        session: TouchTurnSessionContext?,
        hasOpenPosition: Boolean,
        nowEpochMillis: Long
    ): Phase {
        if (session == null) return Phase(index = IDX_START)
        when (session.status) {
            TouchTurnCandleStatus.LOADING -> return Phase(index = IDX_DATA)
            TouchTurnCandleStatus.FAILED -> return Phase(index = IDX_DATA, failed = true)
            TouchTurnCandleStatus.READY -> Unit
        }

        if (hasOpenPosition) return Phase(index = IDX_POSITION)

        val closeStatus = session.candleCloseStatus(nowEpochMillis)
        if (closeStatus != FirstCandleCloseStatus.CLOSED) {
            return Phase(index = IDX_BAR)
        }

        val liquidity = session.liquidityEvaluation(nowEpochMillis)
        when (liquidity) {
            LiquidityCandleEvaluation.AWAITING_CLOSE -> return Phase(index = IDX_BAR)
            LiquidityCandleEvaluation.UNKNOWN -> return Phase(index = IDX_LIQUIDITY)
            LiquidityCandleEvaluation.NOT_LIQUIDITY ->
                return Phase(index = IDX_LIQUIDITY, skippedFromIndex = IDX_ORDERS, terminal = true)
            LiquidityCandleEvaluation.LIQUIDITY -> Unit
        }

        if (session.ordersPlacedForSession) {
            return Phase(index = IDX_POSITION)
        }

        val entryPermitted = session.entryOrdersPermitted
        if (entryPermitted == false) {
            return Phase(index = IDX_ORDERS, skippedFromIndex = IDX_POSITION, terminal = true)
        }

        if (entryPermitted == true) {
            return Phase(index = IDX_ORDERS)
        }

        return Phase(index = IDX_LIQUIDITY)
    }

    /** Most recent closed session with a persisted pipeline log (Live tab after stop). */
    fun pipelineForLastClosedSession(instance: StrategyDeployment): List<TouchTurnBreadcrumbStep>? {
        if (instance.strategyType != StrategyType.TOUCH_AND_TURN_SCALPER) return null
        val run = instance.sessionHistory
            .filter { it.status == SessionStatus.CLOSED && it.touchTurnMilestones != null }
            .maxByOrNull { it.stoppedAt.ifBlank { it.startedAt } }
            ?: return null
        val milestones = run.touchTurnMilestones ?: return null
        return stepsFromHistory(
            milestones = milestones,
            startedAt = run.startedAt,
            stoppedAt = run.stoppedAt,
            hadLiquidityCandle = run.hadLiquidityCandle,
            ordersPlacedForCandle = run.ordersPlacedForCandle,
            positionOpened = run.positionOpened
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
        positionOpened: Boolean?
    ): List<TouchTurnBreadcrumbStep> {
        val ordersSkipped = hadLiquidityCandle == false
        val positionSkipped = ordersSkipped || (ordersPlacedForCandle != true && positionOpened != true)
        val dataFailed = milestones.dataFailedAt != null
        return pipelineLabels.mapIndexed { index, label ->
            val state = when (index) {
                IDX_DATA -> when {
                    dataFailed -> TouchTurnBreadcrumbStepState.FAILED
                    milestones.dataReadyAt != null -> TouchTurnBreadcrumbStepState.COMPLETED
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
                else -> TouchTurnBreadcrumbStepState.COMPLETED
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
            IDX_ORDERS -> milestones.ordersPlacedAt
            IDX_POSITION -> milestones.positionOpenedAt
            IDX_CLOSE -> milestones.closingSessionAt ?: stoppedAt.takeIf { it.isNotBlank() }
            else -> null
        }
        return Formatters.milestoneTimeFromIso(iso)
    }
}
