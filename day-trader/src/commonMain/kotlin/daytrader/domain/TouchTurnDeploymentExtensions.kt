package daytrader.domain

fun StrategyDeployment.beginTouchTurnSession(sessionDate: String): StrategyDeployment {
    if (!isTouchTurn) return this
    val startedAt = inProgressSession()?.startedAt ?: currentSessionTimestampIso()
    val prepareSnapshot = touchTurnPrepare?.let(TouchTurnPrepareSnapshot::from)
    return copy(
        touchTurnSession = TouchTurnSessionContext(
            sessionDate = sessionDate,
            status = TouchTurnCandleStatus.LOADING,
            rules = effectiveTouchTurnRules(),
            milestones = TouchTurnMilestoneTimestamps(startingSessionAt = startedAt),
            prepareSnapshot = prepareSnapshot
        )
    )
}

fun StrategyDeployment.withTouchTurnCandle(
    sessionDate: String,
    candle: OhlcBar,
    adr14: Double,
    rangeThreshold: Double = TouchTurnLogic.liquidityRangeThreshold(adr14)
): StrategyDeployment {
    if (!isTouchTurn) return this
    val setup = TouchTurnLogic.computeBracketSetup(candle, rangeThreshold)
    return copy(
        touchTurnSession = TouchTurnSessionContext(
            sessionDate = sessionDate,
            status = TouchTurnCandleStatus.READY,
            candle = candle,
            setup = setup,
            adr14 = adr14,
            rangeThreshold = rangeThreshold
        )
    )
}

/**
 * Stores ADR/volume context after the initial history fetch. Opening-bar OHLC is **not** stored here;
 * [withClosedFirstFifteenMinuteCandle] applies the completed bar once wall-clock passes bar end.
 */
fun StrategyDeployment.withFirstFifteenMinuteCandle(
    sessionDate: String,
    candle: OhlcBar,
    atr14: Double,
    volumeSma20: Double,
    adr14: Double? = null,
    dailyAtr14: Double? = null,
    currencyCode: String = "USD",
    marketZoneId: String = "America/New_York",
    bootstrapReusedFromPrepare: Boolean? = null
): StrategyDeployment {
    if (!isTouchTurn) return this
    val rules = effectiveTouchTurnRules()
    val thresholds = TouchTurnLogic.resolveLiquidityThresholds(atr14, dailyAtr14, rules)
    val priorSession = touchTurnSession
    val prior = priorSession?.milestones
    val prepareSnapshot = when {
        priorSession?.prepareSnapshot != null && bootstrapReusedFromPrepare != null ->
            priorSession.prepareSnapshot.withBootstrapReused(bootstrapReusedFromPrepare)
        bootstrapReusedFromPrepare != null ->
            TouchTurnPrepareSnapshot(
                overallStatus = TouchTurnPrepareOverallStatus.PASS.name,
                checks = emptyList(),
                bootstrapReusedFromPrepare = bootstrapReusedFromPrepare,
                atr14 = atr14,
                volumeSma20 = volumeSma20
            )
        else -> priorSession?.prepareSnapshot
    }
    val at = currentSessionTimestampIso()
    return copy(
        touchTurnSession = TouchTurnSessionContext(
            sessionDate = sessionDate,
            status = TouchTurnCandleStatus.READY,
            openingBarTime = candle.time,
            candle = null,
            currencyCode = currencyCode,
            marketZoneId = marketZoneId,
            adr14 = adr14 ?: atr14,
            atr14 = atr14,
            dailyAtr14 = dailyAtr14,
            volumeSma20 = volumeSma20,
            rangeThreshold = thresholds.threshold15mAtr ?: thresholds.primary,
            rangeThresholdDailyAtr = thresholds.thresholdDailyAtr,
            rules = rules,
            milestones = TouchTurnMilestoneTimestamps(
                startingSessionAt = prior?.startingSessionAt ?: inProgressSession()?.startedAt ?: at,
                dataReadyAt = at
            ),
            prepareSnapshot = prepareSnapshot
        )
    )
}

