package daytrader.broker

import com.ib.client.ExecutionFilter

/**
 * Builds [ExecutionFilter] for TWS [reqExecutions].
 *
 * IB only returns the current session day's executions via TWS — not historical settled trades.
 * Historical account trades require the Flex Web Service ([IbFlexHistoricalTradeSync]).
 */
internal object IbExecutionFilterFactory {
    fun forTodayExecutions(accountCode: String): ExecutionFilter = ExecutionFilter().apply {
        clientId(ALL_CLIENTS)
        if (accountCode.isNotBlank()) {
            acctCode(accountCode)
        }
    }

    const val ALL_CLIENTS = 0
}
