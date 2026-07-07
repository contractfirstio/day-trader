package daytrader.domain


/**
 * Captured when an instance run stops — persisted on the active [StrategySession].
 */
data class SessionStopSnapshot(
    /** Touch Turn: first 15m bar range exceeded 25% of ADR after close. Null for other strategies. */
    val hadLiquidityCandle: Boolean? = null,
    /** Touch Turn: bracket orders logged/placed after a liquidity bar closed. */
    val ordersPlacedForCandle: Boolean? = null,
    val positionOpened: Boolean = false,
    val sessionPnL: Double = 0.0,
    val trades: Int = 0,
    val sessionTrades: List<SessionTrade> = emptyList()
)

fun StrategyDeployment.resolveStopSnapshot(
    hadOpenBrokerPosition: Boolean,
    brokerUnrealizedPnL: Double?,
    sessionTrades: List<SessionTrade> = emptyList()
): SessionStopSnapshot = when (strategyType) {
    StrategyType.TOUCH_AND_TURN_SCALPER ->
        touchTurnStopSnapshot(hadOpenBrokerPosition, brokerUnrealizedPnL, sessionTrades)
    StrategyType.QUICK_FLIP_SCALPER ->
        quickFlipStopSnapshot(hadOpenBrokerPosition, brokerUnrealizedPnL, sessionTrades)
}

private fun StrategyDeployment.touchTurnStopSnapshot(
    hadOpenBrokerPosition: Boolean,
    brokerUnrealizedPnL: Double?,
    sessionTrades: List<SessionTrade>
): SessionStopSnapshot {
    val session = touchTurnSession
    val hadLiquidity = session?.setup?.isLiquidityCandle == true
    val ordersPlaced = session?.ordersPlacedForSession == true
    val positionOpened = hadOpenBrokerPosition || sessionTrades.isNotEmpty()
    val deduped = sessionTrades.dedupeByExecId()
    val displayPnl = deduped.sessionDisplayPnL()
    val pnl = when {
        displayPnl != 0.0 -> displayPnl
        positionOpened && brokerUnrealizedPnL != null -> brokerUnrealizedPnL
        sessionTrades.isNotEmpty() && deduped.hasCompleteCommissionData() -> displayPnl
        else -> 0.0
    }
    val tradeCount = deduped.roundTripCount().takeIf { it > 0 }
        ?: if (positionOpened) 1 else 0
    return SessionStopSnapshot(
        hadLiquidityCandle = hadLiquidity,
        ordersPlacedForCandle = ordersPlaced,
        positionOpened = positionOpened,
        sessionPnL = pnl,
        trades = tradeCount,
        sessionTrades = sessionTrades
    )
}

private fun StrategyDeployment.quickFlipStopSnapshot(
    hadOpenBrokerPosition: Boolean,
    brokerUnrealizedPnL: Double?,
    sessionTrades: List<SessionTrade>
): SessionStopSnapshot {
    val demoPosition = live.state == ExecutionState.FILLED
    val positionOpened = hadOpenBrokerPosition || demoPosition || sessionTrades.isNotEmpty()
    val deduped = sessionTrades.dedupeByExecId()
    val displayPnl = deduped.sessionDisplayPnL()
    val pnl = when {
        sessionTrades.isNotEmpty() &&
            (displayPnl != 0.0 || deduped.hasCompleteCommissionData()) -> displayPnl
        hadOpenBrokerPosition && brokerUnrealizedPnL != null -> brokerUnrealizedPnL
        demoPosition -> liveUnrealizedPnL()
        else -> 0.0
    }
    val tradeCount = deduped.roundTripCount().takeIf { it > 0 }
        ?: if (positionOpened) maxOf(inProgressSession()?.trades ?: 0, 1) else 0
    return SessionStopSnapshot(
        hadLiquidityCandle = null,
        ordersPlacedForCandle = null,
        positionOpened = positionOpened,
        sessionPnL = pnl,
        trades = tradeCount,
        sessionTrades = sessionTrades
    )
}

private fun StrategyDeployment.liveUnrealizedPnL(): Double {
    val entry = live.entryPrice ?: return 0.0
    val market = live.marketPrice ?: return 0.0
    val qty = live.quantity
    return when (live.side) {
        TradeSide.LONG -> (market - entry) * qty
        TradeSide.SHORT -> (entry - market) * qty
    }
}
