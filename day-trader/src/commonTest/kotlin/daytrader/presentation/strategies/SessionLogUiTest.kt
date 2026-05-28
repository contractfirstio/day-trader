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
}
