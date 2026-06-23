package daytrader.broker

import daytrader.domain.SessionTrade
import daytrader.domain.sessionCommissionTotal
import daytrader.domain.sessionRealizedPnL
import daytrader.gateway.BrokerFill
import java.math.BigDecimal

/**
 * Applies an IB-shaped commission report to a single fill.
 *
 * Execution details arrive first with null commission/realized P&L; the commission report
 * then sets per-fill [BrokerFill.commission] and [BrokerFill.realizedPnL], matching IB's
 * execDetails + commissionAndFeesReport sequence.
 */
object FillCommissionReportApplier {
    private val calculator = TransactionCostCalculator()

    fun applyReport(
        fill: BrokerFill,
        orderType: String,
        priceBasedRealizedPnL: Double?,
    ): BrokerFill =
        fill.copy(
            commission = calculator.calculateCommission(fill.quantity, orderType).toDouble(),
            realizedPnL = priceBasedRealizedPnL ?: 0.0,
        )

    fun applyReport(
        trade: SessionTrade,
        orderType: String,
        priceBasedRealizedPnL: Double?,
    ): SessionTrade =
        trade.copy(
            commission = calculator.calculateCommission(trade.quantity, orderType).toDouble(),
            realizedPnL = priceBasedRealizedPnL ?: 0.0,
        )

    fun validateRoundTripEdge(trades: List<SessionTrade>) {
        val gross = BigDecimal.valueOf(trades.sessionRealizedPnL())
        val commission = BigDecimal.valueOf(trades.sessionCommissionTotal())
        calculator.validateEdge(gross, commission)
    }
}
