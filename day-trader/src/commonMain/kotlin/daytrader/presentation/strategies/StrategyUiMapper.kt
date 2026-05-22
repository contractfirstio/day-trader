package daytrader.presentation.strategies

import daytrader.broker.BrokerOpenOrder
import daytrader.data.StrategyCatalog
import daytrader.domain.RunStatus
import daytrader.domain.StrategyInstance
import daytrader.domain.instanceDisplayName
import daytrader.domain.inProgressRun
import daytrader.domain.rollups
import daytrader.presentation.Formatters

object StrategyUiMapper {
    fun displayName(instance: StrategyInstance): String =
        instanceDisplayName(instance.strategyType, instance.symbol)

    fun toRowUi(
        instance: StrategyInstance,
        sessionDate: String,
        brokerUnrealizedPnL: Double? = null,
        brokerOpenOrders: List<BrokerOpenOrder> = emptyList()
    ): StrategyInstanceRowUi {
        val closedRuns = instance.performance.filter { it.status == RunStatus.CLOSED }
        val rollup = closedRuns.rollups(sessionDate)
        val card = InstanceCardStateMapper.resolve(
            instance,
            sessionDate,
            brokerUnrealizedPnL,
            brokerOpenOrders
        )
        return StrategyInstanceRowUi(
            id = instance.id,
            name = displayName(instance),
            strategyTypeLabel = StrategyCatalog.displayName(instance.strategyType),
            status = instance.status,
            cardAccent = card.accent,
            statusChipLabel = card.chipLabel,
            formattedTotalPnL = Formatters.currency(rollup.totalPnl, showSign = true),
            isPositiveTotalPnL = rollup.totalPnl >= 0,
            paramsSummary = Formatters.paramsSummary(instance.symbol, instance.maxDollars),
            tradesToday = instance.inProgressRun()?.trades ?: 0,
            liveTradeSummary = LiveExecutionUiMapper.toListSummary(instance).text,
            formattedRollup7d = Formatters.currency(rollup.pnl7d, showSign = true),
            isPositiveRollup7d = rollup.pnl7d >= 0,
            formattedRollup30d = Formatters.currency(rollup.pnl30d, showSign = true),
            isPositiveRollup30d = rollup.pnl30d >= 0,
            formattedWinRate = Formatters.winRate(rollup.winDays, rollup.closedDays),
            winRateIsPositive = when {
                rollup.closedDays == 0 -> null
                rollup.winDays * 2 >= rollup.closedDays -> true
                else -> false
            },
            autoStartOnMarketOpen = instance.autoStartOnMarketOpen
        )
    }

    fun strategyDisplayName(instance: StrategyInstance): String =
        StrategyCatalog.displayName(instance.strategyType)

    fun strategyDescription(instance: StrategyInstance): String =
        StrategyCatalog.description(instance.strategyType)

    fun paramsSummary(instance: StrategyInstance): String =
        Formatters.paramsSummary(instance.symbol, instance.maxDollars)
}
