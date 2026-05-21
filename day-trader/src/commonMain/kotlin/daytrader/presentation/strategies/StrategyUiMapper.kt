package daytrader.presentation.strategies

import daytrader.data.StrategyCatalog
import daytrader.domain.StrategyInstance
import daytrader.domain.instanceDisplayName
import daytrader.presentation.Formatters

object StrategyUiMapper {
    fun displayName(instance: StrategyInstance): String =
        instanceDisplayName(instance.strategyType, instance.symbol)

    fun toRowUi(instance: StrategyInstance): StrategyInstanceRowUi = StrategyInstanceRowUi(
        id = instance.id,
        name = displayName(instance),
        strategyTypeLabel = StrategyCatalog.displayName(instance.strategyType),
        status = instance.status,
        formattedTodayPnL = Formatters.currency(instance.todayPnL, showSign = true),
        isPositivePnL = instance.todayPnL >= 0,
        paramsSummary = Formatters.paramsSummary(instance.symbol, instance.maxDollars),
        tradesToday = instance.tradesToday
    )

    fun strategyDisplayName(instance: StrategyInstance): String =
        StrategyCatalog.displayName(instance.strategyType)

    fun strategyDescription(instance: StrategyInstance): String =
        StrategyCatalog.description(instance.strategyType)

    fun formattedTodayPnL(instance: StrategyInstance): String =
        Formatters.currency(instance.todayPnL, showSign = true)

    fun paramsSummary(instance: StrategyInstance): String =
        Formatters.paramsSummary(instance.symbol, instance.maxDollars)
}
