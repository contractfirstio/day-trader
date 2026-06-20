package daytrader.test

import daytrader.broker.emulator.EmulatorLogScope
import daytrader.data.SessionMarketDataCapture
import org.junit.jupiter.api.extension.AfterEachCallback
import org.junit.jupiter.api.extension.Extension
import org.junit.jupiter.api.extension.ExtensionContext

/** Resets process-wide singletons so desktop tests do not pollute each other in one JVM. */
class GlobalTestCleanupExtension : Extension, AfterEachCallback {
    override fun afterEach(context: ExtensionContext) {
        SessionMarketDataCapture.stopAll()
        EmulatorLogScope.clear()
    }
}
