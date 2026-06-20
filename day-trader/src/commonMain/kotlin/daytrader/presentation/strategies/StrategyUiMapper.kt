package daytrader.presentation.strategies

import daytrader.broker.SymbolMarkets
import daytrader.domain.DeploymentPositionOutcomeCalculator
import daytrader.gateway.AccountPosition
import daytrader.gateway.WorkingOrder
import daytrader.data.StrategyCatalog
import daytrader.domain.ExecutionState
import daytrader.domain.SessionStatus
import daytrader.domain.StrategyDeployment
import daytrader.domain.riskReward
import daytrader.domain.instanceDisplayName
import daytrader.domain.inProgressSession
import daytrader.domain.lastClosed
import daytrader.domain.rollups
import daytrader.presentation.Formatters

object StrategyUiMapper {
    fun displayName(instance: StrategyDeployment): String =
        instanceDisplayName(instance.strategyType, instance.symbol)

    fun instrumentDisplayName(instance: StrategyDeployment): String =
        instance.companyName?.takeIf { it.isNotBlank() } ?: instance.symbol

    fun toRowUi(
        instance: StrategyDeployment,
        sessionDate: String,
        brokerUnrealizedPnL: Double? = null,
        brokerOpenOrders: List<WorkingOrder> = emptyList(),
        brokerPosition: AccountPosition? = null
    ): StrategyDeploymentRowUi {
        val closedSessions = instance.sessionHistory.filter { it.status == SessionStatus.CLOSED }
        val lastClosedSession = closedSessions.lastClosed()
        val rollup = closedSessions.rollups(sessionDate)
        val hasOpenPosition = brokerPosition != null ||
            (instance.status == daytrader.domain.DeploymentStatus.RUNNING &&
                instance.live.state == ExecutionState.FILLED)
        val positionPnL = positionUnrealizedPnL(instance, brokerPosition, brokerUnrealizedPnL)
        val positionOutcome = if (hasOpenPosition) {
            DeploymentPositionOutcomeCalculator.resolve(instance, brokerPosition, brokerOpenOrders)
        } else {
            null
        }
        val currency = brokerPosition?.currency
            ?: instance.instrument?.currency
            ?: SymbolMarkets.currencyCode(instance.symbol)
        val card = DeploymentCardStateMapper.resolve(
            instance,
            sessionDate,
            brokerUnrealizedPnL,
            brokerOpenOrders,
            hasOpenPosition = hasOpenPosition
        )
        return StrategyDeploymentRowUi(
            id = instance.id,
            name = displayName(instance),
            instrumentName = instrumentDisplayName(instance),
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
            formattedWinRate = Formatters.winRate(rollup.winDays, rollup.lossDays),
            winRateIsPositive = when {
                rollup.tradedDays == 0 -> null
                rollup.winDays * 2 >= rollup.tradedDays -> true
                else -> false
            },
            formattedNoTradeRate = Formatters.noTradeRate(rollup.noTradeDays, rollup.closedDays),
            formattedLastSessionPnL = lastClosedSession?.let {
                Formatters.money(it.pnl, currency, showSign = true)
            } ?: "—",
            isPositiveLastSessionPnL = lastClosedSession?.let { it.pnl >= 0 },
            autoStartOnMarketOpen = instance.autoStartOnMarketOpen,
            hasOpenPosition = hasOpenPosition,
            formattedPositionPnL = positionPnL?.let {
                Formatters.money(it, currency, showSign = true)
            },
            isPositivePositionPnL = positionPnL?.let { it >= 0 },
            formattedMaxProfit = positionOutcome?.maxProfit?.let {
                Formatters.money(it, currency, showSign = true)
            },
            formattedStopOutcome = positionOutcome?.stopOutcome?.let {
                Formatters.money(it, currency, showSign = true)
            },
            stopOutcomeIsMinWin = positionOutcome?.stopIsMinWin == true
        )
    }

    private fun positionUnrealizedPnL(
        instance: StrategyDeployment,
        brokerPosition: AccountPosition?,
        brokerUnrealizedPnL: Double?
    ): Double? {
        if (brokerPosition != null) return brokerUnrealizedPnL ?: brokerPosition.totalUnrealizedPnL
        if (instance.live.state != ExecutionState.FILLED) return null
        return instance.live.riskReward(
            maxDollars = instance.maxDollars,
            rewardMultiple = StrategyCatalog.rewardMultiple(instance.strategyType)
        ).unrealizedPnL
    }

    fun strategyDisplayName(instance: StrategyDeployment): String =
        StrategyCatalog.displayName(instance.strategyType)

    fun strategyDescription(instance: StrategyDeployment): String =
        StrategyCatalog.description(instance.strategyType)

    fun paramsSummary(instance: StrategyDeployment): String =
        Formatters.paramsSummary(instance.symbol, instance.maxDollars)
}
