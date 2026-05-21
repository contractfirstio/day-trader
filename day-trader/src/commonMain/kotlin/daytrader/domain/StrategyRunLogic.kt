package daytrader.domain

fun newStrategyRunId(): String = "run-${kotlin.random.Random.nextLong().toULong().toString(16)}"

fun StrategyInstance.inProgressRun(sessionDate: String): StrategyRun? =
    performance.find { it.date == sessionDate && it.status == RunStatus.IN_PROGRESS }

fun StrategyInstance.updateInProgressRun(
    sessionDate: String,
    transform: (StrategyRun) -> StrategyRun
): StrategyInstance {
    val day = inProgressRun(sessionDate) ?: return this
    return copy(
        performance = performance.map { run ->
            if (run.id == day.id) transform(run) else run
        }
    )
}

fun StrategyInstance.onRunStarted(sessionDate: String): StrategyInstance {
    val staleClosed = performance.map { run ->
        if (run.status == RunStatus.IN_PROGRESS && run.date != sessionDate) {
            run.copy(status = RunStatus.CLOSED)
        } else {
            run
        }
    }

    val inProgressToday = staleClosed.find {
        it.date == sessionDate && it.status == RunStatus.IN_PROGRESS
    }
    if (inProgressToday != null) {
        return copy(performance = staleClosed, status = InstanceStatus.RUNNING)
    }

    val closedToday = staleClosed.find {
        it.date == sessionDate && it.status == RunStatus.CLOSED
    }
    if (closedToday != null) {
        return copy(
            performance = staleClosed.map { run ->
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
        date = sessionDate,
        pnl = 0.0,
        trades = 0,
        maxAtRisk = maxDollars,
        status = RunStatus.IN_PROGRESS
    )
    return copy(
        performance = staleClosed + newRun,
        status = InstanceStatus.RUNNING
    )
}

fun StrategyInstance.onRunStopped(sessionDate: String): StrategyInstance = copy(
    performance = performance.map { run ->
        if (run.date == sessionDate && run.status == RunStatus.IN_PROGRESS) {
            run.copy(status = RunStatus.CLOSED)
        } else {
            run
        }
    },
    live = ActiveExecution.flat(),
    status = InstanceStatus.STOPPED
)

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
