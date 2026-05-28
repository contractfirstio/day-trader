package daytrader.presentation.strategies

import daytrader.data.StrategyCatalog
import daytrader.domain.ExecutionState
import daytrader.domain.DeploymentStatus
import daytrader.domain.StrategyDeployment
import daytrader.domain.inProgressSession
import daytrader.domain.riskReward
import daytrader.presentation.Formatters

object LiveExecutionUiMapper {
    fun toLiveState(instance: StrategyDeployment): LiveExecutionUiState {
        val execution = instance.live
        val isRunning = instance.status == DeploymentStatus.RUNNING
        val risk = execution.riskReward(
            maxDollars = instance.maxDollars,
            rewardMultiple = StrategyCatalog.rewardMultiple(instance.strategyType)
        )

        val headline = when {
            !isRunning -> "Session stopped"
            execution.state == ExecutionState.FILLED -> {
                val entry = execution.entryPrice?.let { Formatters.currencyPlain(it) } ?: "—"
                "${execution.side.label()} ${execution.quantity} ${instance.symbol} @ $entry"
            }
            execution.state == ExecutionState.WORKING ->
                "${execution.side.label()} ${execution.quantity} ${instance.symbol} — order working"
            else -> "Flat — awaiting setup"
        }

        val canManage = isRunning && execution.state == ExecutionState.FILLED
        val session = instance.inProgressSession()

        return LiveExecutionUiState(
            instanceId = instance.id,
            sessionId = session?.id,
            sessionLogFolder = session?.id?.let { SessionLogUi.logFolderRelativePath(instance.id, it) },
            showPanel = isRunning,
            isRunning = isRunning,
            canManagePosition = canManage,
            state = execution.state,
            headline = headline,
            stopPriceInput = execution.stopPrice?.let { "%.2f".format(it) } ?: "",
            entryPrice = execution.entryPrice?.let { Formatters.currencyPlain(it) },
            stopPrice = execution.stopPrice?.let { Formatters.currencyPlain(it) },
            targetPrice = execution.targetPrice?.let { Formatters.currencyPlain(it) },
            formattedRisk = risk.riskDollars?.let { Formatters.currency(-it) },
            formattedUpside = risk.upsideDollars?.let { Formatters.currency(it, showSign = true) },
            formattedUnrealized = risk.unrealizedPnL?.let { Formatters.currency(it, showSign = true) },
            isUnrealizedPositive = (risk.unrealizedPnL ?: 0.0) >= 0,
            riskPercentOfMax = risk.riskPercentOfMax?.let { "${String.format("%.0f", it)}% of risk budget" }
        )
    }

    fun toListSummary(instance: StrategyDeployment): LiveTradeListSummary {
        if (instance.status != DeploymentStatus.RUNNING) {
            return LiveTradeListSummary(null)
        }
        val execution = instance.live
        val text = when (execution.state) {
            ExecutionState.FILLED -> {
                val entry = execution.entryPrice?.let { Formatters.currencyPlain(it) } ?: "—"
                val stop = execution.stopPrice?.let { Formatters.currencyPlain(it) } ?: "—"
                "${execution.side.label()} ${execution.quantity} @ $entry · Stop $stop"
            }
            ExecutionState.WORKING ->
                "${execution.side.label()} ${execution.quantity} — working"
            ExecutionState.FLAT -> "Flat — watching"
        }
        return LiveTradeListSummary(text)
    }
}
