package daytrader.domain

import daytrader.gateway.BrokerId
import daytrader.gateway.BrokerKind
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
    /** Opening-bar price samples insufficient for extreme bounce evaluation. */
    NO_TRADE_BOUNCE_DATA_UNAVAILABLE,
    /** Extreme bounce count below the configured minimum when bounce rejection is enabled. */
    NO_TRADE_BOUNCE_REJECTION_FAILED,
    /** Live tape no longer on the confirming side of entry at decision time (hybrid / live data). */
    NO_TRADE_LIVE_CLOSE_CONFIRMATION_FAILED,
    /** Completed bar close and live bid/ask mid disagree beyond tolerance (hybrid / live data). */
    NO_TRADE_BAR_LIVE_DIVERGENCE,
    NO_TRADE_ENTRY_WINDOW_EXPIRED,
    /** Resting entry would be marketable — live price already through the touch level. */
    NO_TRADE_ENTRY_NOT_TOUCHABLE,
    /** Bid/ask unavailable when live price gates are required. */
    NO_TRADE_LIVE_QUOTE_UNAVAILABLE,
    NO_TRADE_ORDER_REJECTED,
    /** Opening 15m volume exceeded exhaustion threshold (high-conviction breakout). */
    NO_TRADE_VOLUME_EXHAUSTION,
    /** SPY macro trend did not match fade direction when macro trend alignment is enabled. */
    NO_TRADE_MACRO_TREND_MISALIGNED,
    /** Home-market index regime data could not be loaded for macro trend alignment. */
    NO_TRADE_MACRO_TREND_DATA_UNAVAILABLE,
    /** Symbol daily trend did not match fade direction when stock trend alignment is enabled. */
    NO_TRADE_STOCK_TREND_MISALIGNED,
    /** Symbol daily trend inputs could not be loaded for stock trend alignment. */
    NO_TRADE_STOCK_TREND_DATA_UNAVAILABLE,
    TRADE_BRACKET_SUBMITTED
}

@Serializable
enum class TouchTurnSessionStopTrigger {
    TRADE_OUTCOME_KNOWN,
    /** Liquidity / confirmation / order gate resolved with no trade. */
    NO_TRADE_DECISION,
    OPEN_DEADLINE,
    MANUAL,
    PRE_MARKET_CLOSE,
    ERROR,
    /** App quit or startup recovery of a persisted in-progress run from a prior process. */
    APPLICATION_SHUTDOWN
}

@Serializable
data class TouchTurnPlannedBracket(
    val side: TouchTurnTradeSide,
    val entry: Double,
    val stopLoss: Double,
    val takeProfit: Double,
    /** Favorable-move price that arms the adjustable trailing stop (null when trailing disabled). */
    val trailTriggerPrice: Double? = null
)

@Serializable
data class TouchTurnRunContext(
    val maxDollars: Int,
    val startedBy: TouchTurnSessionStartedBy,
    val brokerId: BrokerId,
    /** Startup broker mode (e.g. paper-live vs pure emulator); null on legacy rows. */
    val brokerKind: BrokerKind? = null,
    /** Pre-flight checks snapshotted when the session was started; null on legacy rows. */
    val prepareSnapshot: TouchTurnPrepareSnapshot? = null,
    /** Continuation mode (inverted long/short at the same entry); null/false on legacy rows. */
    val invertTradeSide: Boolean = false
)

@Serializable
data class TouchTurnRunMarketInputs(
    val openingBar: OhlcBar? = null,
    val adr14: Double? = null,
    /** 14-period ATR on prior 15m bars (liquidity range threshold input). */
    val atr14: Double? = null,
    /** Wilder daily ATR(14) on completed daily bars (ProReal-style liquidity input). */
    val dailyAtr14: Double? = null,
    /** Legacy field — volume gates removed. */
    val volumeSma20: Double? = null,
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
    val milestones: TouchTurnMilestoneTimestamps,
    /** Rule thresholds and enable flags in effect when this run ended. */
    val rules: TouchTurnRuleConfig? = null
)

