package daytrader.presentation.strategies

import daytrader.broker.SessionTradeMatcher
import daytrader.broker.SessionTradePnL
import daytrader.broker.SymbolMarkets
import daytrader.domain.DeploymentStatus
import daytrader.domain.SessionStatus
import daytrader.domain.SessionTrade
import daytrader.domain.StrategyDeployment
import daytrader.domain.inProgressSession
import daytrader.gateway.AccountPosition
import daytrader.gateway.BrokerFill

data class LiveSessionTradesUiState(
    val symbol: String,
    val runLabel: String?,
    val lifecycleLabel: String?,
    val tradeDetail: SessionTradeDetailUiState,
    val emptyMessage: String?
)

object LiveSessionTradesUiMapper {
    fun forDeployment(
        instance: StrategyDeployment,
        liveFills: List<BrokerFill>,
        brokerPosition: AccountPosition? = null
    ): LiveSessionTradesUiState? {
        val symbol = instance.symbol
        val currency = brokerPosition?.currency
            ?: liveFills.firstOrNull { SymbolMarkets.symbolsMatch(symbol, it.symbol) }?.currency
            ?: "USD"
        return when (instance.status) {
            DeploymentStatus.RUNNING -> {
                val run = instance.inProgressSession() ?: return null
                val fills = SessionTradePnL.fillsForDisplay(
                    symbol = symbol,
                    startedAt = run.startedAt,
                    stoppedAt = null,
                    fills = liveFills
                )
                val trades = SessionTradeMatcher.toSessionTrades(fills)
                val unrealized = SessionTradePnL.unrealizedForSymbol(symbol, brokerPosition)
                if (trades.isEmpty() && unrealized == 0.0) return null
                fromTrades(
                    symbol = symbol,
                    runLabel = "Session open",
                    lifecycleLabel = lifecycleLabel(trades, unrealized, brokerPosition),
                    trades = trades,
                    unrealizedPnL = unrealized,
                    currency = currency
                )
            }
            else -> {
                val lastRun = instance.sessionHistory
                    .filter { it.status == SessionStatus.CLOSED && it.sessionTrades.isNotEmpty() }
                    .maxByOrNull { it.stoppedAt.ifBlank { it.startedAt } }
                    ?: return null
                val trades = lastRun.sessionTrades
                fromTrades(
                    symbol = symbol,
                    runLabel = lastRun.date,
                    lifecycleLabel = "Session ended — verify fills below",
                    trades = trades,
                    unrealizedPnL = 0.0,
                    currency = trades.firstOrNull()?.currency ?: currency
                )
            }
        }
    }

    private fun lifecycleLabel(
        trades: List<SessionTrade>,
        unrealizedPnL: Double,
        brokerPosition: AccountPosition?
    ): String? {
        val hasPosition = brokerPosition?.quantity?.let { it != 0 } == true
        val hasExitFill = trades.any { (it.realizedPnL ?: 0.0) != 0.0 }
        return when {
            hasExitFill && !hasPosition -> "Position closed — realized P&L on exit fill(s)"
            hasPosition && unrealizedPnL != 0.0 -> "Position open — unrealized P&L updating with market"
            hasPosition -> "Position open — waiting for stop or take-profit"
            trades.isNotEmpty() -> "Entry filled — bracket working"
            else -> null
        }
    }

    private fun fromTrades(
        symbol: String,
        runLabel: String?,
        lifecycleLabel: String?,
        trades: List<SessionTrade>,
        unrealizedPnL: Double,
        currency: String
    ): LiveSessionTradesUiState? {
        val tradeDetail = SessionTradeDetailUiMapper.fromSessionTrades(
            trades = trades,
            unrealizedPnL = unrealizedPnL,
            lifecycleLabel = lifecycleLabel,
            runLabel = runLabel
        ) ?: return null
        return LiveSessionTradesUiState(
            symbol = symbol,
            runLabel = runLabel,
            lifecycleLabel = lifecycleLabel,
            tradeDetail = tradeDetail,
            emptyMessage = null
        )
    }
}
