package daytrader.e2e.support

import daytrader.broker.SymbolMarkets
import daytrader.domain.SessionStatus
import daytrader.domain.StrategyDeployment
import daytrader.domain.StrategySession
import daytrader.domain.effectivePnL
import daytrader.domain.configurationRollupsForDeployments
import daytrader.domain.rollups
import daytrader.domain.rollupsForConfiguration
import daytrader.presentation.Formatters
import daytrader.presentation.strategies.StrategiesViewModel
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

/** Asserts list row, filtered summary, and session history rollup fields stay in sync. */
object E2ESessionRollupHelper {
    data class ExpectedRollupUi(
        val formattedTotalPnL: String,
        val formattedWinRate: String,
        val formattedNoTradeRate: String,
        val formattedNetPnL: String,
        val formattedRollup30d: String,
        val formattedSummaryWinRate: String = formattedWinRate,
        val formattedSummaryNoTradeRate: String = formattedNoTradeRate,
    )

    fun closedTradedSession(
        id: String,
        sessionDate: String = E2ETestFixtures.SESSION_DATE,
        pnl: Double,
        stoppedAt: String = "${sessionDate}T10:00:00",
    ): StrategySession = StrategySession(
        id = id,
        date = sessionDate,
        startedAt = "${sessionDate}T09:30:00",
        stoppedAt = stoppedAt,
        pnl = pnl,
        trades = 2,
        maxAtRisk = 500,
        status = SessionStatus.CLOSED,
        positionOpened = true,
    )

    fun expectedRollupUi(
        deployment: StrategyDeployment,
        sessionDate: String = E2ETestFixtures.SESSION_DATE,
        allDeployments: List<StrategyDeployment> = listOf(deployment),
    ): ExpectedRollupUi {
        val closedSessions = deployment.sessionHistory.filter { it.status == SessionStatus.CLOSED }
        val rollup = closedSessions.rollups(sessionDate)
        val configRollup = closedSessions.rollupsForConfiguration(sessionDate, deployment)
        val summaryConfigRollup = configurationRollupsForDeployments(allDeployments, sessionDate)
        val netPnLByCurrency = mutableMapOf<String, Double>()
        allDeployments.forEach { instance ->
            val instanceCurrency = instance.instrument?.currency
                ?: SymbolMarkets.currencyCode(instance.symbol)
            val closedPnL = instance.sessionHistory
                .filter { it.status == SessionStatus.CLOSED }
                .sumOf { it.effectivePnL() }
            netPnLByCurrency.merge(instanceCurrency, closedPnL, Double::plus)
        }
        val formattedNetPnL = when {
            netPnLByCurrency.isEmpty() -> "—"
            else -> netPnLByCurrency.entries
                .sortedBy { it.key }
                .joinToString(" · ") { (code, amount) ->
                    Formatters.money(amount, code, showSign = true)
                }
        }
        return ExpectedRollupUi(
            formattedTotalPnL = Formatters.currency(rollup.totalPnl, showSign = true),
            formattedWinRate = Formatters.winRate(configRollup.winDays, configRollup.lossDays),
            formattedNoTradeRate = Formatters.noTradeRate(configRollup.noTradeDays, configRollup.closedDays),
            formattedNetPnL = formattedNetPnL,
            formattedRollup30d = Formatters.currency(rollup.pnl30d, showSign = true),
            formattedSummaryWinRate = Formatters.winRate(summaryConfigRollup.winDays, summaryConfigRollup.lossDays),
            formattedSummaryNoTradeRate = Formatters.noTradeRate(
                summaryConfigRollup.noTradeDays,
                summaryConfigRollup.closedDays
            ),
        )
    }

    fun assertRollupsConsistent(
        viewModel: StrategiesViewModel,
        deploymentId: String,
        expected: ExpectedRollupUi,
        expectSummary: Boolean = true,
    ) {
        val row = viewModel.listState.value.filteredRows.find { it.id == deploymentId }
        assertNotNull(row, "expected list row for $deploymentId")
        assertEquals(expected.formattedTotalPnL, row.formattedTotalPnL, "list row total PnL")
        assertEquals(expected.formattedWinRate, row.formattedWinRate, "list row win rate")
        assertEquals(expected.formattedNoTradeRate, row.formattedNoTradeRate, "list row no-trade rate")

        if (expectSummary) {
            val summary = viewModel.listState.value.filteredSummary
            assertNotNull(summary, "expected filtered summary")
            assertEquals(expected.formattedNetPnL, summary.formattedNetPnL, "summary net PnL")
            assertEquals(expected.formattedSummaryWinRate, summary.formattedWinRate, "summary win rate")
            assertEquals(
                expected.formattedSummaryNoTradeRate,
                summary.formattedNoTradeRate,
                "summary no-trade rate"
            )
        }

        val history = viewModel.detailState.value.sessionHistory
        assertNotNull(history, "expected session history state")
        assertEquals(expected.formattedRollup30d, history.rollup30d, "history 30d rollup")
        assertEquals(expected.formattedWinRate, history.winRate, "history win rate")
        assertEquals(expected.formattedNoTradeRate, history.noTradeRate, "history no-trade rate")
    }
}
