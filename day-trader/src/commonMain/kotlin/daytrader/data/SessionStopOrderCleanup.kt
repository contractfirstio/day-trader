package daytrader.data

import daytrader.gateway.BrokerGateway

/** Flattens broker state for a symbol when a strategy run stops (no working orders, no open position). */
object SessionStopOrderCleanup {
    fun flattenSymbolForSession(gateway: BrokerGateway, symbol: String) {
        gateway.flattenSymbolForSymbol(symbol)
    }
}
