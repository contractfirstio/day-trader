package daytrader.presentation.strategies

import daytrader.domain.DeploymentMarket
import daytrader.domain.SessionStatus
import daytrader.domain.StrategyDeployment
import daytrader.domain.StrategySession
import daytrader.domain.StrategyType
import daytrader.domain.TouchTurnSessionOutcome
import daytrader.domain.rollups
import daytrader.presentation.Formatters
import daytrader.presentation.positions.SortDirection

object SessionHistoryUiMapper {
    fun build(
        instance: StrategyDeployment,
        sessionDate: String,
        sortColumn: SessionHistorySortColumn,
        sortDirection: SortDirection,
        selectedRunId: String? = null,
        marketZoneFilter: String? = null,
        marketFilterLabel: String? = null
    ): SessionHistoryUiState {
        val closedSessions = instance.sessionHistory
            .filter { it.status == SessionStatus.CLOSED }
            .filter { DeploymentMarket.sessionMatchesMarketFilter(it, instance, marketZoneFilter) }
        val displaySessions = instance.sessionHistory
            .filter {
                it.status == SessionStatus.CLOSED || it.status == SessionStatus.IN_PROGRESS
            }
            .filter { DeploymentMarket.sessionMatchesMarketFilter(it, instance, marketZoneFilter) }
        val isTouchTurn = instance.strategyType == StrategyType.TOUCH_AND_TURN_SCALPER
        val sortedRows = sortRuns(displaySessions, sortColumn, sortDirection)
            .map { toRowUi(it, isTouchTurn, selectedRunId, instance) }
        val rollup = closedSessions.rollups(sessionDate)
        val selectedRun = displaySessions.find { it.id == selectedRunId }
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
            rollup30d = Formatters.currency(rollup.pnl30d, showSign = true),
            winRate = Formatters.winRate(rollup.winDays, rollup.closedDays),
            rows = sortedRows,
            sortColumn = sortColumn,
            sortDirection = sortDirection,
            selectedRunId = selectedRunId,
            selectedSessionTradeDetail = selectedDetail,
            marketFilterLabel = marketFilterLabel
        )
    }

    private fun toRowUi(
        run: StrategySession,
        isTouchTurn: Boolean,
        selectedRunId: String?,
        instance: StrategyDeployment
    ): StrategySessionRowUi {
        val inProgress = run.status == SessionStatus.IN_PROGRESS
        val isSelected = run.id == selectedRunId
        val formattedPnL = if (inProgress) {
            "—"
        } else {
            Formatters.runPnLDisplay(run.pnl, run.positionOpened)
        }
        val isPnLFlat = formattedPnL == Formatters.FLAT_PNL_LABEL
        val (_, tradeSummary) = SessionTradeDetailUiMapper.tradeSummaryForRow(run.sessionTrades)
        val positionLine = tradeSummary ?: "—"
        val runRecord = run.touchTurnRunRecord
        val milestones = run.touchTurnMilestones ?: runRecord?.milestones
        val pipelineGraph = if (isSelected && isTouchTurn && !inProgress) {
            milestones?.let {
                TouchTurnStatusBreadcrumbMapper.graphFromHistory(
                    milestones = it,
                    startedAt = run.startedAt,
                    stoppedAt = run.stoppedAt,
                    hadLiquidityCandle = run.hadLiquidityCandle
                        ?: runRecord?.let { r ->
                            r.decision.outcome != TouchTurnSessionOutcome.NO_TRADE_NOT_LIQUIDITY
                        },
                    ordersPlacedForCandle = run.ordersPlacedForCandle
                        ?: runRecord?.let { r ->
                            r.decision.outcome == TouchTurnSessionOutcome.TRADE_BRACKET_SUBMITTED
                        },
                    positionOpened = run.positionOpened,
                    decisionOutcome = runRecord?.decision?.outcome,
                    instanceId = instance.id,
                    symbol = instance.symbol
                )
            }
        } else {
            null
        }
        val touchTurnRunDetail = if (isSelected) {
            runRecord?.let { TouchTurnRunRecordUiMapper.from(it, run) }
        } else {
            null
        }
        return StrategySessionRowUi(
            id = run.id,
            deploymentId = instance.id,
            sessionLogFolder = SessionLogUi.logFolderRelativePath(instance.id, run.id),
            formattedSessionTime = Formatters.runSessionTimeDisplay(
                startedAt = run.startedAt,
                stoppedAt = run.stoppedAt,
                inProgress = inProgress
            ),
            positionLine = positionLine,
            hasTradeDetail = run.sessionTrades.isNotEmpty(),
            hasPipelineLog = isTouchTurn &&
                !inProgress &&
                (milestones != null || runRecord != null),
            isSelected = isSelected,
            formattedPnL = formattedPnL,
            isPositivePnL = run.pnl > 0.005,
            isPnLFlat = isPnLFlat,
            isInProgress = inProgress,
            canDelete = !inProgress,
            pipelineGraph = pipelineGraph,
            touchTurnRunDetail = touchTurnRunDetail
        )
    }

    private fun sortRuns(
        runs: List<StrategySession>,
        sortColumn: SessionHistorySortColumn,
        sortDirection: SortDirection
    ): List<StrategySession> {
        val comparator = when (sortColumn) {
            SessionHistorySortColumn.TIME -> compareBy<StrategySession> { it.startedAt.ifBlank { it.date } }
            SessionHistorySortColumn.PNL -> compareBy { it.pnl }
        }
        return if (sortDirection == SortDirection.DESCENDING) {
            runs.sortedWith(comparator.reversed())
        } else {
            runs.sortedWith(comparator)
        }
    }
}
