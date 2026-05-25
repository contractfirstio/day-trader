package daytrader.presentation.strategies

import daytrader.domain.SessionStatus
import daytrader.domain.StrategyDeployment
import daytrader.domain.StrategySession
import daytrader.domain.StrategyType
import daytrader.domain.rollups
import daytrader.presentation.Formatters
import daytrader.presentation.positions.SortDirection

object SessionHistoryUiMapper {
    fun build(
        instance: StrategyDeployment,
        sessionDate: String,
        sortColumn: SessionHistorySortColumn,
        sortDirection: SortDirection,
        selectedRunId: String? = null
    ): SessionHistoryUiState {
        val closedSessions = instance.sessionHistory.filter { it.status == SessionStatus.CLOSED }
        val displaySessions = instance.sessionHistory.filter {
            it.status == SessionStatus.CLOSED || it.status == SessionStatus.IN_PROGRESS
        }
        val includeTouchTurnFields = instance.strategyType == StrategyType.TOUCH_AND_TURN_SCALPER
        val sortedRows = sortRuns(displaySessions, sortColumn, sortDirection)
            .map { toRowUi(it, includeTouchTurnFields, selectedRunId) }
        val rollup = closedSessions.rollups(sessionDate)
        val selectedRun = displaySessions.find { it.id == selectedRunId }
        val selectedPipeline = selectedRun
            ?.takeIf { includeTouchTurnFields && it.status == SessionStatus.CLOSED }
            ?.touchTurnMilestones
            ?.let { milestones ->
                TouchTurnStatusBreadcrumbMapper.stepsFromHistory(
                    milestones = milestones,
                    startedAt = selectedRun.startedAt,
                    stoppedAt = selectedRun.stoppedAt,
                    hadLiquidityCandle = selectedRun.hadLiquidityCandle,
                    ordersPlacedForCandle = selectedRun.ordersPlacedForCandle,
                    positionOpened = selectedRun.positionOpened
                )
            }
        val selectedDetail = selectedRun
            ?.takeIf { it.sessionTrades.isNotEmpty() }
            ?.let { run ->
                val inProgress = run.status == SessionStatus.IN_PROGRESS
                SessionTradeDetailUiMapper.fromSessionTrades(
                    trades = run.sessionTrades,
                    lifecycleLabel = if (inProgress) {
                        "Session open"
                    } else {
                        "Session ${run.date} · ${Formatters.runStartTimeDisplay(run.startedAt)}"
                    }
                )
            }

        return SessionHistoryUiState(
            rollup7d = Formatters.currency(rollup.pnl7d, showSign = true),
            rollup14d = Formatters.currency(rollup.pnl14d, showSign = true),
            rollup30d = Formatters.currency(rollup.pnl30d, showSign = true),
            winRate = Formatters.winRate(rollup.winDays, rollup.closedDays),
            rows = sortedRows,
            sortColumn = sortColumn,
            sortDirection = sortDirection,
            includeTouchTurnFields = includeTouchTurnFields,
            selectedRunId = selectedRunId,
            selectedSessionTradeDetail = selectedDetail,
            selectedTouchTurnPipeline = selectedPipeline
        )
    }

    private fun toRowUi(
        run: StrategySession,
        includeTouchTurnFields: Boolean,
        selectedRunId: String?
    ): StrategySessionRowUi {
        val inProgress = run.status == SessionStatus.IN_PROGRESS
        val formattedPnL = if (inProgress) {
            "—"
        } else {
            Formatters.runPnLDisplay(run.pnl, run.positionOpened)
        }
        val isPnLFlat = formattedPnL == Formatters.FLAT_PNL_LABEL
        val (tradeSide, tradeSummary) = SessionTradeDetailUiMapper.tradeSummaryForRow(run.sessionTrades)
        return StrategySessionRowUi(
            id = run.id,
            formattedStartTime = Formatters.runStartTimeDisplay(run.startedAt),
            formattedStopTime = Formatters.runStopTimeDisplay(run.stoppedAt, inProgress),
            tradeSideLabel = tradeSide,
            tradeSummary = tradeSummary,
            hasTradeDetail = run.sessionTrades.isNotEmpty(),
            hasPipelineLog = includeTouchTurnFields &&
                !inProgress &&
                run.touchTurnMilestones != null,
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
            isPnLFlat = isPnLFlat,
            isInProgress = inProgress,
            canDelete = !inProgress
        )
    }

    private fun sortRuns(
        runs: List<StrategySession>,
        sortColumn: SessionHistorySortColumn,
        sortDirection: SortDirection
    ): List<StrategySession> {
        val comparator = when (sortColumn) {
            SessionHistorySortColumn.START -> compareBy<StrategySession> { it.startedAt.ifBlank { it.date } }
            SessionHistorySortColumn.STOP -> compareBy { it.stoppedAt.ifBlank { it.startedAt } }
            SessionHistorySortColumn.LIQUIDITY -> compareBy { it.hadLiquidityCandle == true }
            SessionHistorySortColumn.ORDERS -> compareBy { it.ordersPlacedForCandle == true }
            SessionHistorySortColumn.PNL -> compareBy { it.pnl }
        }
        return if (sortDirection == SortDirection.DESCENDING) {
            runs.sortedWith(comparator.reversed())
        } else {
            runs.sortedWith(comparator)
        }
    }
}
