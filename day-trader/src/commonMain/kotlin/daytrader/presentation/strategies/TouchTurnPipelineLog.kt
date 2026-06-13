package daytrader.presentation.strategies

import daytrader.diagnostics.TimestampedConsoleLog
import daytrader.domain.TouchTurnCloseConfirmation
import daytrader.domain.TouchTurnMilestoneTimestamps
import daytrader.domain.TouchTurnSessionContext
import daytrader.domain.TouchTurnSessionOutcome

/**
 * Console diagnostics for the Touch Turn pipeline graph (active path, step states, edges).
 * Disabled when `DAY_TRADER_TOUCH_TURN_CANDLE_LOGS=false` (same as [daytrader.domain.TouchTurnDecisionLog]).
 */
object TouchTurnPipelineLog {
    private val enabled: Boolean
        get() = System.getenv("DAY_TRADER_TOUCH_TURN_CANDLE_LOGS")
            ?.equals("false", ignoreCase = true) != true

    fun graphBuilt(
        instanceId: String,
        symbol: String,
        session: TouchTurnSessionContext?,
        steps: List<TouchTurnBreadcrumbStep>,
        graph: TouchTurnPipelineGraph,
        hasOpenPosition: Boolean,
        hasOpenOrders: Boolean,
        closingPhase: Boolean,
        phaseIndex: Int,
        phaseSkippedFrom: Int?,
        phaseTerminal: Boolean,
        usesNoTradePipeline: Boolean,
        closeConfirmation: TouchTurnCloseConfirmation?,
        nowEpochMillis: Long,
        source: String
    ) {
        if (!enabled) return
        val confirmFailed = steps.getOrNull(4)?.state == TouchTurnBreadcrumbStepState.FAILED
        val interesting = usesNoTradePipeline || closingPhase ||
            session?.decisionOutcome != null || confirmFailed
        if (!interesting && source == "live") return
        val path = graph.activePath.joinToString(">") { it.name }
        line(
            "pipeline source=$source instance=$instanceId symbol=$symbol " +
                "path=$path closingPhase=$closingPhase usesNoTrade=$usesNoTradePipeline"
        )
        detail(
            "  session decisionOutcome=${session?.decisionOutcome?.name ?: "null"} " +
                "entryOrdersPermitted=${session?.entryOrdersPermitted} " +
                "ordersPlaced=${session?.ordersPlacedForSession} " +
                "closeConfirmation=${closeConfirmation?.name ?: "n/a"} " +
                "hasOpenPosition=$hasOpenPosition hasOpenOrders=$hasOpenOrders"
        )
        detail(
            "  phase index=$phaseIndex terminal=$phaseTerminal skippedFrom=$phaseSkippedFrom " +
                "confirmFailedOnSteps=${steps.getOrNull(4)?.state?.name} " +
                "orders=${steps.getOrNull(3)?.state?.name} position=${steps.getOrNull(4)?.state?.name}"
        )
        detail(
            "  stepStates=${steps.map { it.state.name }.joinToString(",")} " +
                "labels=${steps.map { it.label }.joinToString("|")}"
        )
        milestonesDetail(session?.milestones)
        edgeDiagnostics(graph, session, usesNoTradePipeline)
        if (usesNoTradePipeline && TouchTurnPipelineNodeId.Orders in graph.activePath) {
            detail(
                "  WARN: no-trade pipeline but Orders is on activePath — check decisionOutcome / " +
                    "confirmationStepFailed / entryOrdersPermitted"
            )
        }
        if (session?.decisionOutcome in noTradeOutcomes &&
            TouchTurnPipelineNodeId.Close !in graph.activePath
        ) {
            detail(
                "  WARN: decisionOutcome=${session?.decisionOutcome?.name} but Close not on activePath"
            )
        }
    }

    private fun milestonesDetail(milestones: TouchTurnMilestoneTimestamps?) {
        val m = milestones ?: return
        detail(
            "  milestones barClosed=${m.barClosedAt != null} liquidity=${m.liquidityEvaluatedAt != null} " +
                "closeConfirmed=${m.closeConfirmedAt != null} orders=${m.ordersPlacedAt != null} " +
                "position=${m.positionOpenedAt != null} closing=${m.closingSessionAt != null}"
        )
    }

    private fun edgeDiagnostics(
        graph: TouchTurnPipelineGraph,
        session: TouchTurnSessionContext?,
        usesNoTradePipeline: Boolean
    ) {
        fun edge(from: TouchTurnPipelineNodeId, to: TouchTurnPipelineNodeId): String =
            graph.edges.firstOrNull { it.from == from && it.to == to }?.state?.name ?: "missing"

        detail(
            "  edges Rules→Orders=${edge(TouchTurnPipelineNodeId.Rules, TouchTurnPipelineNodeId.Orders)} " +
                "Rules→Close=${edge(TouchTurnPipelineNodeId.Rules, TouchTurnPipelineNodeId.Close)} " +
                "Orders→Position=${edge(TouchTurnPipelineNodeId.Orders, TouchTurnPipelineNodeId.Position)} " +
                "Orders→Close=${edge(TouchTurnPipelineNodeId.Orders, TouchTurnPipelineNodeId.Close)} " +
                "Position→Close=${edge(TouchTurnPipelineNodeId.Position, TouchTurnPipelineNodeId.Close)}"
        )
        graph.caption.takeIf { it.isNotBlank() }?.let { detail("  caption=$it") }
        if (usesNoTradePipeline) {
            detail("  expectedPath=…→Rules→Close (not Orders/Position)")
        }
    }

    private val noTradeOutcomes = setOf(
        TouchTurnSessionOutcome.NO_TRADE_DATA_FAILED,
        TouchTurnSessionOutcome.NO_TRADE_NOT_LIQUIDITY,
        TouchTurnSessionOutcome.NO_TRADE_DOJI,
        TouchTurnSessionOutcome.NO_TRADE_CLOSE_CONFIRMATION_FAILED,
        TouchTurnSessionOutcome.NO_TRADE_BOUNCE_REJECTION_FAILED,
        TouchTurnSessionOutcome.NO_TRADE_BOUNCE_DATA_UNAVAILABLE,
        TouchTurnSessionOutcome.NO_TRADE_LIVE_CLOSE_CONFIRMATION_FAILED,
        TouchTurnSessionOutcome.NO_TRADE_BAR_LIVE_DIVERGENCE,
        TouchTurnSessionOutcome.NO_TRADE_ENTRY_NOT_TOUCHABLE,
        TouchTurnSessionOutcome.NO_TRADE_LIVE_QUOTE_UNAVAILABLE,
        TouchTurnSessionOutcome.NO_TRADE_ENTRY_WINDOW_EXPIRED,
        TouchTurnSessionOutcome.NO_TRADE_ORDER_REJECTED,
        TouchTurnSessionOutcome.NO_TRADE_VOLUME_EXHAUSTION,
        TouchTurnSessionOutcome.NO_TRADE_MACRO_TREND_MISALIGNED,
        TouchTurnSessionOutcome.NO_TRADE_MACRO_TREND_DATA_UNAVAILABLE,
        TouchTurnSessionOutcome.NO_TRADE_STOCK_TREND_MISALIGNED,
        TouchTurnSessionOutcome.NO_TRADE_STOCK_TREND_DATA_UNAVAILABLE
    )

    private fun line(message: String) {
        TimestampedConsoleLog.line("TouchTurn", message)
    }

    private fun detail(message: String) {
        TimestampedConsoleLog.line("TouchTurn", message)
    }
}
