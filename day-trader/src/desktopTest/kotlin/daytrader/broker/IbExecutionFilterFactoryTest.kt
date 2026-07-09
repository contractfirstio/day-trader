package daytrader.broker

import kotlin.test.Test
import kotlin.test.assertEquals

class IbExecutionFilterFactoryTest {
    @Test
    fun forTodayExecutions_usesAllClientsWithoutTimeFilter() {
        val filter = IbExecutionFilterFactory.forTodayExecutions(accountCode = "DU123456")
        assertEquals(IbExecutionFilterFactory.ALL_CLIENTS, filter.clientId())
        assertEquals("DU123456", filter.acctCode())
    }
}
