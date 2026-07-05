package daytrader.test

import daytrader.e2e.support.E2EProcessCleanup
import org.junit.jupiter.api.extension.AfterEachCallback
import org.junit.jupiter.api.extension.BeforeEachCallback
import org.junit.jupiter.api.extension.Extension
import org.junit.jupiter.api.extension.ExtensionContext

/** Resets process-wide singletons so desktop tests do not pollute each other in one JVM. */
class GlobalTestCleanupExtension : Extension, BeforeEachCallback, AfterEachCallback {
    override fun beforeEach(context: ExtensionContext) {
        TestJvmIsolation.ensureJvmDataDirectory()
        E2EProcessCleanup.resetAll()
    }

    override fun afterEach(context: ExtensionContext) {
        E2EProcessCleanup.resetAll()
    }
}
