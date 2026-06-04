package daytrader.broker.emulator

import daytrader.data.persistence.AppDataFiles
import kotlin.test.Test
import kotlin.test.assertEquals

class EmulatorLogScopeTest {

    @Test
    fun resolveEngineLogPath_usesSessionDirectoryWhileBound() {
        EmulatorLogScope.bind("dep-1", "sess-1")
        try {
            assertEquals(
                AppDataFiles.sessionEmulatorEngineLogFileName("dep-1", "sess-1"),
                EmulatorLogScope.resolveEngineLogPath()
            )
        } finally {
            EmulatorLogScope.clear()
        }
    }

    @Test
    fun resolveEngineLogPath_fallsBackToGlobalWhenUnbound() {
        EmulatorLogScope.clear()
        assertEquals(AppDataFiles.emulatorEngineLogFileName(), EmulatorLogScope.resolveEngineLogPath())
    }
}