/** Engine event: first 15m RTH bar has closed (OHLC refetch / liquidity eval may still be in progress). */
fun StrategyDeployment.withOpeningBarClosedMilestone(): StrategyDeployment {
    if (!isTouchTurn) return this
    val session = touchTurnSession ?: return this
    if (session.milestones.barClosedAt != null) return this
    val at = currentSessionTimestampIso()
    return copy(
        touchTurnSession = session.copy(
            milestones = session.milestones.copy(barClosedAt = at)
        )
    )
}

/** Applies the completed first 15m bar OHLC from a post-close historical refetch. */
fun StrategyDeployment.withClosedFirstFifteenMinuteCandle(candle: OhlcBar): StrategyDeployment {
    if (!isTouchTurn) return this
    val session = touchTurnSession ?: return this
    return copy(
        touchTurnSession = session.copy(
            candle = candle,
            openingBarTime = session.openingBarTime ?: candle.time
        )
    )
}

/** Persists bracket setup and liquidity flag once the first candle has closed. */
fun StrategyDeployment.withLiquidityEvaluatedIfClosed(
    enforceCloseConfirmation: Boolean = true,
    nowEpochMillis: Long = System.currentTimeMillis(),
    liveBid: Double? = null,
    liveAsk: Double? = null,
    liveLast: Double? = null,
    requireLivePriceChecks: Boolean = false,
    macroTrend: MacroTrendState? = null,
    stockTrend: StockTrendState? = null,
    macroBenchmarkSymbol: String? = null,
    macroBenchmarkLabel: String? = null
): StrategyDeployment {
    if (!isTouchTurn) return this
    val session = touchTurnSession ?: return this
    val candle = session.candle ?: return this
    if (session.candleCloseStatus(nowEpochMillis) != FirstCandleCloseStatus.CLOSED) return this
    if (session.setup != null) return this
    val rules = effectiveTouchTurnRules()
    val setup = TouchTurnLogic.computeBracketSetup(candle, session.liquidityThresholds, rules)
    val gate = TouchTurnLogic.evaluateEntryGate(
        setup = setup,
        candle = candle,
        volumeSma20 = session.volumeSma20 ?: 0.0,
        marketZoneId = session.marketZoneId,
        nowEpochMillis = nowEpochMillis,
        sessionDateIso = session.sessionDate,
        enforceCloseConfirmation = enforceCloseConfirmation,
        liveBid = liveBid,
        liveAsk = liveAsk,
        liveLast = liveLast,
        requireLivePriceChecks = requireLivePriceChecks,
        macroTrend = macroTrend,
        stockTrend = stockTrend,
        rules = rules
    )
    val closeConfirmation = gate.closeConfirmation
    val closeGatePassed = gate.closeGatePassed
    val entryOrdersPermitted = gate.entryOrdersPermitted
    val decisionOutcome = gate.decisionOutcome
    val at = currentSessionTimestampIso()
    val milestones = session.milestones.let { m ->
        m.copy(
            dataReadyAt = m.dataReadyAt ?: m.startingSessionAt,
            barClosedAt = m.barClosedAt ?: at,
            liquidityEvaluatedAt = at,
            closeConfirmedAt = m.closeConfirmedAt
                ?: if (closeGatePassed) at else null
        )
    }
    val updatedSession = session.copy(
        setup = setup,
        entryOrdersPermitted = entryOrdersPermitted,
        decisionOutcome = decisionOutcome ?: session.decisionOutcome,
        macroTrendAtEntry = macroTrend ?: session.macroTrendAtEntry,
        stockTrendAtEntry = stockTrend ?: session.stockTrendAtEntry,
        macroBenchmarkSymbol = macroBenchmarkSymbol ?: session.macroBenchmarkSymbol,
        macroBenchmarkLabel = macroBenchmarkLabel ?: session.macroBenchmarkLabel,
        milestones = milestones
    )
    TouchTurnDecisionLog.liquidityEvaluated(
        instanceId = id,
        symbol = symbol,
        session = updatedSession,
        setup = setup,
        enforceCloseConfirmation = enforceCloseConfirmation,
        closeConfirmation = closeConfirmation,
        entryOrdersPermitted = entryOrdersPermitted,
        decisionOutcome = updatedSession.decisionOutcome,
        nowEpochMillis = nowEpochMillis
    )
    return copy(touchTurnSession = updatedSession)
}

