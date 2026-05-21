package daytrader.presentation.strategies

import daytrader.domain.RunStatus
import daytrader.domain.StrategyInstance
import daytrader.domain.StrategyRun
import daytrader.domain.rollups
import daytrader.presentation.Formatters
import daytrader.presentation.positions.SortDirection

object PerformanceUiMapper {
    fun build(
        instance: StrategyInstance,
        sessionDate: String,
        sortColumn: RunSortColumn,
        sortDirection: SortDirection
    ): PerformanceUiState {
        val closedRuns = instance.runs.filter { it.status == RunStatus.CLOSED }
        val sortedRows = sortRuns(closedRuns, sortColumn, sortDirection).map(::toRowUi)
        val rollup = closedRuns.rollups(sessionDate)

        return PerformanceUiState(
            rollup7d = Formatters.currency(rollup.pnl7d, showSign = true),
            rollup30d = Formatters.currency(rollup.pnl30d, showSign = true),
            winRate = Formatters.winRate(rollup.winDays, rollup.closedDays),
            rows = sortedRows,
            sortColumn = sortColumn,
            sortDirection = sortDirection
        )
    }

    private fun toRowUi(run: StrategyRun): StrategyRunRowUi = StrategyRunRowUi(
        id = run.id,
        formattedDate = Formatters.sessionDateLabel(run.sessionDate),
        formattedPnL = Formatters.currency(run.pnl, showSign = true),
        isPositivePnL = run.pnl >= 0,
        trades = run.trades,
        formattedAtRisk = Formatters.maxAtRisk(run.maxDollarsAtRun)
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
            RunSortColumn.AT_RISK -> compareBy { it.maxDollarsAtRun }
        }
        return if (sortDirection == SortDirection.DESCENDING) {
            runs.sortedWith(comparator.reversed())
        } else {
            runs.sortedWith(comparator)
        }
    }
}