fun TouchTurnOrderPlan.toPlannedBracket(): TouchTurnPlannedBracket {
    val stopLeg = orders.first { it.role == TouchTurnOrderRole.STOP_LOSS }
    return TouchTurnPlannedBracket(
        side = side,
        entry = orders.first { it.role == TouchTurnOrderRole.ENTRY }.price,
        stopLoss = stopLeg.price,
        takeProfit = orders.first { it.role == TouchTurnOrderRole.TAKE_PROFIT }.price,
        trailTriggerPrice = stopLeg.trailTriggerPrice
    )
}

fun resolveTouchTurnSessionOutcome(session: TouchTurnSessionContext): TouchTurnSessionOutcome {
    session.decisionOutcome?.let { return it }
    if (session.status == TouchTurnCandleStatus.FAILED) return TouchTurnSessionOutcome.NO_TRADE_DATA_FAILED
    if (session.ordersPlacedForSession) return TouchTurnSessionOutcome.TRADE_BRACKET_SUBMITTED
    val setup = session.setup ?: return TouchTurnSessionOutcome.NO_TRADE_DATA_FAILED
    val liquidityEvaluatedAt = session.milestones.liquidityEvaluatedAt?.let(::parseIsoToEpochMillis)
    val evalInstant = liquidityEvaluatedAt ?: System.currentTimeMillis()
    TouchTurnLogic.barSetupBlockOutcome(setup, session.rules)?.let { return it }
    when (session.entryOrdersPermitted) {
        true ->
            return if (session.ordersPlacedForSession) {
                TouchTurnSessionOutcome.TRADE_BRACKET_SUBMITTED
            } else {
                TouchTurnSessionOutcome.NO_TRADE_ORDER_REJECTED
            }
        false -> {
            session.decisionOutcome?.let { return it }
            when (session.pipelineCloseConfirmation(evalInstant)) {
                TouchTurnCloseConfirmation.FAILED ->
                    return TouchTurnSessionOutcome.NO_TRADE_CLOSE_CONFIRMATION_FAILED
                TouchTurnCloseConfirmation.EXPIRED ->
                    return TouchTurnSessionOutcome.NO_TRADE_ENTRY_WINDOW_EXPIRED
                else -> return TouchTurnSessionOutcome.NO_TRADE_ORDER_REJECTED
            }
        }
        null -> when (session.pipelineCloseConfirmation(evalInstant)) {
            TouchTurnCloseConfirmation.FAILED ->
                return TouchTurnSessionOutcome.NO_TRADE_CLOSE_CONFIRMATION_FAILED
            TouchTurnCloseConfirmation.EXPIRED ->
                return TouchTurnSessionOutcome.NO_TRADE_ENTRY_WINDOW_EXPIRED
            else -> Unit
        }
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
    if (DeploymentSessionStopLogic.shouldStopAfterNoTradeDecision(instance)) {
        return TouchTurnSessionStopTrigger.NO_TRADE_DECISION
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
    brokerKind: BrokerKind? = null,
    brokerUnrealizedPnLAtStop: Double?,
    stopErrorMessage: String? = null,
    sessionTrades: List<SessionTrade> = emptyList(),
    invertTradeSide: Boolean = false
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
            brokerId = brokerId,
            brokerKind = brokerKind,
            prepareSnapshot = touchTurnSession.prepareSnapshot,
            invertTradeSide = invertTradeSide
        ),
        marketInputs = TouchTurnRunMarketInputs(
            openingBar = touchTurnSession.candle,
            adr14 = touchTurnSession.adr14,
            atr14 = touchTurnSession.atr14,
            dailyAtr14 = touchTurnSession.dailyAtr14,
            volumeSma20 = touchTurnSession.volumeSma20,
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
        milestones = touchTurnSession.milestones,
        rules = touchTurnSession.rules
    )
}

private fun parseIsoToEpochMillis(iso: String): Long? = runCatching {
    java.time.LocalDateTime.parse(iso, java.time.format.DateTimeFormatter.ISO_LOCAL_DATE_TIME)
        .atZone(java.time.ZoneId.systemDefault())
        .toInstant()
        .toEpochMilli()
}.getOrNull()