fun StrategyDeployment.withTouchTurnDecisionOutcome(outcome: TouchTurnSessionOutcome): StrategyDeployment {
    if (!isTouchTurn) return this
    val session = touchTurnSession ?: return this
    if (session.decisionOutcome != null) return this
    return copy(touchTurnSession = session.copy(decisionOutcome = outcome))
}

fun StrategyDeployment.withOrdersPlacedForSession(plan: TouchTurnOrderPlan? = null): StrategyDeployment {
    if (!isTouchTurn) return this
    val session = touchTurnSession ?: return this
    val at = currentSessionTimestampIso()
    val milestones = session.milestones.copy(
        ordersPlacedAt = session.milestones.ordersPlacedAt ?: at
    )
    val bracket = plan?.toPlannedBracket()
    return copy(
        touchTurnSession = session.copy(
            ordersPlacedForSession = true,
            decisionOutcome = TouchTurnSessionOutcome.TRADE_BRACKET_SUBMITTED,
            plannedQuantity = plan?.quantity ?: session.plannedQuantity,
            plannedBracket = bracket ?: session.plannedBracket,
            milestones = milestones
        )
    )
}

/** Records when the broker first reports an open position for this run. */
fun StrategyDeployment.withTouchTurnPositionOpenedIfNeeded(): StrategyDeployment {
    if (!isTouchTurn) return this
    val session = touchTurnSession ?: return this
    if (session.milestones.positionOpenedAt != null) return this
    val at = currentSessionTimestampIso()
    return copy(
        touchTurnSession = session.copy(
            milestones = session.milestones.copy(positionOpenedAt = at)
        )
    )
}

/** Records when the run enters the closing / auto-stop phase. */
fun StrategyDeployment.withTouchTurnClosingMilestoneIfNeeded(): StrategyDeployment {
    if (!isTouchTurn) return this
    val session = touchTurnSession ?: return this
    if (session.milestones.closingSessionAt != null) return this
    val at = currentSessionTimestampIso()
    return copy(
        touchTurnSession = session.copy(
            milestones = session.milestones.copy(closingSessionAt = at)
        )
    )
}

fun StrategyDeployment.withTouchTurnCandleFailed(
    sessionDate: String,
    message: String
): StrategyDeployment {
    if (!isTouchTurn) return this
    val prior = touchTurnSession
    val at = currentSessionTimestampIso()
    val milestones = (prior?.milestones ?: TouchTurnMilestoneTimestamps()).copy(
        startingSessionAt = prior?.milestones?.startingSessionAt
            ?: inProgressSession()?.startedAt
            ?: at,
        dataReadyAt = prior?.milestones?.dataReadyAt,
        barClosedAt = prior?.milestones?.barClosedAt,
        dataFailedAt = at
    )
    val failedSession = prior?.copy(
        sessionDate = sessionDate,
        status = TouchTurnCandleStatus.FAILED,
        errorMessage = message,
        decisionOutcome = TouchTurnSessionOutcome.NO_TRADE_DATA_FAILED,
        milestones = milestones
    ) ?: TouchTurnSessionContext(
        sessionDate = sessionDate,
        status = TouchTurnCandleStatus.FAILED,
        errorMessage = message,
        decisionOutcome = TouchTurnSessionOutcome.NO_TRADE_DATA_FAILED,
        milestones = milestones
    )
    return copy(touchTurnSession = failedSession)
}

