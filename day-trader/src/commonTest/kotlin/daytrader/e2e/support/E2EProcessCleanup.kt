package daytrader.e2e.support

import daytrader.broker.emulator.EmulatorLogScope
import daytrader.data.SessionMarketDataCapture
import daytrader.presentation.ui.UiFaultBus

/**
 * Resets process-wide mutable state shared across E2E/BDD scenarios and programmatic E2E tests
 * in the same JVM. Call from Cucumber hooks, JUnit extensions, and [daytrader.e2e.E2EWorld.reset].
 */
object E2EProcessCleanup {
    fun resetAll() {
        SessionMarketDataCapture.stopAll()
        EmulatorLogScope.clear()
        UiFaultBus.clearAll()
    }

    fun isClean(): Boolean = SessionMarketDataCapture.activeTargets().isEmpty()

    fun requireClean(context: String) {
        require(isClean()) {
            "$context: expected no active SessionMarketDataCapture targets but found " +
                SessionMarketDataCapture.activeTargets()
        }
    }
}
