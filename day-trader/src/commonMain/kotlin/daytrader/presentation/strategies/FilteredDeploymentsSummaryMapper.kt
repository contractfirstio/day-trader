package daytrader.presentation.strategies

import daytrader.broker.BrokerDeploymentIndex
import daytrader.broker.SymbolMarkets
import daytrader.domain.DeploymentPositionOutcomeCalculator
import daytrader.domain.DeploymentStatus
import daytrader.domain.ExecutionState
import daytrader.domain.SessionStatus
import daytrader.domain.StrategyDeployment
import daytrader.domain.lastClosed
import daytrader.domain.riskReward
import daytrader.domain.rollups
import daytrader.data.StrategyCatalog
import daytrader.gateway.AccountPosition
import daytrader.gateway.WorkingOrder
import daytrader.presentation.Formatters

data class FilteredDeploymentsSummaryUi(
    val runningCount: Int,
    val openPositionCount: Int,
    val showLiveBand: Boolean,
    val formattedUnrealized: String,
    val isPositiveUnrealized: Boolean? = null,
    val formattedMaxProfit: String? = null,
    val formattedStopOutcome: String? = null,
    val stopOutcomeIsMinWin: Boolean = false,
    val formattedLastSessionPnL: String = "—",
    val isPositiveLastSessionPnL: Boolean? = null,
    val formattedWinRate: String,
    val winRateIsPositive: Boolean? = null,
    val formattedNoTradeRate: String = "—",
    val formattedNetPnL: String,
    val isPositiveNetPnL: Boolean? = null,
)

object FilteredDeploymentsSummaryMapper {
    fun build(
        instances: List<StrategyDeployment>,
        sessionDate: String,
        brokerPositions: List<AccountPosition>,
        brokerOpenOrders: List<WorkingOrder>,
    ): FilteredDeploymentsSummaryUi? =
        build(
            instances = instances,
            sessionDate = sessionDate,
            brokerIndex = BrokerDeploymentIndex.build(instances, brokerPositions, brokerOpenOrders),
        )

    fun build(
        instances: List<StrategyDeployment>,
        sessionDate: String,
        brokerIndex: BrokerDeploymentIndex,
        sessionRollupCache: SessionRollupCache? = null,
    ): FilteredDeploymentsSummaryUi? {
        if (instances.isEmpty()) return null
        val runningCount = instances.count { it.status == DeploymentStatus.RUNNING }
        val unrealizedByCurrency = mutableMapOf<String, Double>()
        val maxProfitByCurrency = mutableMapOf<String, Double>()
        val stopOutcomeByCurrency = mutableMapOf<String, Double>()
        var openPositionCount = 0
        var hasLiveMetrics = false

        instances.forEach { instance ->
            val currency = deploymentCurrency(instance, brokerIndex)
            val brokerPosition = brokerIndex.openPosition(instance)
            val hasOpenPosition = brokerPosition != null ||
                (instance.status == DeploymentStatus.RUNNING &&
                    instance.live.state == ExecutionState.FILLED)
            if (!hasOpenPosition) return@forEach
            openPositionCount++
            val unrealized = unrealizedPnL(instance, brokerPosition) ?: return@forEach
            hasLiveMetrics = true
            unrealizedByCurrency.addAmount(currency, unrealized)
            DeploymentPositionOutcomeCalculator.resolve(
                instance,
                brokerPosition,
                brokerIndex.openOrders(instance),
            )
                ?.let { outcome ->
                    maxProfitByCurrency.addAmount(currency, outcome.maxProfit)
                    stopOutcomeByCurrency.addAmount(currency, outcome.stopOutcome)
                }
        }

        val closedSessions = instances.flatMap { deployment ->
            deployment.sessionHistory.filter { it.status == SessionStatus.CLOSED }
        }
        val rollup = sessionRollupCache?.rollupsForSummary(
            deploymentIds = instances.map { it.id },
            closedSessions = closedSessions,
            asOfSessionDate = sessionDate,
        ) ?: closedSessions.rollups(sessionDate)
        val lastSessionByCurrency = mutableMapOf<String, Double>()
        val netPnLByCurrency = mutableMapOf<String, Double>()
        instances.forEach { instance ->
            val currency = deploymentCurrency(instance, brokerIndex)
            val closed = instance.sessionHistory.filter { it.status == SessionStatus.CLOSED }
            netPnLByCurrency.addAmount(currency, closed.sumOf { it.pnl })
            instance.sessionHistory
                .filter { it.status == SessionStatus.CLOSED }
                .lastClosed()
                ?.pnl
                ?.let { lastSessionByCurrency.addAmount(currency, it) }
        }

        val stopOutcomeTotals = stopOutcomeByCurrency.filterValues { it != 0.0 }
        val stopSum = stopOutcomeTotals.values.sum()
        return FilteredDeploymentsSummaryUi(
            runningCount = runningCount,
            openPositionCount = openPositionCount,
            showLiveBand = hasLiveMetrics,
            formattedUnrealized = formatMoneyTotals(unrealizedByCurrency),
            isPositiveUnrealized = singleCurrencySign(unrealizedByCurrency),
            formattedMaxProfit = maxProfitByCurrency
                .takeIf { it.isNotEmpty() }
                ?.let { formatMoneyTotals(it) },
            formattedStopOutcome = stopOutcomeTotals
                .takeIf { it.isNotEmpty() }
                ?.let { formatMoneyTotals(it) },
            stopOutcomeIsMinWin = stopOutcomeTotals.isNotEmpty() && stopSum >= 0.0,
            formattedLastSessionPnL = formatMoneyTotals(lastSessionByCurrency),
            isPositiveLastSessionPnL = singleCurrencySign(lastSessionByCurrency),
            formattedWinRate = Formatters.winRate(rollup.winDays, rollup.lossDays),
            winRateIsPositive = when {
                rollup.tradedDays == 0 -> null
                rollup.winDays * 2 >= rollup.tradedDays -> true
                else -> false
            },
            formattedNoTradeRate = Formatters.noTradeRate(rollup.noTradeDays, rollup.closedDays),
            formattedNetPnL = formatMoneyTotals(netPnLByCurrency),
            isPositiveNetPnL = singleCurrencySign(netPnLByCurrency),
        )
    }

    private fun deploymentCurrency(
        instance: StrategyDeployment,
        brokerIndex: BrokerDeploymentIndex,
    ): String =
        brokerIndex.openPosition(instance)?.currency
            ?: instance.instrument?.currency
            ?: SymbolMarkets.currencyCode(instance.symbol)

    private fun unrealizedPnL(
        instance: StrategyDeployment,
        brokerPosition: AccountPosition?
    ): Double? {
        if (brokerPosition != null) return brokerPosition.totalUnrealizedPnL
        if (instance.live.state != ExecutionState.FILLED) return null
        return instance.live.riskReward(
            maxDollars = instance.maxDollars,
            rewardMultiple = StrategyCatalog.rewardMultiple(instance.strategyType)
        ).unrealizedPnL
    }

    private fun MutableMap<String, Double>.addAmount(currency: String, amount: Double) {
        merge(currency, amount, Double::plus)
    }

    private fun formatMoneyTotals(totals: Map<String, Double>): String =
        when {
            totals.isEmpty() -> "—"
            else -> totals.entries
                .sortedBy { it.key }
                .joinToString(" · ") { (currency, amount) ->
                    Formatters.money(amount, currency, showSign = true)
                }
        }

    private fun singleCurrencySign(totals: Map<String, Double>): Boolean? =
        when {
            totals.isEmpty() -> null
            totals.size == 1 -> totals.values.single() >= 0.0
            else -> null
        }
}
