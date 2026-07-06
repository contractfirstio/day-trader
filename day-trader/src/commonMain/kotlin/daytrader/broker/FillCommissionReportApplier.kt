package daytrader.broker

import daytrader.domain.SessionTrade
import daytrader.domain.sessionCommissionTotal
import daytrader.domain.sessionGrossPricePnL
import daytrader.gateway.BrokerFill
import java.math.BigDecimal

/**
 * Applies an IB-shaped commission report to a single fill.
 *
 * Execution details arrive first with null commission/realized P&L; the commission report
 * then sets per-fill [BrokerFill.commission] and [BrokerFill.realizedPnL], matching IB's
 * execDetails + commissionAndFeesReport sequence. On closing fills, [realizedPnL] is the
 * net round-trip P&L (price move minus all leg commissions), not gross price P&L.
 */
object FillCommissionReportApplier {
    private val calculator = TransactionCostCalculator()

    fun applyReport(
        fill: BrokerFill,
        orderType: String,
        priceBasedRealizedPnL: Double?,
        priorFillsForRoundTrip: List<BrokerFill> = emptyList(),
    ): BrokerFill {
        val commission = calculator.calculateCommission(fill.quantity, orderType).toDouble()
        return fill.copy(
            commission = commission,
            realizedPnL = ibNetRealizedPnL(priceBasedRealizedPnL, priorFillsForRoundTrip, commission),
        )
    }

    fun applyReport(
        trade: SessionTrade,
        orderType: String,
        priceBasedRealizedPnL: Double?,
        priorFillsForRoundTrip: List<SessionTrade> = emptyList(),
    ): SessionTrade {
        val commission = calculator.calculateCommission(trade.quantity, orderType).toDouble()
        return trade.copy(
            commission = commission,
            realizedPnL = ibNetRealizedPnL(
                priceBasedRealizedPnL,
                priorFillsForRoundTrip.sumOf { it.commission ?: 0.0 },
                commission,
            ),
        )
    }

    private fun ibNetRealizedPnL(
        priceBasedRealizedPnL: Double?,
        priorFillsForRoundTrip: List<BrokerFill>,
        thisLegCommission: Double,
    ): Double = ibNetRealizedPnL(
        priceBasedRealizedPnL,
        priorFillsForRoundTrip.sumOf { it.commission ?: 0.0 },
        thisLegCommission,
    )

    private fun ibNetRealizedPnL(
        priceBasedRealizedPnL: Double?,
        priorLegCommissionTotal: Double,
        thisLegCommission: Double,
    ): Double {
        if (priceBasedRealizedPnL == null) return 0.0
        return priceBasedRealizedPnL - priorLegCommissionTotal - thisLegCommission
    }

    fun validateRoundTripEdge(trades: List<SessionTrade>) {
        val commission = BigDecimal.valueOf(trades.sessionCommissionTotal())
        val gross = BigDecimal.valueOf(trades.sessionGrossPricePnL())
        calculator.validateEdge(gross, commission)
    }
}
