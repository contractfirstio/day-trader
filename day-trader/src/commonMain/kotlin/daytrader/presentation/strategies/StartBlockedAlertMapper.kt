package daytrader.presentation.strategies

import daytrader.gateway.AccountPosition
import daytrader.domain.StrategyDeployment
import daytrader.domain.instanceDisplayName

data class StartBlockedByPositionAlert(
    val instanceDisplayName: String,
    val instanceSymbol: String,
    val position: LivePositionUi?,
    val summary: String,
    val positionDetails: String,
    val reason: String
)

object StartBlockedAlertMapper {
    fun from(instance: StrategyDeployment, position: AccountPosition): StartBlockedByPositionAlert {
        val displayName = instanceDisplayName(instance.strategyType, instance.symbol)
        val positionUi = LiveBrokerUiMapper.positionUi(position)
        val summary =
            "Cannot start \"$displayName\" while Interactive Brokers has an open position for ${instance.symbol}."
        val positionDetails = buildString {
            appendLine("Existing position")
            appendLine("• ${positionUi.symbol} — ${positionUi.companyName}")
            appendLine("• ${positionUi.sideLabel} · ${positionUi.quantity} shares")
            appendLine("• Avg ${positionUi.formattedAvgPrice} · Market ${positionUi.formattedMarketPrice}")
            append("• Unrealized P&L ${positionUi.formattedUnrealizedPnL}")
            positionUi.formattedDailyChange?.let { daily ->
                appendLine()
                append("• Today $daily")
            }
        }
        val reason =
            "Deployments must start from a flat position. Close or flatten this holding in Interactive Brokers " +
                "before starting the deployment."
        return StartBlockedByPositionAlert(
            instanceDisplayName = displayName,
            instanceSymbol = instance.symbol,
            position = positionUi,
            summary = summary,
            positionDetails = positionDetails.trim(),
            reason = reason
        )
    }

    fun fromReplayCaptureNotFound(instance: StrategyDeployment): StartBlockedByPositionAlert {
        val displayName = instanceDisplayName(instance.strategyType, instance.symbol)
        val summary =
            "Cannot start \"$displayName\" — no hybrid session capture was found for ${instance.symbol}."
        val details = buildString {
            appendLine("Deployment symbol: ${instance.symbol}")
            appendLine()
            append(
                "Record a hybrid (paper-live-ib) session for this symbol, or browse to its capture " +
                    "folder from the replay picker so it appears in the catalog."
            )
        }
        val reason =
            "Replay loads market data from captured hybrid sessions discovered under paper-live-ib."
        return StartBlockedByPositionAlert(
            instanceDisplayName = displayName,
            instanceSymbol = instance.symbol,
            position = null,
            summary = summary,
            positionDetails = details.trim(),
            reason = reason
        )
    }
}
