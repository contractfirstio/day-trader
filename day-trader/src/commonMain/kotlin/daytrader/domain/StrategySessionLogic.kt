package daytrader.domain

import daytrader.diagnostics.SessionTrace

fun newStrategySessionId(): String = "session-${kotlin.random.Random.nextLong().toULong().toString(16)}"

fun StrategyDeployment.inProgressSession(): StrategySession? =
    sessionHistory.find { it.status == SessionStatus.IN_PROGRESS }

fun StrategyDeployment.withoutSessionHistoryEntry(sessionId: String): StrategyDeployment =
    copy(sessionHistory = sessionHistory.filterNot { it.id == sessionId })

fun StrategyDeployment.withoutClosedSessionHistory(): StrategyDeployment =
    copy(sessionHistory = sessionHistory.filter { it.status == SessionStatus.IN_PROGRESS })

fun StrategyDeployment.updateInProgressSession(
    transform: (StrategySession) -> StrategySession
): StrategyDeployment {
    val active = inProgressSession() ?: return this
    return copy(
        sessionHistory = sessionHistory.map { session ->
            if (session.id == active.id) transform(session) else session
        }
    )
}

/**
 * Starts a new performance row for this run cycle. Multiple start/stop cycles on the same
 * calendar [sessionDate] each get their own line when stopped.
 */
fun StrategyDeployment.onSessionStarted(
    sessionDate: String,
    startedAt: String = currentSessionTimestampIso(),
    touchTurnStartedBy: TouchTurnSessionStartedBy? = null
): StrategyDeployment {
    val withoutStaleInProgress = sessionHistory.map { session ->
        if (session.status == SessionStatus.IN_PROGRESS) {
            session.withConfigurationFingerprint(this)
                .copy(status = SessionStatus.CLOSED, stoppedAt = startedAt)
        } else {
            session
        }
    }
    val newSession = StrategySession(
        id = newStrategySessionId(),
        date = sessionDate,
        startedAt = startedAt,
        pnl = 0.0,
        trades = 0,
        maxAtRisk = maxDollars,
        status = SessionStatus.IN_PROGRESS,
        touchTurnStartedBy = when (strategyType) {
            StrategyType.TOUCH_AND_TURN_SCALPER -> touchTurnStartedBy ?: TouchTurnSessionStartedBy.MANUAL
            StrategyType.QUICK_FLIP_SCALPER -> null
        }
    )
    return copy(
        sessionHistory = withoutStaleInProgress + newSession,
        status = DeploymentStatus.RUNNING
    )
}

/** Closes every in-progress session-history row for this trading session. */
fun StrategyDeployment.onSessionStopped(
    stoppedAt: String = currentSessionTimestampIso(),
    snapshot: SessionStopSnapshot? = null,
    sessionTrades: List<SessionTrade> = emptyList(),
    stopParams: SessionStopParams? = null
): StrategyDeployment {
    val touchTurn = touchTurnSession
    val activeSession = inProgressSession()
    val trades = sessionTrades.ifEmpty { snapshot?.sessionTrades ?: emptyList() }
    val resolvedStopTrigger = when {
        strategyType == StrategyType.TOUCH_AND_TURN_SCALPER && touchTurn != null ->
            inferTouchTurnStopTrigger(
                instance = this,
                sessionTrades = trades,
                hasOpenPosition = stopParams?.hasOpenPosition == true,
                hasOpenOrders = stopParams?.hasOpenOrders == true,
                explicit = stopParams?.stopTrigger
            )
        else -> stopParams?.stopTrigger
    }
    val touchTurnRecord = when {
        strategyType == StrategyType.TOUCH_AND_TURN_SCALPER &&
            touchTurn != null &&
            activeSession != null &&
            stopParams?.brokerId != null &&
            resolvedStopTrigger != null ->
            buildTouchTurnRunRecord(
                session = activeSession,
                touchTurnSession = touchTurn,
                stopTrigger = resolvedStopTrigger,
                brokerId = stopParams.brokerId,
                brokerKind = stopParams.brokerKind,
                brokerUnrealizedPnLAtStop = stopParams.brokerUnrealizedPnLAtStop,
                stopErrorMessage = stopParams.stopErrorMessage,
                sessionTrades = trades,
                invertTradeSide = effectiveTouchTurnRules().invertTradeSide
            )
        else -> null
    }
    val closedSession = activeSession?.copy(
        status = SessionStatus.CLOSED,
        stoppedAt = stoppedAt,
        hadLiquidityCandle = snapshot?.hadLiquidityCandle,
        ordersPlacedForCandle = snapshot?.ordersPlacedForCandle,
        positionOpened = snapshot?.positionOpened,
        pnl = snapshot?.sessionPnL ?: activeSession.pnl,
        trades = when {
            trades.isNotEmpty() -> trades.dedupeByExecId().roundTripCount()
            else -> snapshot?.trades ?: activeSession.trades
        },
        sessionTrades = trades,
        touchTurnMilestones = when (strategyType) {
            StrategyType.TOUCH_AND_TURN_SCALPER -> touchTurn?.milestones
            StrategyType.QUICK_FLIP_SCALPER -> null
        },
        touchTurnRunRecord = touchTurnRecord
    )?.withConfigurationFingerprint(this)
    if (closedSession != null) {
        SessionTrace.sessionClosed(
            deployment = this,
            session = closedSession,
            rawTrades = trades,
            runRecord = touchTurnRecord,
            stopTrigger = resolvedStopTrigger?.name,
            brokerUnrealizedPnL = stopParams?.brokerUnrealizedPnLAtStop,
            hadOpenPosition = stopParams?.hasOpenPosition == true,
            hadOpenOrders = stopParams?.hasOpenOrders == true
        )
    }
    return copy(
        sessionHistory = sessionHistory.map { session ->
            if (session.status == SessionStatus.IN_PROGRESS && closedSession != null) {
                closedSession
            } else {
                session
            }
        },
        live = ActiveExecution.flat(),
        touchTurnSession = null,
        status = DeploymentStatus.STOPPED
    )
}

