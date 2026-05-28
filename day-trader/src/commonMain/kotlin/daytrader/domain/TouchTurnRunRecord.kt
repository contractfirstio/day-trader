package daytrader.domain

import daytrader.gateway.BrokerId
import kotlinx.serialization.Serializable

@Serializable
enum class TouchTurnSessionStartedBy {
    MANUAL,
    AUTO_MARKET_OPEN
}

/** Primary no-trade / trade decision for a closed Touch Turn run (written once when known). */
@Serializable
enum class TouchTurnSessionOutcome {
    NO_TRADE_DATA_FAILED,
    NO_TRADE_NOT_LIQUIDITY,
    NO_TRADE_DOJI,
    NO_TRADE_CLOSE_CONFIRMATION_FAILED,
    NO_TRADE_ENTRY_WINDOW_EXPIRED,
    NO_TRADE_ORDER_REJECTED,
    TRADE_BRACKET_SUBMITTED
}

@Serializable
enum class TouchTurnSessionStopTrigger {
    TRADE_OUTCOME_KNOWN,
    OPEN_DEADLINE,
    MANUAL,
    PRE_MARKET_CLOSE,
    ERROR
}

@Serializable
data class TouchTurnPlannedBracket(
    val side: TouchTurnTradeSide,
    val entry: Double,
    val stopLoss: Double,
    val takeProfit: Double
)

@Serializable
data class TouchTurnRunContext(
    val maxDollars: Int,
    val startedBy: TouchTurnSessionStartedBy,
    val brokerId: BrokerId
)

@Serializable
data class TouchTurnRunMarketInputs(
    val openingBar: OhlcBar? = null,
    val adr14: Double? = null,
    val currencyCode: String = "USD",
    val marketZoneId: String = "America/New_York",
    val dataErrorMessage: String? = null
)

@Serializable
data class TouchTurnSessionDecision(
    val outcome: TouchTurnSessionOutcome,
    val plannedQuantity: Int? = null,
    val plannedBracket: TouchTurnPlannedBracket? = null,
    /** Filled bracket legs for this run (computed at session stop from broker fills). */
    val executedLegs: List<TouchTurnOrderRole> = emptyList()
)

@Serializable
data class TouchTurnStopEvent(
    val stopTrigger: TouchTurnSessionStopTrigger,
    val stopErrorMessage: String? = null,
    val brokerUnrealizedPnLAtStop: Double? = null
)

/** Frozen Touch Turn facts for a closed performance row. */
@Serializable
data class TouchTurnRunRecord(
    val runContext: TouchTurnRunContext,
    val marketInputs: TouchTurnRunMarketInputs,
    val decision: TouchTurnSessionDecision,
    val stopEvent: TouchTurnStopEvent,
    val milestones: TouchTurnMilestoneTimestamps
)

fun TouchTurnOrderPlan.toPlannedBracket(): TouchTurnPlannedBracket =
    TouchTurnPlannedBracket(
        side = side,
        entry = orders.first { it.role == TouchTurnOrderRole.ENTRY }.price,
        stopLoss = orders.first { it.role == TouchTurnOrderRole.STOP_LOSS }.price,
        takeProfit = orders.first { it.role == TouchTurnOrderRole.TAKE_PROFIT }.price
    )

fun resolveTouchTurnSessionOutcome(session: TouchTurnSessionContext): TouchTurnSessionOutcome {
    session.decisionOutcome?.let { return it }
    if (session.status == TouchTurnCandleStatus.FAILED) return TouchTurnSessionOutcome.NO_TRADE_DATA_FAILED
    if (session.ordersPlacedForSession) return TouchTurnSessionOutcome.TRADE_BRACKET_SUBMITTED
    val setup = session.setup ?: return TouchTurnSessionOutcome.NO_TRADE_DATA_FAILED
    val liquidityEvaluatedAt = session.milestones.liquidityEvaluatedAt?.let(::parseIsoToEpochMillis)
    val evalInstant = liquidityEvaluatedAt ?: System.currentTimeMillis()
    if (!setup.isLiquidityCandle) return TouchTurnSessionOutcome.NO_TRADE_NOT_LIQUIDITY
    if (!setup.isActionable) return TouchTurnSessionOutcome.NO_TRADE_DOJI
    if (session.closeConfirmation(evalInstant) == TouchTurnCloseConfirmation.FAILED) {
        return TouchTurnSessionOutcome.NO_TRADE_CLOSE_CONFIRMATION_FAILED
    }
    if (session.entryWindowStatus(evalInstant) == TouchTurnEntryWindowStatus.EXPIRED) {
        return TouchTurnSessionOutcome.NO_TRADE_ENTRY_WINDOW_EXPIRED
    }
    return TouchTurnSessionOutcome.NO_TRADE_ORDER_REJECTED
}

