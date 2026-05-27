package daytrader.data.persistence

import kotlin.test.Test
import kotlin.test.assertEquals

class AppDataFilesTest {

    @Test
    fun sessionTraceFileName_isPerDeploymentAndSession() {
        assertEquals(
            "session-traces/inst-abc/session-def.jsonl",
            AppDataFiles.sessionTraceFileName("inst-abc", "session-def")
        )
    }

    @Test
    fun sessionTracePendingFileName_isUnderDeployment() {
        assertEquals(
            "session-traces/inst-abc/_pending.jsonl",
            AppDataFiles.sessionTracePendingFileName("inst-abc")
        )
    }

    @Test
    fun sessionTraceUnattributedFileName_isSharedOrphanSink() {
        assertEquals(
            "session-traces/_unattributed/orphan.jsonl",
            AppDataFiles.sessionTraceUnattributedFileName()
        )
    }
}