fun currentSessionTimestampIso(): String =
    java.time.LocalDateTime.now().format(java.time.format.DateTimeFormatter.ISO_LOCAL_DATE_TIME)

data class SessionRollups(
    val totalPnl: Double,
    val pnl7d: Double,
    val pnl14d: Double,
    val pnl30d: Double,
    val winDays: Int,
    val lossDays: Int,
    val noTradeDays: Int,
    val closedDays: Int,
) {
    val tradedDays: Int get() = winDays + lossDays
}

/** Closed-run P&L for display and rollups — IB net realized from fills when present. */
fun StrategySession.effectivePnL(): Double {
    val trades = sessionTrades.dedupeByExecId()
    if (trades.isEmpty()) return pnl
    val fromFills = trades.sessionDisplayPnL()
    if (trades.hasCompleteCommissionData()) return fromFills
    // Persisted fills may have lost commission while session.pnl was saved at stop.
    if (kotlin.math.abs(pnl - fromFills) > 0.005) return pnl
    return fromFills
}

/** True when this closed run opened a broker position (entry filled), not merely rules passed. */
fun StrategySession.hadPosition(): Boolean {
    when (positionOpened) {
        true -> return true
        false -> return false
        null -> Unit
    }
    if (touchTurnMilestones?.positionOpenedAt != null) return true
    touchTurnRunRecord?.milestones?.positionOpenedAt?.takeIf { it.isNotBlank() }?.let { return true }
    if (sessionTrades.any { it.parentOrderId == 0 }) return true
    touchTurnRunRecord?.decision?.outcome?.let { outcome ->
        if (outcome != TouchTurnSessionOutcome.TRADE_BRACKET_SUBMITTED) return false
    }
    return trades > 0 && kotlin.math.abs(effectivePnL()) >= 0.01
}

/** Most recently stopped closed run, by [StrategySession.stoppedAt] then [StrategySession.startedAt]. */
fun List<StrategySession>.lastClosed(): StrategySession? =
    filter { it.status == SessionStatus.CLOSED }
        .maxByOrNull { it.stoppedAt.ifBlank { it.startedAt } }

fun List<StrategySession>.rollups(asOfSessionDate: String): SessionRollups {
    val asOf = asOfSessionDate.toSessionDayOrdinal()
    var totalPnl = 0.0
    var pnl7d = 0.0
    var pnl14d = 0.0
    var pnl30d = 0.0
    var winDays = 0
    var lossDays = 0
    var noTradeDays = 0
    var closedDays = 0
    for (session in this) {
        val isClosed = session.status == SessionStatus.CLOSED
        val inProgressRelevant =
            session.status == SessionStatus.IN_PROGRESS && session.date <= asOfSessionDate
        if (!isClosed && !inProgressRelevant) continue

        val dayDelta = asOf - session.date.toSessionDayOrdinal()
        if (dayDelta < 7) pnl7d += session.effectivePnL()
        if (dayDelta < 14) pnl14d += session.effectivePnL()
        if (dayDelta < 30) pnl30d += session.effectivePnL()

        if (isClosed) {
            closedDays++
            val sessionPnl = session.effectivePnL()
            totalPnl += sessionPnl
            if (session.hadPosition()) {
                if (sessionPnl > 0) winDays++ else lossDays++
            } else {
                noTradeDays++
            }
        }
    }
    return SessionRollups(
        totalPnl = totalPnl,
        pnl7d = pnl7d,
        pnl14d = pnl14d,
        pnl30d = pnl30d,
        winDays = winDays,
        lossDays = lossDays,
        noTradeDays = noTradeDays,
        closedDays = closedDays,
    )
}

private fun String.toSessionDayOrdinal(): Int {
    val parts = split("-")
    if (parts.size != 3) return 0
    val year = parts[0].toIntOrNull() ?: return 0
    val month = parts[1].toIntOrNull() ?: return 0
    val day = parts[2].toIntOrNull() ?: return 0
    return year * 372 + month * 31 + day
}
