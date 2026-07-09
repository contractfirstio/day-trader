package daytrader.broker

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class IbGatewayConfigTest {
    @Test
    fun hasFlexTradeSync_requiresBothTokenAndQueryId() {
        assertFalse(IbGatewayConfig(flexToken = "token").hasFlexTradeSync)
        assertFalse(IbGatewayConfig(flexTradesQueryId = "123").hasFlexTradeSync)
        assertTrue(
            IbGatewayConfig(flexToken = "token", flexTradesQueryId = "1234567").hasFlexTradeSync
        )
    }

    @Test
    fun hasFlexTradeSync_ignoresBlankValues() {
        assertFalse(
            IbGatewayConfig(flexToken = "  ", flexTradesQueryId = "123").hasFlexTradeSync
        )
        assertFalse(
            IbGatewayConfig(flexToken = "token", flexTradesQueryId = "  ").hasFlexTradeSync
        )
    }
}
