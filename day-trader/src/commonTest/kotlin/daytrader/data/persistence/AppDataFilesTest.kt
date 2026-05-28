package daytrader.data.persistence

import kotlin.test.Test
import kotlin.test.assertEquals

class AppDataFilesTest {

    @Test
    fun sessionDirectory_pairsLogsUnderOneFolder() {
        assertEquals(
            "sessions/inst-abc/session-def",
            AppDataFiles.sessionDirectory("inst-abc", "session-def")
        )
    }

    @Test
    fun sessionApplicationLogFileName_isBesidePricesLog() {
        assertEquals(
            "sessions/inst-abc/session-def/application.jsonl",
            AppDataFiles.sessionApplicationLogFileName("inst-abc", "session-def")
        )
        assertEquals(
            "sessions/inst-abc/session-def/prices.jsonl",
            AppDataFiles.sessionPriceLogFileName("inst-abc", "session-def")
        )
    }

    @Test
    fun sessionPendingLogFileName_isUnderDeployment() {
        assertEquals(
            "sessions/inst-abc/_pending.jsonl",
            AppDataFiles.sessionPendingLogFileName("inst-abc")
        )
    }

    @Test
    fun sessionOrphanLogFileName_isSharedOrphanSink() {
        assertEquals(
            "sessions/_unattributed/orphan.jsonl",
            AppDataFiles.sessionOrphanLogFileName()
        )
    }

    @Test
    fun emulatorLogFileNames_areUnderEmulatorDir() {
        assertEquals(
            "emulator/engine.jsonl",
            AppDataFiles.emulatorEngineLogFileName()
        )
        assertEquals(
            "emulator/prices.jsonl",
            AppDataFiles.emulatorPricesLogFileName()
        )
    }

    @Test
    fun ibPriceLogFileName_isUnderIbPricesDir() {
        assertEquals(
            "ib-prices/META.jsonl",
            AppDataFiles.ibPriceLogFileName("META")
        )
    }

    @Test
    fun safeFileNameComponent_replacesUnsafeCharacters() {
        assertEquals(
            "STREAM_META_107113386",
            AppDataFiles.safeFileNameComponent("STREAM:META:107113386")
        )
    }
}
