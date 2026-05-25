package daytrader.presentation.strategies

import daytrader.gateway.WorkingOrder
import daytrader.data.StrategyCatalog
import daytrader.domain.SessionStatus
import daytrader.domain.StrategyDeployment
import daytrader.domain.instanceDisplayName
import daytrader.domain.inProgressSession
import daytrader.domain.rollups
import daytrader.presentation.Formatters

object StrategyUiMapper {
    fun displayName(instance: StrategyDeployment): String =
        instanceDisplayName(instance.strategyType, instance.symbol)

    fun toRowUi(
        instance: StrategyDeployment,
        sessionDate: String,
        brokerUnrealizedPnL: Double? = null,
        brokerOpenOrders: List<WorkingOrder> = emptyList()
    ): StrategyDeploymentRowUi {
        val closedSessions = instance.sessionHistory.filter { it.status == SessionStatus.CLOSED }
        val rollup = closedSessions.rollups(sessionDate)
        val card = DeploymentCardStateMapper.resolve(
            instance,
            sessionDate,
            brokerUnrealizedPnL,
            brokerOpenOrders
        )
        return StrategyDeploymentRowUi(
            id = instance.id,
            name = displayName(instance),
            strategyTypeLabel = StrategyCatalog.displayName(instance.strategyType),
            status = instance.status,
            cardAccent = card.accent,
            statusChipLabel = card.chipLabel,
            formattedTotalPnL = Formatters.currency(rollup.totalPnl, showSign = true),
            isPositiveTotalPnL = rollup.totalPnl >= 0,
            paramsSummary = Formatters.paramsSummary(instance.symbol, instance.maxDollars),
            tradesToday = instance.inProgressSession()?.trades ?: 0,
            liveTradeSummary = LiveExecutionUiMapper.toListSummary(instance).text,
            formattedRollup7d = Formatters.currency(rollup.pnl7d, showSign = true),
            isPositiveRollup7d = rollup.pnl7d >= 0,
            formattedRollup14d = Formatters.currency(rollup.pnl14d, showSign = true),
            isPositiveRollup14d = rollup.pnl14d >= 0,
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

    fun strategyDisplayName(instance: StrategyDeployment): String =
        StrategyCatalog.displayName(instance.strategyType)

    fun strategyDescription(instance: StrategyDeployment): String =
        StrategyCatalog.description(instance.strategyType)

    fun paramsSummary(instance: StrategyDeployment): String =
        Formatters.paramsSummary(instance.symbol, instance.maxDollars)
}
