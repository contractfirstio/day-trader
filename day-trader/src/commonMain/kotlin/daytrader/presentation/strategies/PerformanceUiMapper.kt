package daytrader.presentation.strategies

import daytrader.domain.RunStatus
import daytrader.domain.StrategyInstance
import daytrader.domain.StrategyRun
import daytrader.domain.StrategyType
import daytrader.domain.rollups
import daytrader.presentation.Formatters
import daytrader.presentation.positions.SortDirection

object PerformanceUiMapper {
    fun build(
        instance: StrategyInstance,
        sessionDate: String,
        sortColumn: RunSortColumn,
        sortDirection: SortDirection,
        selectedRunId: String? = null
    ): PerformanceUiState {
        val closedRuns = instance.performance.filter { it.status == RunStatus.CLOSED }
        val displayRuns = instance.performance.filter {
            it.status == RunStatus.CLOSED || it.status == RunStatus.IN_PROGRESS
        }
        val includeTouchTurnFields = instance.strategyType == StrategyType.TOUCH_AND_TURN_SCALPER
        val sortedRows = sortRuns(displayRuns, sortColumn, sortDirection)
            .map { toRowUi(it, includeTouchTurnFields, selectedRunId) }
        val rollup = closedRuns.rollups(sessionDate)
        val selectedRun = displayRuns.find { it.id == selectedRunId }
        val selectedDetail = selectedRun
            ?.takeIf { it.sessionTrades.isNotEmpty() }
            ?.let { run ->
                val inProgress = run.status == RunStatus.IN_PROGRESS
                RunTradeDetailUiMapper.fromSessionTrades(
                    trades = run.sessionTrades,
                    lifecycleLabel = if (inProgress) {
                        "Run in progress"
                    } else {
                        "Session ${run.date} · ${Formatters.runStartTimeDisplay(run.startedAt)}"
                    }
                )
            }

        return PerformanceUiState(
            rollup7d = Formatters.currency(rollup.pnl7d, showSign = true),
            rollup30d = Formatters.currency(rollup.pnl30d, showSign = true),
            winRate = Formatters.winRate(rollup.winDays, rollup.closedDays),
            rows = sortedRows,
            sortColumn = sortColumn,
            sortDirection = sortDirection,
            includeTouchTurnFields = includeTouchTurnFields,
            selectedRunId = selectedRunId,
            selectedRunTradeDetail = selectedDetail
        )
    }

    private fun toRowUi(
        run: StrategyRun,
        includeTouchTurnFields: Boolean,
        selectedRunId: String?
    ): StrategyRunRowUi {
        val inProgress = run.status == RunStatus.IN_PROGRESS
        val formattedPnL = if (inProgress) {
            "—"
        } else {
            Formatters.runPnLDisplay(run.pnl, run.positionOpened)
        }
        val isPnLNothing = formattedPnL == "Nothing"
        val (tradeSide, tradeSummary) = RunTradeDetailUiMapper.tradeSummaryForRow(run.sessionTrades)
        return StrategyRunRowUi(
            id = run.id,
            formattedStartTime = Formatters.runStartTimeDisplay(run.startedAt),
            formattedStopTime = Formatters.runStopTimeDisplay(run.stoppedAt, inProgress),
            tradeSideLabel = tradeSide,
            tradeSummary = tradeSummary,
            hasTradeDetail = run.sessionTrades.isNotEmpty(),
            isSelected = run.id == selectedRunId,
            liquidityCandle = if (includeTouchTurnFields && !inProgress) {
                Formatters.yesNo(run.hadLiquidityCandle)
            } else {
                "—"
            },
            ordersPlaced = if (includeTouchTurnFields && !inProgress) {
                Formatters.yesNo(run.ordersPlacedForCandle)
            } else {
                "—"
            },
            formattedPnL = formattedPnL,
            isPositivePnL = run.pnl > 0.005,
            isPnLNothing = isPnLNothing,
            isInProgress = inProgress,
            canDelete = !inProgress
        )
    }

    private fun sortRuns(
        runs: List<StrategyRun>,
        sortColumn: RunSortColumn,
        sortDirection: SortDirection
    ): List<StrategyRun> {
        val comparator = when (sortColumn) {
            RunSortColumn.START -> compareBy<StrategyRun> { it.startedAt.ifBlank { it.date } }
            RunSortColumn.STOP -> compareBy { it.stoppedAt.ifBlank { it.startedAt } }
            RunSortColumn.LIQUIDITY -> compareBy { it.hadLiquidityCandle == true }
            RunSortColumn.ORDERS -> compareBy { it.ordersPlacedForCandle == true }
            RunSortColumn.PNL -> compareBy { it.pnl }
        }
        return if (sortDirection == SortDirection.DESCENDING) {
            runs.sortedWith(comparator.reversed())
        } else {
            runs.sortedWith(comparator)
        }
    }
}
