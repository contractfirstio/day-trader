package daytrader.replay

import daytrader.domain.StrategyType
import daytrader.domain.TouchTurnRuleConfig
import daytrader.domain.TouchTurnRuleEnables
import daytrader.domain.TouchTurnSessionStopLogic
import daytrader.domain.beginTouchTurnSession
import daytrader.domain.defaultStrategyDeployment
import daytrader.domain.onSessionStarted
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ReplayQuoteStopSyncTest {
    @Test
    fun quoteTimelineEnded_whenNoRemainingQuotes_isTrue() {
        assertTrue(ReplayQuoteStopSync.quoteTimelineEnded(nextQuoteEpochMs = null, deadlineEpochMs = null))
        assertTrue(ReplayQuoteStopSync.quoteTimelineEnded(nextQuoteEpochMs = null, deadlineEpochMs = 1_000L))
    }

    @Test
    fun quoteTimelineEnded_whenNextQuotePastDeadline_isTrue() {
        assertTrue(ReplayQuoteStopSync.quoteTimelineEnded(nextQuoteEpochMs = 2_000L, deadlineEpochMs = 1_000L))
        assertTrue(!ReplayQuoteStopSync.quoteTimelineEnded(nextQuoteEpochMs = 500L, deadlineEpochMs = 1_000L))
    }

    @Test
    fun openDeadlineEpochMs_isRthOpenPlusStopAfterOpenMinutes() {
        val deployment = defaultStrategyDeployment(
            strategyType = StrategyType.TOUCH_AND_TURN_SCALPER,
            symbol = "1810",
            maxDollars = 500,
            marketZoneId = "Asia/Hong_Kong"
        ).copy(
            touchTurnRules = TouchTurnRuleConfig.DEFAULT.copy(
                stopAfterOpenMinutes = 90,
                enables = TouchTurnRuleEnables.DEFAULT.copy(openDeadline = true)
            )
        )
        val sessionDate = "2026-06-10"
        val withSession = deployment.onSessionStarted(sessionDate).beginTouchTurnSession(sessionDate)
        val open = TouchTurnSessionStopLogic.sessionOpenEpochMillis(withSession, sessionDate)!!
        val expected = open + 90 * 60_000L
        assertEquals(expected, ReplayQuoteStopSync.openDeadlineEpochMs(withSession, sessionDate))
    }

    @Test
    fun openDeadlineEpochMs_nullWhenRuleDisabled() {
        val deployment = defaultStrategyDeployment(
            strategyType = StrategyType.TOUCH_AND_TURN_SCALPER,
            symbol = "1810",
            maxDollars = 500,
            marketZoneId = "Asia/Hong_Kong"
        )
        assertNull(ReplayQuoteStopSync.openDeadlineEpochMs(deployment, "2026-06-10"))
    }
}
