package daytrader.domain

fun newStrategyRunId(): String = "run-${kotlin.random.Random.nextLong().toULong().toString(16)}"

fun StrategyInstance.syncInProgressRun(sessionDate: String): StrategyInstance = copy(
    runs = runs.map { run ->
        if (run.status == RunStatus.IN_PROGRESS && run.sessionDate == sessionDate) {
            run.copy(pnl = todayPnL, trades = tradesToday)
        } else {
            run
        }
    }
)

fun StrategyInstance.onRunStarted(sessionDate: String): StrategyInstance {
    val staleClosed = runs.map { run ->
        if (run.status == RunStatus.IN_PROGRESS && run.sessionDate != sessionDate) {
            run.copy(status = RunStatus.CLOSED, pnl = todayPnL, trades = tradesToday)
        } else {
            run
        }
    }

    val inProgressToday = staleClosed.find {
        it.sessionDate == sessionDate && it.status == RunStatus.IN_PROGRESS
    }
    if (inProgressToday != null) {
        return copy(runs = staleClosed, status = InstanceStatus.RUNNING)
    }

    val closedToday = staleClosed.find {
        it.sessionDate == sessionDate && it.status == RunStatus.CLOSED
    }
    if (closedToday != null) {
        return copy(
            runs = staleClosed.map { run ->
                if (run.id == closedToday.id) {
                    run.copy(status = RunStatus.IN_PROGRESS)
                } else {
                    run
                }
            },
            status = InstanceStatus.RUNNING
        )
    }

    val newRun = StrategyRun(
        id = newStrategyRunId(),
        instanceId = id,
        sessionDate = sessionDate,
        pnl = 0.0,
        trades = 0,
        maxDollarsAtRun = maxDollars,
        status = RunStatus.IN_PROGRESS
    )
    return copy(
        runs = staleClosed + newRun,
        todayPnL = 0.0,
        tradesToday = 0,
        status = InstanceStatus.RUNNING
    )
}

fun StrategyInstance.onRunStopped(sessionDate: String): StrategyInstance = copy(
    runs = runs.map { run ->
        if (run.sessionDate == sessionDate && run.status == RunStatus.IN_PROGRESS) {
            run.copy(status = RunStatus.CLOSED, pnl = todayPnL, trades = tradesToday)
        } else {
            run
        }
    },
    status = InstanceStatus.STOPPED
)

fun StrategyInstance.inProgressRun(sessionDate: String): StrategyRun? =
    runs.find { it.sessionDate == sessionDate && it.status == RunStatus.IN_PROGRESS }

data class RunRollups(
    val pnl7d: Double,
    val pnl30d: Double,
    val winDays: Int,
    val closedDays: Int
)

fun List<StrategyRun>.rollups(asOfSessionDate: String): RunRollups {
    val asOf = asOfSessionDate.toSessionDayOrdinal()
    val relevant = filter { run ->
        run.status == RunStatus.CLOSED ||
            (run.status == RunStatus.IN_PROGRESS && run.sessionDate <= asOfSessionDate)
    }
    val closed = relevant.filter { it.status == RunStatus.CLOSED }
    val within30 = relevant.filter { asOf - it.sessionDate.toSessionDayOrdinal() < 30 }
    val within7 = relevant.filter { asOf - it.sessionDate.toSessionDayOrdinal() < 7 }
    return RunRollups(
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
