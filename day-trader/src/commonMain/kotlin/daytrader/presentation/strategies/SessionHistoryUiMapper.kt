package daytrader.presentation.strategies

import daytrader.domain.DeploymentMarket
import daytrader.domain.SessionStatus
import daytrader.domain.StrategyDeployment
import daytrader.domain.isTouchTurn
import daytrader.domain.StrategySession
import daytrader.domain.StrategyType
import daytrader.domain.rollups
import daytrader.domain.currentConfigurationFingerprint
import daytrader.domain.resolvedConfigurationFingerprint
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
        marketFilterLabel: String? = null,
        sessionRollupCache: SessionRollupCache? = null,
    ): SessionHistoryUiState {
        val closedSessions = mutableListOf<StrategySession>()
        val displaySessions = mutableListOf<StrategySession>()
        for (session in instance.sessionHistory.toList()) {
            if (!DeploymentMarket.sessionMatchesMarketFilter(session, instance, marketZoneFilter)) continue
            when (session.status) {
                SessionStatus.CLOSED -> {
                    closedSessions.add(session)
                    displaySessions.add(session)
                }
                SessionStatus.IN_PROGRESS -> displaySessions.add(session)
                else -> Unit
            }
        }
        val isTouchTurn = instance.isTouchTurn
        val sortedRows = sortRuns(displaySessions, sortColumn, sortDirection)
            .map { toRowUi(it, isTouchTurn, selectedRunId, instance) }
        val rollupScope = buildString {
            append(instance.id)
            append(':')
            append(marketZoneFilter ?: "all")
        }
        val rollup = sessionRollupCache?.rollups(rollupScope, closedSessions, sessionDate)
            ?: closedSessions.rollups(sessionDate)
        val configFingerprint = instance.currentConfigurationFingerprint()
        val configSessions = closedSessions.filter { session ->
            session.resolvedConfigurationFingerprint(instance) == configFingerprint
        }
        val configScope = buildString {
            append(rollupScope)
            append(":cfg:")
            append(configFingerprint)
        }
        val configRollup = sessionRollupCache?.rollups(configScope, configSessions, sessionDate)
            ?: configSessions.rollups(sessionDate)

        return SessionHistoryUiState(
            rollup30d = Formatters.currency(rollup.pnl30d, showSign = true),
            winRate = Formatters.winRate(configRollup.winDays, configRollup.lossDays),
            noTradeRate = Formatters.noTradeRate(configRollup.noTradeDays, configRollup.closedDays),
            rows = sortedRows,
            sortColumn = sortColumn,
            sortDirection = sortDirection,
            selectedRunId = selectedRunId,
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
        val opensOnTradingTab = isTouchTurn &&
            !inProgress &&
            (milestones != null || runRecord != null)
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
            isSelected = isSelected,
            formattedPnL = formattedPnL,
            isPositivePnL = run.pnl > 0.005,
            isPnLFlat = isPnLFlat,
            isInProgress = inProgress,
            canDelete = !inProgress,
            opensOnTradingTab = opensOnTradingTab
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
