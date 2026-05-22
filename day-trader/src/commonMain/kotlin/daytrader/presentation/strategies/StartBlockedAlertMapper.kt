package daytrader.presentation.strategies

import daytrader.broker.BrokerPosition
import daytrader.domain.StrategyInstance
import daytrader.domain.instanceDisplayName

data class StartBlockedByPositionAlert(
    val instanceDisplayName: String,
    val instanceSymbol: String,
    val position: LivePositionUi,
    val summary: String,
    val positionDetails: String,
    val reason: String
)

object StartBlockedAlertMapper {
    fun from(instance: StrategyInstance, position: BrokerPosition): StartBlockedByPositionAlert {
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
            "Strategy instances must start from a flat position. Close or flatten this holding in IB " +
                "before starting the instance."
        return StartBlockedByPositionAlert(
            instanceDisplayName = displayName,
            instanceSymbol = instance.symbol,
            position = positionUi,
            summary = summary,
            positionDetails = positionDetails.trim(),
            reason = reason
        )
    }
}
