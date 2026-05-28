package daytrader.presentation.strategies

import daytrader.data.persistence.AppDataFiles
import kotlin.test.Test
import kotlin.test.assertEquals

class SessionLogUiTest {
    @Test
    fun logFolderRelativePath_matchesSessionDirectory() {
        assertEquals(
            "sessions/deployment-1/session-abc/",
            SessionLogUi.logFolderRelativePath("deployment-1", "session-abc")
        )
        assertEquals(
            AppDataFiles.sessionDirectory("deployment-1", "session-abc") + "/",
            SessionLogUi.logFolderRelativePath("deployment-1", "session-abc")
        )
    }

    @Test
    fun diagnosisPromptText_matchesSessionAnalysisTemplate() {
        val prompt = SessionLogUi.diagnosisPromptText(
            sessionId = "session-abc",
            broker = "emulator",
            applicationLogPath = "/Users/me/Library/Application Support/Day Trader/emulator/sessions/d1/session-abc/application.jsonl",
        )
        assertEquals(
            """
            Day Trader diagnosis — Session ID: session-abc
            Broker: emulator
            Symptom: {one sentence}

            Follow the Day Trader log diagnosis workflow: find /Users/me/Library/Application Support/Day Trader/emulator/sessions/d1/session-abc/application.jsonl, correlate emulator/emulator/*.jsonl by epochMs+symbol if hybrid/emulator, then explain with code. Cite log lines and epochMs.
            """.trimIndent(),
            prompt,
        )
    }
}
