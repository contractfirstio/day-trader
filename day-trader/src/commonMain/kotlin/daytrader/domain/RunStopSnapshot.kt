package daytrader.domain

/**
 * Captured when an instance run stops — persisted on the active [StrategyRun].
 */
data class RunStopSnapshot(
    /** Touch Turn: first 15m bar range exceeded 25% of ADR after close. Null for other strategies. */
    val hadLiquidityCandle: Boolean? = null,
    /** Touch Turn: bracket orders logged/placed after a liquidity bar closed. */
    val ordersPlacedForCandle: Boolean? = null,
    val positionOpened: Boolean = false,
    val sessionPnL: Double = 0.0,
    val trades: Int = 0,
    val sessionTrades: List<SessionTrade> = emptyList()
)

fun StrategyInstance.resolveStopSnapshot(
    hadOpenBrokerPosition: Boolean,
    brokerUnrealizedPnL: Double?,
    sessionTrades: List<SessionTrade> = emptyList()
): RunStopSnapshot = when (strategyType) {
    StrategyType.TOUCH_AND_TURN_SCALPER ->
        touchTurnStopSnapshot(hadOpenBrokerPosition, brokerUnrealizedPnL, sessionTrades)
    StrategyType.QUICK_FLIP_SCALPER ->
        quickFlipStopSnapshot(hadOpenBrokerPosition, brokerUnrealizedPnL, sessionTrades)
}

private fun StrategyInstance.touchTurnStopSnapshot(
    hadOpenBrokerPosition: Boolean,
    brokerUnrealizedPnL: Double?,
    sessionTrades: List<SessionTrade>
): RunStopSnapshot {
    val session = touchTurnSession
    val hadLiquidity = session?.setup?.isLiquidityCandle == true
    val ordersPlaced = hadLiquidity &&
        session.setup?.isActionable == true &&
        session.sessionOrdersPlaced()
    val positionOpened = hadOpenBrokerPosition || sessionTrades.isNotEmpty()
    val realized = sessionTrades.sessionRealizedPnL()
    val pnl = when {
        sessionTrades.isNotEmpty() && realized != 0.0 -> realized
        positionOpened && brokerUnrealizedPnL != null -> brokerUnrealizedPnL
        else -> 0.0
    }
    val tradeCount = sessionTrades.size.takeIf { it > 0 }
        ?: if (positionOpened) 1 else 0
    return RunStopSnapshot(
        hadLiquidityCandle = hadLiquidity,
        ordersPlacedForCandle = ordersPlaced,
        positionOpened = positionOpened,
        sessionPnL = pnl,
        trades = tradeCount,
        sessionTrades = sessionTrades
    )
}

private fun StrategyInstance.quickFlipStopSnapshot(
    hadOpenBrokerPosition: Boolean,
    brokerUnrealizedPnL: Double?,
    sessionTrades: List<SessionTrade>
): RunStopSnapshot {
    val demoPosition = live.state == ExecutionState.FILLED
    val positionOpened = hadOpenBrokerPosition || demoPosition || sessionTrades.isNotEmpty()
    val realized = sessionTrades.sessionRealizedPnL()
    val pnl = when {
        sessionTrades.isNotEmpty() && realized != 0.0 -> realized
        hadOpenBrokerPosition && brokerUnrealizedPnL != null -> brokerUnrealizedPnL
        demoPosition -> liveUnrealizedPnL()
        else -> 0.0
    }
    val tradeCount = sessionTrades.size.takeIf { it > 0 }
        ?: if (positionOpened) maxOf(inProgressRun()?.trades ?: 0, 1) else 0
    return RunStopSnapshot(
        hadLiquidityCandle = null,
        ordersPlacedForCandle = null,
        positionOpened = positionOpened,
        sessionPnL = pnl,
        trades = tradeCount,
        sessionTrades = sessionTrades
    )
}

private fun StrategyInstance.liveUnrealizedPnL(): Double {
    val entry = live.entryPrice ?: return 0.0
    val market = live.marketPrice ?: return 0.0
    val qty = live.quantity
    return when (live.side) {
        TradeSide.LONG -> (market - entry) * qty
        TradeSide.SHORT -> (entry - market) * qty
    }
}
