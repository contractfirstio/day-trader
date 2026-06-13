package daytrader.replay

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SessionReplaySearchTest {

    @Test
    fun wildcardMatches_plainText_matchesSubstringCaseInsensitively() {
        assertTrue(SessionReplaySearch.wildcardMatches("meta", "META"))
        assertTrue(SessionReplaySearch.wildcardMatches("meta", "Meta Platforms Inc."))
        assertFalse(SessionReplaySearch.wildcardMatches("meta", "Apple Inc."))
    }

    @Test
    fun wildcardMatches_asteriskMatchesAnyCharacters() {
        assertTrue(SessionReplaySearch.wildcardMatches("M*A", "META"))
        assertTrue(SessionReplaySearch.wildcardMatches("*platform*", "Meta Platforms Inc."))
        assertTrue(SessionReplaySearch.wildcardMatches("A*L", "AAPL"))
    }

    @Test
    fun wildcardMatches_questionMarkMatchesSingleCharacter() {
        assertTrue(SessionReplaySearch.wildcardMatches("M?TA", "META"))
        assertFalse(SessionReplaySearch.wildcardMatches("M?TA", "MTA"))
    }

    @Test
    fun matches_searchesSymbolAndCompanyName() {
        assertTrue(
            SessionReplaySearch.matches(
                query = "meta",
                symbol = "META",
                companyName = "Meta Platforms Inc.",
                deploymentId = "dep-1",
                sessionId = "sess-1",
                label = "META · 2026-06-04"
            )
        )
        assertTrue(
            SessionReplaySearch.matches(
                query = "platform",
                symbol = "META",
                companyName = "Meta Platforms Inc.",
                deploymentId = "dep-1",
                sessionId = "sess-1",
                label = "META · 2026-06-04"
            )
        )
        assertFalse(
            SessionReplaySearch.matches(
                query = "apple",
                symbol = "META",
                companyName = "Meta Platforms Inc.",
                deploymentId = "dep-1",
                sessionId = "sess-1",
                label = "META · 2026-06-04"
            )
        )
    }
}
