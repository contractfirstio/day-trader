package daytrader.data

import daytrader.data.persistence.MergeFillsResult
import daytrader.gateway.BrokerFill
import kotlinx.coroutines.flow.StateFlow

interface FillsRepository {
    val fills: StateFlow<List<BrokerFill>>

    suspend fun awaitHydrated()

    fun mergeFills(incoming: List<BrokerFill>): MergeFillsResult

    /** IB Flex sync: replace stored Flex rows with the latest statement batch. */
    fun mergeFlexFills(incoming: List<BrokerFill>): MergeFillsResult

    fun flushPersistenceBlocking()
}