/**
 * Most recent closed Touch Turn run for pipeline UI, recap, and close panel.
 * Picks the latest closed run by [StrategySession.stoppedAt] (then [StrategySession.startedAt]),
 * regardless of whether it had broker fills.
 */
fun StrategyDeployment.touchTurnPostStopSession(): StrategySession? {
    if (!isTouchTurn) return null
    inProgressSession()?.takeIf { it.sessionTrades.isNotEmpty() }?.let { return it }
    return sessionHistory
        .filter {
            it.status == SessionStatus.CLOSED &&
                (it.touchTurnMilestones != null || it.touchTurnRunRecord != null)
        }
        .maxByOrNull { it.stoppedAt.ifBlank { it.startedAt } }
}

/** Most recent closed Touch Turn run used for pipeline / recap / close. */
fun StrategyDeployment.lastClosedTouchTurnSession(): StrategySession? = touchTurnPostStopSession()

fun StrategyDeployment.sessionHistoryRun(runId: String): StrategySession? =
    sessionHistory.find { it.id == runId }

/**
 * Touch Turn run shown on the Trading tab recap (live in-progress with fills, else closed).
 * When [runId] is set, returns that history row if it has pipeline data; otherwise the latest closed run.
 */
fun StrategyDeployment.touchTurnRecapRun(runId: String? = null): StrategySession? {
    if (!isTouchTurn) return null
    inProgressSession()?.takeIf { it.sessionTrades.isNotEmpty() }?.let { return it }
    runId?.let { id ->
        sessionHistoryRun(id)?.takeIf { run ->
            (run.status == SessionStatus.CLOSED || run.status == SessionStatus.IN_PROGRESS) &&
                (run.touchTurnMilestones != null || run.touchTurnRunRecord != null)
        }?.let { return it }
    }
    return touchTurnPostStopSession()
}

/** Closed or in-progress run whose fills power the post-session Orders recap chart. */
fun StrategyDeployment.touchTurnRecapSessionRun(runId: String? = null): StrategySession? =
    touchTurnRecapRun(runId)

/** Broker fills for the Trading tab order recap chart. */
fun StrategyDeployment.touchTurnRecapSessionTrades(runId: String? = null): List<SessionTrade> =
    touchTurnRecapSessionRun(runId)?.sessionTrades.orEmpty()

/** Realized P&L for the recap chart — from fills on the same run as [touchTurnRecapSessionTrades]. */
fun StrategyDeployment.touchTurnRecapSessionPnl(runId: String? = null): Double? {
    val trades = touchTurnRecapSessionTrades(runId)
    if (trades.isEmpty()) return null
    val fromFills = trades.sessionRealizedPnL()
    if (fromFills != 0.0) return fromFills
    return touchTurnRecapSessionRun(runId)?.pnl
}

/**
 * Live or closed-run session context for the Trading tab pipeline detail panels.
 * After stop, [touchTurnSession] is cleared but opening bar / ADR are restored from [StrategySession.touchTurnRunRecord].
 */
fun StrategyDeployment.touchTurnAnalysisSessionForRun(run: StrategySession? = null): TouchTurnSessionContext? {
    touchTurnSession?.let { return it }
    val closed = run ?: touchTurnPostStopSession() ?: return null
    val rules = closed.touchTurnRunRecord?.rules ?: effectiveTouchTurnRules()
    return closed.toTouchTurnAnalysisContext(rules)
}

/** @see [touchTurnAnalysisSessionForRun] with the latest closed run. */
fun StrategyDeployment.touchTurnAnalysisSession(): TouchTurnSessionContext? =
    touchTurnAnalysisSessionForRun(touchTurnPostStopSession())

