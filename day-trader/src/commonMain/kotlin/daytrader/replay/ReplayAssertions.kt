package daytrader.replay

import daytrader.domain.StrategyDeployment
import daytrader.domain.TouchTurnSessionOutcome
import daytrader.domain.resolveTouchTurnSessionOutcome

data class ReplayComparison(
    val expectedOutcome: TouchTurnSessionOutcome,
    val actualOutcome: TouchTurnSessionOutcome?,
    val outcomeMatches: Boolean,
    val expectedBracketSubmitted: Boolean,
    val actualBracketSubmitted: Boolean,
    val bracketSubmittedMatches: Boolean
) {
    val passed: Boolean
        get() = outcomeMatches && bracketSubmittedMatches
}

object ReplayAssertions {
    fun compare(deployment: StrategyDeployment, bundle: SessionBundle): ReplayComparison {
        val expected = bundle.groundTruth?.runRecord?.decision?.outcome
            ?: error("Replay bundle missing ground-truth decision outcome")
        val actual = resolveActualOutcome(deployment)
        val expectedBracket = expected == TouchTurnSessionOutcome.TRADE_BRACKET_SUBMITTED
        val actualBracket = deployment.touchTurnSession?.ordersPlacedForSession == true ||
            deployment.sessionHistory.lastOrNull()?.ordersPlacedForCandle == true ||
            actual == TouchTurnSessionOutcome.TRADE_BRACKET_SUBMITTED
        return ReplayComparison(
            expectedOutcome = expected,
            actualOutcome = actual,
            outcomeMatches = expected == actual,
            expectedBracketSubmitted = expectedBracket,
            actualBracketSubmitted = actualBracket,
            bracketSubmittedMatches = expectedBracket == actualBracket
        )
    }

    fun assertMatches(deployment: StrategyDeployment, bundle: SessionBundle): ReplayComparison {
        val comparison = compare(deployment, bundle)
        require(comparison.passed) {
            "Replay outcome mismatch: expected=${comparison.expectedOutcome} actual=${comparison.actualOutcome} " +
                "expectedBracket=${comparison.expectedBracketSubmitted} actualBracket=${comparison.actualBracketSubmitted}"
        }
        return comparison
    }

    private fun resolveActualOutcome(deployment: StrategyDeployment): TouchTurnSessionOutcome? {
        deployment.touchTurnSession?.decisionOutcome?.let { return it }
        deployment.touchTurnSession?.let { return resolveTouchTurnSessionOutcome(it) }
        return deployment.sessionHistory.lastOrNull()?.touchTurnRunRecord?.decision?.outcome
    }
}
