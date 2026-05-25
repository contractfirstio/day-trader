package daytrader.domain

fun newStrategySessionId(): String = "session-${kotlin.random.Random.nextLong().toULong().toString(16)}"

fun StrategyDeployment.inProgressSession(): StrategySession? =
    sessionHistory.find { it.status == SessionStatus.IN_PROGRESS }

fun StrategyDeployment.withoutSessionHistoryEntry(sessionId: String): StrategyDeployment =
    copy(sessionHistory = sessionHistory.filterNot { it.id == sessionId })

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
            session.copy(status = SessionStatus.CLOSED, stoppedAt = startedAt)
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
                brokerUnrealizedPnLAtStop = stopParams.brokerUnrealizedPnLAtStop,
                stopErrorMessage = stopParams.stopErrorMessage
            )
        else -> null
    }
    return copy(
        sessionHistory = sessionHistory.map { session ->
            if (session.status == SessionStatus.IN_PROGRESS) {
                session.copy(
                    status = SessionStatus.CLOSED,
                    stoppedAt = stoppedAt,
                    hadLiquidityCandle = snapshot?.hadLiquidityCandle,
                    ordersPlacedForCandle = snapshot?.ordersPlacedForCandle,
                    positionOpened = snapshot?.positionOpened,
                    pnl = snapshot?.sessionPnL ?: session.pnl,
                    trades = when {
                        trades.isNotEmpty() -> trades.size
                        else -> snapshot?.trades ?: session.trades
                    },
                    sessionTrades = trades,
                    touchTurnMilestones = when (strategyType) {
                        StrategyType.TOUCH_AND_TURN_SCALPER -> touchTurn?.milestones
                        StrategyType.QUICK_FLIP_SCALPER -> null
                    },
                    touchTurnRunRecord = touchTurnRecord
                )
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
    val closedDays: Int
)

fun List<StrategySession>.rollups(asOfSessionDate: String): SessionRollups {
    val asOf = asOfSessionDate.toSessionDayOrdinal()
    val relevant = filter { session ->
        session.status == SessionStatus.CLOSED ||
            (session.status == SessionStatus.IN_PROGRESS && session.date <= asOfSessionDate)
    }
    val closed = relevant.filter { it.status == SessionStatus.CLOSED }
    val within30 = relevant.filter { asOf - it.date.toSessionDayOrdinal() < 30 }
    val within14 = relevant.filter { asOf - it.date.toSessionDayOrdinal() < 14 }
    val within7 = relevant.filter { asOf - it.date.toSessionDayOrdinal() < 7 }
    return SessionRollups(
        totalPnl = closed.sumOf { it.pnl },
        pnl7d = within7.sumOf { it.pnl },
        pnl14d = within14.sumOf { it.pnl },
        pnl30d = within30.sumOf { it.pnl },
        winDays = closed.count { it.pnl > 0 },
        closedDays = closed.size
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