fun StrategySession.toTouchTurnAnalysisContext(
    rules: TouchTurnRuleConfig = TouchTurnRuleConfig.DEFAULT
): TouchTurnSessionContext? {
    val record = touchTurnRunRecord
    val milestones = touchTurnMilestones ?: record?.milestones ?: return null
    val inputs = record?.marketInputs
    val candle = inputs?.openingBar
    val adr = inputs?.adr14
    val atr = inputs?.atr14
    val dailyAtr = inputs?.dailyAtr14
    val thresholds = TouchTurnLogic.resolveLiquidityThresholds(atr, dailyAtr, rules).let { resolved ->
        if (resolved.primary > 0.0) resolved else {
            val legacy = atr?.let { TouchTurnLogic.liquidityRangeThresholdFromAtr(it, rules) }
                ?: adr?.let { TouchTurnLogic.liquidityRangeThreshold(it) }
            TouchTurnLiquidityThresholds(threshold15mAtr = legacy)
        }
    }
    val setup = candle?.let { TouchTurnLogic.computeBracketSetup(it, thresholds, rules) }
    val plannedBracket = record?.decision?.plannedBracket
    val outcome = record?.decision?.outcome
    val executedBracketLegs = record?.decision?.executedLegs?.takeIf { it.isNotEmpty() }
        ?: TouchTurnBracketExecution.resolveFromTrades(
            trades = sessionTrades,
            plannedBracket = plannedBracket,
            bracketSetup = setup,
            sessionPnl = pnl.takeIf { sessionTrades.isNotEmpty() }
        )
    return TouchTurnSessionContext(
        sessionDate = date,
        status = when {
            inputs?.dataErrorMessage != null -> TouchTurnCandleStatus.FAILED
            candle == null -> TouchTurnCandleStatus.LOADING
            else -> TouchTurnCandleStatus.READY
        },
        candle = candle,
        setup = setup,
        errorMessage = inputs?.dataErrorMessage,
        milestones = milestones,
        currencyCode = inputs?.currencyCode ?: "USD",
        marketZoneId = inputs?.marketZoneId ?: "America/New_York",
        adr14 = adr,
        atr14 = atr,
        dailyAtr14 = dailyAtr,
        volumeSma20 = inputs?.volumeSma20,
        rangeThreshold = thresholds.threshold15mAtr ?: thresholds.primary,
        rangeThresholdDailyAtr = thresholds.thresholdDailyAtr,
        rules = rules,
        entryOrdersPermitted = when (outcome) {
            TouchTurnSessionOutcome.TRADE_BRACKET_SUBMITTED -> true
            TouchTurnSessionOutcome.NO_TRADE_NOT_LIQUIDITY,
            TouchTurnSessionOutcome.NO_TRADE_DOJI,
            TouchTurnSessionOutcome.NO_TRADE_CLOSE_CONFIRMATION_FAILED,
            TouchTurnSessionOutcome.NO_TRADE_LIVE_CLOSE_CONFIRMATION_FAILED,
            TouchTurnSessionOutcome.NO_TRADE_BAR_LIVE_DIVERGENCE,
            TouchTurnSessionOutcome.NO_TRADE_ENTRY_NOT_TOUCHABLE,
            TouchTurnSessionOutcome.NO_TRADE_LIVE_QUOTE_UNAVAILABLE,
            TouchTurnSessionOutcome.NO_TRADE_ENTRY_WINDOW_EXPIRED,
            TouchTurnSessionOutcome.NO_TRADE_ORDER_REJECTED -> false
            else -> hadLiquidityCandle == true &&
                setup?.let { TouchTurnLogic.setupActionableForEntry(it, rules) } == true
        },
        ordersPlacedForSession = ordersPlacedForCandle == true ||
            outcome == TouchTurnSessionOutcome.TRADE_BRACKET_SUBMITTED,
        decisionOutcome = outcome,
        plannedQuantity = record?.decision?.plannedQuantity,
        plannedBracket = plannedBracket,
        executedBracketLegs = executedBracketLegs
    )
}
