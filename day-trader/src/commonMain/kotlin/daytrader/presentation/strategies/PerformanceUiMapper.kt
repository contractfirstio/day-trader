package daytrader.presentation.strategies

import daytrader.domain.RunStatus
import daytrader.domain.StrategyInstance
import daytrader.domain.StrategyRun
import daytrader.domain.rollups
import daytrader.domain.inProgressRun
import daytrader.domain.syncInProgressRun
import daytrader.presentation.Formatters
import daytrader.presentation.positions.SortDirection

object PerformanceUiMapper {
    fun build(
        instance: StrategyInstance,
        sessionDate: String,
        sortColumn: RunSortColumn,
        sortDirection: SortDirection
    ): PerformanceUiState {
        val synced = instance.syncInProgressRun(sessionDate)
        val inProgress = synced.inProgressRun(sessionDate)
        val isLive = synced.status == daytrader.domain.InstanceStatus.RUNNING && inProgress != null

        val currentPnL = if (isLive) synced.todayPnL else inProgress?.pnl ?: synced.todayPnL
        val currentTrades = if (isLive) synced.tradesToday else inProgress?.trades ?: synced.tradesToday

        val sortedRows = sortRuns(synced.runs, sortColumn, sortDirection).map { run ->
            toRowUi(run, isLive && run.sessionDate == sessionDate && run.status == RunStatus.IN_PROGRESS)
        }

        val rollup = synced.runs.rollups(sessionDate)

        return PerformanceUiState(
            currentRunDateLabel = Formatters.sessionDateLabel(sessionDate),
            currentRunPnL = Formatters.currency(currentPnL, showSign = true),
            isCurrentRunPositive = currentPnL >= 0,
            currentRunTrades = currentTrades,
            isLive = isLive,
            rollup7d = Formatters.currency(rollup.pnl7d, showSign = true),
            rollup30d = Formatters.currency(rollup.pnl30d, showSign = true),
            winRate = Formatters.winRate(rollup.winDays, rollup.closedDays),
            rows = sortedRows,
            sortColumn = sortColumn,
            sortDirection = sortDirection
        )
    }

    private fun toRowUi(run: StrategyRun, isLive: Boolean): StrategyRunRowUi = StrategyRunRowUi(
        id = run.id,
        formattedDate = Formatters.sessionDateLabel(run.sessionDate),
        formattedPnL = Formatters.currency(run.pnl, showSign = true),
        isPositivePnL = run.pnl >= 0,
        trades = run.trades,
        formattedVsMax = Formatters.percentOfMax(run.pnl, run.maxDollarsAtRun),
        status = run.status,
        isLive = isLive
    )

    private fun sortRuns(
        runs: List<StrategyRun>,
        sortColumn: RunSortColumn,
        sortDirection: SortDirection
    ): List<StrategyRun> {
        val comparator = when (sortColumn) {
            RunSortColumn.DATE -> compareBy<StrategyRun> { it.sessionDate }
            RunSortColumn.PNL -> compareBy { it.pnl }
            RunSortColumn.TRADES -> compareBy { it.trades }
            RunSortColumn.VS_MAX -> compareBy { run ->
                if (run.maxDollarsAtRun <= 0) 0.0 else kotlin.math.abs(run.pnl) / run.maxDollarsAtRun
            }
        }
        return if (sortDirection == SortDirection.DESCENDING) {
            runs.sortedWith(comparator.reversed())
        } else {
            runs.sortedWith(comparator)
        }
    }
}
