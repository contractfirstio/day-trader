package daytrader.data

import daytrader.gateway.BrokerFill

/**
 * Fetches settled IB account trades via the Flex Web Service.
 * TWS [reqExecutions] only returns the current session day; historical trades require Flex.
 */
fun interface HistoricalTradeSync {
    suspend fun fetchTrades(): Result<List<BrokerFill>>
}
