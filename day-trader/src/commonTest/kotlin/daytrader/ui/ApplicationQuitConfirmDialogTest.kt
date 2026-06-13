package daytrader.ui

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ApplicationQuitConfirmDialogTest {
    @Test
    fun buildQuitWarningMessage_singleSession_includesSymbolAndFlattenWarning() {
        val message = buildQuitWarningMessage(count = 1, symbolList = "00981")
        assertTrue(message.contains("1 running strategy session"))
        assertTrue(message.contains("00981"))
        assertTrue(message.contains("close open positions at market"))
    }

    @Test
    fun buildQuitWarningMessage_multipleSessions_usesPlural() {
        val message = buildQuitWarningMessage(count = 3, symbolList = "00981, 09988, AAPL")
        assertTrue(message.contains("3 running strategy sessions"))
        assertEquals(
            "You have 3 running strategy sessions (00981, 09988, AAPL). Quitting will stop those sessions, " +
                "cancel any working orders, and close open positions at market.",
            message
        )
    }

    @Test
    fun buildRunningSessionsWarningMessage_changeMode_usesCustomConsequence() {
        val message = buildRunningSessionsWarningMessage(
            count = 1,
            symbolList = "AAPL",
            consequenceText = "Changing mode will stop those sessions, cancel any working orders, " +
                "and close open positions at market."
        )
        assertTrue(message.contains("Changing mode will stop"))
        assertTrue(message.contains("AAPL"))
    }
}
