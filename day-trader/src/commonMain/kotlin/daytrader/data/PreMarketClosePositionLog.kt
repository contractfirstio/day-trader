package daytrader.data

import daytrader.broker.BrokerPosition
import daytrader.presentation.Formatters

/** Console log for positions that would be closed before RTH cash session end. */
object PreMarketClosePositionLog {
    fun logWouldClosePosition(
        position: BrokerPosition,
        sessionDateIso: String,
        marketZoneId: String,
        minutesBeforeClose: Int = StrategyCatalog.CLOSE_POSITIONS_BEFORE_MARKET_CLOSE_MIN,
        runningInstanceId: String? = null
    ) {
        line(wouldClosePositionMessage(position, sessionDateIso, marketZoneId, minutesBeforeClose, runningInstanceId))
    }

    private fun wouldClosePositionMessage(
        position: BrokerPosition,
        sessionDateIso: String,
        marketZoneId: String,
        minutesBeforeClose: Int,
        runningInstanceId: String?
    ): String {
        val side = when {
            position.quantity > 0 -> "long"
            position.quantity < 0 -> "short"
            else -> "flat"
        }
        val qty = kotlin.math.abs(position.quantity)
        val pnl = Formatters.money(position.totalUnrealizedPnL, position.currency, showSign = true)
        val instancePart = runningInstanceId?.let { " instance=$it" } ?: ""
        return "Would close $side position for ${position.symbol}$instancePart at ${minutesBeforeClose}m before " +
            "RTH close ($sessionDateIso, $marketZoneId): qty=$qty " +
            "avg=${Formatters.moneyPlain(position.avgPrice, position.currency)} " +
            "mkt=${Formatters.moneyPlain(position.marketPrice, position.currency)} " +
            "unrealized=$pnl — closePosition not called."
    }

    private fun line(message: String) {
        println("[PreMarketClose] $message")
    }
}
