package daytrader.e2e

import daytrader.domain.TouchTurnSessionOutcome
import daytrader.domain.withClosedFirstFifteenMinuteCandle
import daytrader.domain.withFirstFifteenMinuteCandle
import daytrader.domain.withLiquidityEvaluatedIfClosed
import daytrader.domain.withOpeningBarClosedMilestone
import daytrader.e2e.support.E2ETestFixtures
import daytrader.engine.support.InMemoryStrategyDeploymentRepository
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

class E2EDomainSmokeTest {
    @Test
    fun closedNonLiquidityBar_evaluatesToNoTradeNotLiquidity() {
        val repository = InMemoryStrategyDeploymentRepository()
        repository.add(E2ETestFixtures.runningDeployment())
        val bar = E2ETestFixtures.nonLiquidityOpeningBar()
        repository.update(E2ETestFixtures.DEPLOYMENT_ID) { current ->
            current
                .withFirstFifteenMinuteCandle(
                    sessionDate = E2ETestFixtures.SESSION_DATE,
                    candle = bar,
                    atr14 = E2ETestFixtures.ATR14,
                    volumeSma20 = E2ETestFixtures.VOLUME_SMA20
                )
                .withOpeningBarClosedMilestone()
                .withClosedFirstFifteenMinuteCandle(bar)
                .withLiquidityEvaluatedIfClosed(
                    enforceCloseConfirmation = false,
                    nowEpochMillis = E2ETestFixtures.BAR_CLOSE_EPOCH_MS
                )
        }
        val session = repository.deployments.value.first().touchTurnSession
        assertNotNull(session)
        assertEquals(TouchTurnSessionOutcome.NO_TRADE_NOT_LIQUIDITY, session.decisionOutcome)
    }
}
