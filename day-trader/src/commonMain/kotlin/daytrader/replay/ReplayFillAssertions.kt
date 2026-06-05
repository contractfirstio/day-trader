package daytrader.replay

import daytrader.domain.SessionTrade
import daytrader.domain.StrategyDeployment
import daytrader.domain.dedupeByExecId
import daytrader.domain.inProgressSession
import daytrader.domain.roundTripCount

data class ReplayFillComparison(
    val expectedFillCount: Int,
    val actualFillCount: Int,
    val fillCountMatches: Boolean,
    val expectedRoundTrips: Int,
    val actualRoundTrips: Int,
    val roundTripsMatch: Boolean
) {
    val passed: Boolean
        get() = fillCountMatches && roundTripsMatch
}

object ReplayFillAssertions {
    fun compare(deployment: StrategyDeployment, bundle: SessionBundle): ReplayFillComparison {
        val expected = bundle.groundTruth?.dedupedFills.orEmpty()
        val actual = actualSessionTrades(deployment)
        return ReplayFillComparison(
            expectedFillCount = expected.size,
            actualFillCount = actual.size,
            fillCountMatches = expected.size == actual.size,
            expectedRoundTrips = expected.roundTripCount(),
            actualRoundTrips = actual.roundTripCount(),
            roundTripsMatch = expected.roundTripCount() == actual.roundTripCount()
        )
    }

    private fun actualSessionTrades(deployment: StrategyDeployment): List<SessionTrade> {
        deployment.inProgressSession()?.sessionTrades?.dedupeByExecId()?.let { return it }
        return deployment.sessionHistory.lastOrNull()?.sessionTrades?.dedupeByExecId().orEmpty()
    }
}
