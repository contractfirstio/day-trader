package daytrader.diagnostics

import daytrader.domain.DeploymentStatus
import daytrader.domain.StrategyType
import daytrader.domain.defaultStrategyDeployment
import daytrader.domain.onSessionStarted
import daytrader.gateway.BrokerId
import daytrader.gateway.LiveQuote
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SessionPriceLogTest {
    @Test
    fun quoteHasData_trueWhenAnyFieldPresent() {
        assertTrue(SessionPriceLog.quoteHasData(LiveQuote(symbol = "AAPL", bid = 1.0)))
        assertTrue(SessionPriceLog.quoteHasData(LiveQuote(symbol = "AAPL", last = 1.0)))
        assertFalse(SessionPriceLog.quoteHasData(LiveQuote(symbol = "AAPL")))
    }

    @Test
    fun quotesEqual_comparesBidAskLast() {
        val a = LiveQuote(symbol = "AAPL", bid = 100.0, ask = 101.0, last = 100.5)
        val b = LiveQuote(symbol = "AAPL", bid = 100.0, ask = 101.0, last = 100.5)
        val c = LiveQuote(symbol = "AAPL", bid = 100.0, ask = 101.0, last = 100.6)
        assertTrue(SessionPriceLog.quotesEqual(a, b))
        assertFalse(SessionPriceLog.quotesEqual(a, c))
    }

    @Test
    fun recordQuoteSnapshot_noOpWithoutInstall() {
        SessionPriceLog.clearInstall()
        SessionPriceLog.recordQuoteSnapshot(
            brokerId = BrokerId.EMULATOR,
            incoming = mapOf("AAPL" to LiveQuote(symbol = "AAPL", bid = 1.0, ask = 2.0)),
            previous = emptyMap()
        )
    }

    @Test
    fun recordQuoteSnapshot_skipsWhenNoRunningSession() {
        SessionPriceLog.install {
            listOf(
                defaultStrategyDeployment(
                    strategyType = StrategyType.TOUCH_AND_TURN_SCALPER,
                    symbol = "AAPL",
                    maxDollars = 500,
                    status = DeploymentStatus.STOPPED
                )
            )
        }
        SessionPriceLog.recordQuoteSnapshot(
            brokerId = BrokerId.INTERACTIVE_BROKERS,
            incoming = mapOf("AAPL" to LiveQuote(symbol = "AAPL", bid = 1.0, ask = 2.0)),
            previous = emptyMap()
        )
        SessionPriceLog.clearInstall()
    }

    @Test
    fun recordQuoteSnapshot_skipsEmulatorBroker() {
        SessionPriceLog.install {
            listOf(
                defaultStrategyDeployment(
                    strategyType = StrategyType.TOUCH_AND_TURN_SCALPER,
                    symbol = "AAPL",
                    maxDollars = 500,
                    status = DeploymentStatus.RUNNING
                ).onSessionStarted("2026-05-22")
            )
        }
        SessionPriceLog.recordQuoteSnapshot(
            brokerId = BrokerId.EMULATOR,
            incoming = mapOf("AAPL" to LiveQuote(symbol = "AAPL", bid = 1.0, ask = 2.0)),
            previous = emptyMap()
        )
        SessionPriceLog.clearInstall()
    }

    @Test
    fun recordQuoteSnapshot_skipsUnchangedQuote() {
        val quote = LiveQuote(symbol = "AAPL", bid = 1.0, ask = 2.0, last = 1.5)
        SessionPriceLog.install {
            listOf(
                defaultStrategyDeployment(
                    strategyType = StrategyType.TOUCH_AND_TURN_SCALPER,
                    symbol = "AAPL",
                    maxDollars = 500,
                    status = DeploymentStatus.RUNNING
                ).onSessionStarted("2026-05-22")
            )
        }
        SessionPriceLog.recordQuoteSnapshot(
            brokerId = BrokerId.EMULATOR,
            incoming = mapOf("AAPL" to quote),
            previous = mapOf("AAPL" to quote)
        )
        SessionPriceLog.clearInstall()
    }
}
