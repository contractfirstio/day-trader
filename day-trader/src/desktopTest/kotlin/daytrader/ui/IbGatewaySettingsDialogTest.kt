package daytrader.ui

import daytrader.broker.IbGatewayConfig
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class IbGatewaySettingsDialogTest {
    @Test
    fun parseIbGatewaySettings_acceptsValidValues() {
        val parsed = parseIbGatewaySettings(
            host = " 127.0.0.1 ",
            port = "4002",
            clientId = "7",
            accountCode = " DU123 "
        )
        assertEquals(
            IbGatewayConfig(host = "127.0.0.1", port = 4002, clientId = 7, accountCode = "DU123"),
            parsed
        )
    }

    @Test
    fun parseIbGatewaySettings_rejectsInvalidPort() {
        assertNull(parseIbGatewaySettings("localhost", "0", "1", ""))
        assertNull(parseIbGatewaySettings("localhost", "70000", "1", ""))
    }
}
