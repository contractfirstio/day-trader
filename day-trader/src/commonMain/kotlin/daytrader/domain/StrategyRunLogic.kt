package daytrader.domain

fun newStrategyRunId(): String = "run-${kotlin.random.Random.nextLong().toULong().toString(16)}"

fun StrategyInstance.inProgressRun(): StrategyRun? =
    performance.find { it.status == RunStatus.IN_PROGRESS }

fun StrategyInstance.withoutPerformanceRun(runId: String): StrategyInstance =
    copy(performance = performance.filterNot { it.id == runId })

fun StrategyInstance.updateInProgressRun(
    transform: (StrategyRun) -> StrategyRun
): StrategyInstance {
    val active = inProgressRun() ?: return this
    return copy(
        performance = performance.map { run ->
            if (run.id == active.id) transform(run) else run
        }
    )
}

/**
 * Starts a new performance row for this run cycle. Multiple start/stop cycles on the same
 * calendar [sessionDate] each get their own line when stopped.
 */
fun StrategyInstance.onRunStarted(
    sessionDate: String,
    startedAt: String = currentRunTimestampIso()
): StrategyInstance {
    val withoutStaleInProgress = performance.map { run ->
        if (run.status == RunStatus.IN_PROGRESS) {
            run.copy(status = RunStatus.CLOSED, stoppedAt = startedAt)
        } else {
            run
        }
    }
    val newRun = StrategyRun(
        id = newStrategyRunId(),
        date = sessionDate,
        startedAt = startedAt,
        pnl = 0.0,
        trades = 0,
        maxAtRisk = maxDollars,
        status = RunStatus.IN_PROGRESS
    )
    return copy(
        performance = withoutStaleInProgress + newRun,
        status = InstanceStatus.RUNNING
    )
}

/** Closes every in-progress performance row for this run cycle. */
fun StrategyInstance.onRunStopped(
    stoppedAt: String = currentRunTimestampIso(),
    snapshot: RunStopSnapshot? = null
): StrategyInstance = copy(
    performance = performance.map { run ->
        if (run.status == RunStatus.IN_PROGRESS) {
            run.copy(
                status = RunStatus.CLOSED,
                stoppedAt = stoppedAt,
                hadLiquidityCandle = snapshot?.hadLiquidityCandle,
                ordersPlacedForCandle = snapshot?.ordersPlacedForCandle,
                positionOpened = snapshot?.positionOpened,
                pnl = snapshot?.sessionPnL ?: run.pnl,
                trades = snapshot?.trades ?: run.trades
            )
        } else {
            run
        }
    },
    live = ActiveExecution.flat(),
    touchTurnSession = null,
    status = InstanceStatus.STOPPED
)

fun currentRunTimestampIso(): String =
    java.time.LocalDateTime.now().format(java.time.format.DateTimeFormatter.ISO_LOCAL_DATE_TIME)

data class RunRollups(
    val totalPnl: Double,
    val pnl7d: Double,
    val pnl30d: Double,
    val winDays: Int,
    val closedDays: Int
)

fun List<StrategyRun>.rollups(asOfSessionDate: String): RunRollups {
    val asOf = asOfSessionDate.toSessionDayOrdinal()
    val relevant = filter { run ->
        run.status == RunStatus.CLOSED ||
            (run.status == RunStatus.IN_PROGRESS && run.date <= asOfSessionDate)
    }
    val closed = relevant.filter { it.status == RunStatus.CLOSED }
    val within30 = relevant.filter { asOf - it.date.toSessionDayOrdinal() < 30 }
    val within7 = relevant.filter { asOf - it.date.toSessionDayOrdinal() < 7 }
    return RunRollups(
        totalPnl = closed.sumOf { it.pnl },
        pnl7d = within7.sumOf { it.pnl },
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