/**
 * Resolves why a Touch Turn run stopped. Prefers an explicit watcher/UI value, then infers from
 * flat + completed trade cycle (position closed / bracket outcome), open deadline, or data failure.
 */
fun inferTouchTurnStopTrigger(
    instance: StrategyDeployment,
    sessionTrades: List<SessionTrade>,
    hasOpenPosition: Boolean,
    hasOpenOrders: Boolean,
    explicit: TouchTurnSessionStopTrigger? = null,
    nowEpochMillis: Long = System.currentTimeMillis()
): TouchTurnSessionStopTrigger {
    explicit?.let { return it }
    if (instance.touchTurnSession?.status == TouchTurnCandleStatus.FAILED) {
        return TouchTurnSessionStopTrigger.ERROR
    }
    if (DeploymentSessionStopLogic.shouldStopAfterTradeOutcome(
            instance = instance,
            sessionTrades = sessionTrades,
            hasOpenPosition = hasOpenPosition,
            hasOpenOrders = hasOpenOrders
        )
    ) {
        return TouchTurnSessionStopTrigger.TRADE_OUTCOME_KNOWN
    }
    if (DeploymentSessionStopLogic.evaluateDeadlineForInstance(instance, nowEpochMillis) ==
        DeploymentSessionStopAction.STOP_AFTER_OPEN_DEADLINE
    ) {
        return TouchTurnSessionStopTrigger.OPEN_DEADLINE
    }
    return TouchTurnSessionStopTrigger.MANUAL
}

fun buildTouchTurnRunRecord(
    session: StrategySession,
    touchTurnSession: TouchTurnSessionContext,
    stopTrigger: TouchTurnSessionStopTrigger,
    brokerId: BrokerId,
    brokerUnrealizedPnLAtStop: Double?,
    stopErrorMessage: String? = null,
    sessionTrades: List<SessionTrade> = emptyList()
): TouchTurnRunRecord {
    val outcome = resolveTouchTurnSessionOutcome(touchTurnSession)
    val plannedBracket = touchTurnSession.plannedBracket
        ?: touchTurnSession.setup?.takeIf { outcome == TouchTurnSessionOutcome.TRADE_BRACKET_SUBMITTED }
            ?.let { setup ->
                TouchTurnPlannedBracket(
                    side = setup.side,
                    entry = setup.entry,
                    stopLoss = setup.stopLoss,
                    takeProfit = setup.takeProfit
                )
            }
    val fillPnl = sessionTrades.sessionRealizedPnL()
    val executedLegs = TouchTurnBracketExecution.resolveFromTrades(
        trades = sessionTrades,
        plannedBracket = plannedBracket,
        bracketSetup = touchTurnSession.setup,
        sessionPnl = fillPnl.takeIf { sessionTrades.isNotEmpty() && it != 0.0 }
            ?: session.pnl.takeIf { sessionTrades.isNotEmpty() }
    )
    return TouchTurnRunRecord(
        runContext = TouchTurnRunContext(
            maxDollars = session.maxAtRisk,
            startedBy = session.touchTurnStartedBy ?: TouchTurnSessionStartedBy.MANUAL,
            brokerId = brokerId
        ),
        marketInputs = TouchTurnRunMarketInputs(
            openingBar = touchTurnSession.candle,
            adr14 = touchTurnSession.adr14,
            currencyCode = touchTurnSession.currencyCode,
            marketZoneId = touchTurnSession.marketZoneId,
            dataErrorMessage = touchTurnSession.errorMessage
        ),
        decision = TouchTurnSessionDecision(
            outcome = outcome,
            plannedQuantity = touchTurnSession.plannedQuantity,
            plannedBracket = plannedBracket,
            executedLegs = executedLegs
        ),
        stopEvent = TouchTurnStopEvent(
            stopTrigger = stopTrigger,
            stopErrorMessage = stopErrorMessage,
            brokerUnrealizedPnLAtStop = brokerUnrealizedPnLAtStop
        ),
        milestones = touchTurnSession.milestones
    )
}

private fun parseIsoToEpochMillis(iso: String): Long? = runCatching {
    java.time.LocalDateTime.parse(iso, java.time.format.DateTimeFormatter.ISO_LOCAL_DATE_TIME)
        .atZone(java.time.ZoneId.systemDefault())
        .toInstant()
        .toEpochMilli()
}.getOrNull()
