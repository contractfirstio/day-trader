package daytrader.diagnostics

import daytrader.domain.FirstCandleCloseStatus
import daytrader.domain.LiquidityCandleEvaluation
import daytrader.domain.TouchTurnCandleStatus
import daytrader.domain.TouchTurnCloseConfirmation
import daytrader.domain.TouchTurnSessionContext
import daytrader.domain.TouchTurnSessionOutcome
import daytrader.presentation.strategies.TouchTurnBreadcrumbStepState
import daytrader.presentation.strategies.TouchTurnPipelineNodeId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class TouchTurnStateSyncLogTest {
    @Test
    fun findMismatches_emptyWhenSessionNull() {
        assertTrue(
            TouchTurnStateSyncLog.findMismatches(
                engine = engineSnapshot(),
                ui = uiSnapshot(),
                session = null
            ).isEmpty()
        )
    }

    @Test
    fun findMismatches_ordersPlacedWithoutBrokerOpenOrders() {
        val session = TouchTurnSessionContext(
            sessionDate = "2026-05-22",
            status = TouchTurnCandleStatus.READY,
            decisionOutcome = TouchTurnSessionOutcome.TRADE_BRACKET_SUBMITTED,
            ordersPlacedForSession = true
        )
        val mismatches = TouchTurnStateSyncLog.findMismatches(
            engine = engineSnapshot(
                sessionStatus = TouchTurnCandleStatus.READY,
                decisionOutcome = TouchTurnSessionOutcome.TRADE_BRACKET_SUBMITTED,
                ordersPlacedForSession = true,
                hasOpenOrders = false
            ),
            ui = uiSnapshot(phaseIndex = 5),
            session = session
        )
        assertTrue(
            mismatches.any { it.contains("hasOpenOrders=false") },
            "mismatches=$mismatches"
        )
    }

    @Test
    fun findMismatches_uiAheadOfLiquidityMilestone() {
        val session = TouchTurnSessionContext(
            sessionDate = "2026-05-22",
            status = TouchTurnCandleStatus.READY,
            entryOrdersPermitted = null
        )
        val mismatches = TouchTurnStateSyncLog.findMismatches(
            engine = engineSnapshot(sessionStatus = TouchTurnCandleStatus.READY),
            ui = uiSnapshot(
                phaseIndex = 4,
                activePath = listOf(
                    TouchTurnPipelineNodeId.Start,
                    TouchTurnPipelineNodeId.Data,
                    TouchTurnPipelineNodeId.Bar,
                    TouchTurnPipelineNodeId.Liquidity,
                    TouchTurnPipelineNodeId.Confirmation
                )
            ),
            session = session
        )
        assertTrue(
            mismatches.any { it.contains("liquidityEvaluatedAt=null") },
            "mismatches=$mismatches"
        )
    }

    @Test
    fun findMismatches_loadingStatus_wrongPhase() {
        val session = TouchTurnSessionContext(
            sessionDate = "2026-05-22",
            status = TouchTurnCandleStatus.LOADING
        )
        val mismatches = TouchTurnStateSyncLog.findMismatches(
            engine = engineSnapshot(sessionStatus = TouchTurnCandleStatus.LOADING),
            ui = uiSnapshot(phaseIndex = 2),
            session = session
        )
        assertEquals(listOf("engine status=LOADING but ui phaseIndex=2"), mismatches)
    }

    @Test
    fun findMismatches_noTradeOutcome_ordersOnPath() {
        val session = TouchTurnSessionContext(
            sessionDate = "2026-05-22",
            status = TouchTurnCandleStatus.READY,
            decisionOutcome = TouchTurnSessionOutcome.NO_TRADE_NOT_LIQUIDITY
        )
        val mismatches = TouchTurnStateSyncLog.findMismatches(
            engine = engineSnapshot(
                sessionStatus = TouchTurnCandleStatus.READY,
                decisionOutcome = TouchTurnSessionOutcome.NO_TRADE_NOT_LIQUIDITY
            ),
            ui = uiSnapshot(
                activePath = listOf(
                    TouchTurnPipelineNodeId.Start,
                    TouchTurnPipelineNodeId.Data,
                    TouchTurnPipelineNodeId.Bar,
                    TouchTurnPipelineNodeId.Liquidity,
                    TouchTurnPipelineNodeId.Orders
                ),
                phaseTerminal = true,
                usesNoTradePipeline = false
            ),
            session = session
        )
        assertTrue(mismatches.any { it.contains("Orders on ui activePath") })
        assertTrue(mismatches.any { it.contains("NoTrade missing") })
    }

    @Test
    fun findMismatches_waitingForEntry_positionMustStayUpcoming() {
        val session = TouchTurnSessionContext(
            sessionDate = "2026-05-22",
            status = TouchTurnCandleStatus.READY,
            ordersPlacedForSession = true
        )
        val stepStates = List(8) { TouchTurnBreadcrumbStepState.UPCOMING }.toMutableList()
        stepStates[5] = TouchTurnBreadcrumbStepState.CURRENT
        stepStates[6] = TouchTurnBreadcrumbStepState.CURRENT
        val mismatches = TouchTurnStateSyncLog.findMismatches(
            engine = engineSnapshot(
                sessionStatus = TouchTurnCandleStatus.READY,
                ordersPlacedForSession = true,
                hasOpenPosition = false
            ),
            ui = uiSnapshot(stepStates = stepStates),
            session = session
        )
        assertEquals(
            listOf(
                "engine ordersPlacedForSession=true but broker hasOpenOrders=false (see bracket_submit_requested / bracket_acknowledged / emulator bracket_placed)",
                "engine waiting for entry but ui Position step=CURRENT"
            ),
            mismatches
        )
    }

    @Test
    fun findMismatches_openPosition_positionStepNotCurrent() {
        val session = TouchTurnSessionContext(
            sessionDate = "2026-05-22",
            status = TouchTurnCandleStatus.READY
        )
        val stepStates = List(8) { TouchTurnBreadcrumbStepState.UPCOMING }.toMutableList()
        stepStates[6] = TouchTurnBreadcrumbStepState.UPCOMING
        val mismatches = TouchTurnStateSyncLog.findMismatches(
            engine = engineSnapshot(
                sessionStatus = TouchTurnCandleStatus.READY,
                hasOpenPosition = true
            ),
            ui = uiSnapshot(stepStates = stepStates),
            session = session
        )
        assertEquals(
            listOf("engine hasOpenPosition=true but ui Position step=UPCOMING"),
            mismatches
        )
    }

    @Test
    fun findMismatches_closingPhase_closeNotOnPath() {
        val session = TouchTurnSessionContext(
            sessionDate = "2026-05-22",
            status = TouchTurnCandleStatus.READY,
            ordersPlacedForSession = true
        )
        val stepStates = List(8) { TouchTurnBreadcrumbStepState.UPCOMING }.toMutableList()
        stepStates[5] = TouchTurnBreadcrumbStepState.COMPLETED
        stepStates[6] = TouchTurnBreadcrumbStepState.SKIPPED
        val mismatches = TouchTurnStateSyncLog.findMismatches(
            engine = engineSnapshot(
                sessionStatus = TouchTurnCandleStatus.READY,
                ordersPlacedForSession = true,
                closingPhase = true
            ),
            ui = uiSnapshot(
                activePath = listOf(
                    TouchTurnPipelineNodeId.Start,
                    TouchTurnPipelineNodeId.Data,
                    TouchTurnPipelineNodeId.Bar,
                    TouchTurnPipelineNodeId.Liquidity,
                    TouchTurnPipelineNodeId.Confirmation,
                    TouchTurnPipelineNodeId.Orders,
                    TouchTurnPipelineNodeId.Position
                ),
                stepStates = stepStates
            ),
            session = session
        )
        assertEquals(listOf("engine closingPhase=true but Close not on ui activePath"), mismatches)
    }

    private fun engineSnapshot(
        sessionStatus: TouchTurnCandleStatus? = TouchTurnCandleStatus.READY,
        decisionOutcome: TouchTurnSessionOutcome? = null,
        entryOrdersPermitted: Boolean? = null,
        ordersPlacedForSession: Boolean = false,
        hasOpenPosition: Boolean = false,
        hasOpenOrders: Boolean = false,
        closingPhase: Boolean = false
    ) = TouchTurnStateSyncLog.EngineSnapshot(
        sessionStatus = sessionStatus,
        decisionOutcome = decisionOutcome,
        entryOrdersPermitted = entryOrdersPermitted,
        ordersPlacedForSession = ordersPlacedForSession,
        candleCloseStatus = FirstCandleCloseStatus.CLOSED,
        liquidityEvaluation = LiquidityCandleEvaluation.LIQUIDITY,
        closeConfirmation = TouchTurnCloseConfirmation.PASSED,
        hasOpenPosition = hasOpenPosition,
        hasOpenOrders = hasOpenOrders,
        closingPhase = closingPhase,
        tradeCycleComplete = closingPhase && !hasOpenPosition && !hasOpenOrders
    )

    private fun uiSnapshot(
        activePath: List<TouchTurnPipelineNodeId> = listOf(TouchTurnPipelineNodeId.Start),
        stepStates: List<TouchTurnBreadcrumbStepState> = List(8) { TouchTurnBreadcrumbStepState.UPCOMING },
        phaseIndex: Int = 1,
        phaseTerminal: Boolean = false,
        usesNoTradePipeline: Boolean = false
    ) = TouchTurnStateSyncLog.UiSnapshot(
        activePath = activePath,
        stepStates = stepStates,
        phaseIndex = phaseIndex,
        phaseSkippedFrom = null,
        phaseTerminal = phaseTerminal,
        usesNoTradePipeline = usesNoTradePipeline,
        caption = ""
    )
}
