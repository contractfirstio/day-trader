package daytrader.data

import daytrader.domain.DeploymentStatus
import daytrader.domain.StrategyDeployment
import daytrader.domain.StrategySession
import daytrader.domain.StrategyType
import daytrader.domain.TouchTurnCandleStatus
import daytrader.domain.TouchTurnMilestoneTimestamps
import daytrader.domain.TouchTurnRuleConfig
import daytrader.domain.TouchTurnSessionContext
import daytrader.domain.SessionStatus
import daytrader.gateway.AccountPosition
import daytrader.gateway.BrokerFill
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class OpenDeadlinePositionResolverTest {
    @Test
    fun resolve_prefersDecisionSnapshotOverLiveCache() {
        val deployment = deploymentWithPositionMilestone()
        val snapshot = shortPosition(quantity = -50)
        val live = shortPosition(quantity = -100)

        val resolved = OpenDeadlinePositionResolver.resolve(
            instance = deployment,
            brokerPositionAtDecision = snapshot,
            livePositions = listOf(live),
            fills = emptyList()
        )

        assertEquals(OpenDeadlinePositionResolver.PositionSource.DECISION_SNAPSHOT, resolved.source)
        assertEquals(-50, resolved.position?.quantity)
    }

    @Test
    fun resolve_infersShortPositionFromSessionFillsWhenCachesEmpty() {
        val deployment = deploymentWithPositionMilestone()
        val fills = listOf(
            fill(side = "SLD", quantity = 100, time = "2026-06-04T09:46:00")
        )

        val resolved = OpenDeadlinePositionResolver.resolve(
            instance = deployment,
            brokerPositionAtDecision = null,
            livePositions = emptyList(),
            fills = fills
        )

        assertEquals(OpenDeadlinePositionResolver.PositionSource.INFERRED_FROM_FILLS, resolved.source)
        assertEquals(-100, resolved.position?.quantity)
    }

    @Test
    fun resolve_infersLongPositionFromBotFills() {
        val deployment = deploymentWithPositionMilestone()
        val fills = listOf(
            fill(side = "BOT", quantity = 25, time = "2026-06-04T09:46:00")
        )

        val resolved = OpenDeadlinePositionResolver.resolve(
            instance = deployment,
            brokerPositionAtDecision = null,
            livePositions = emptyList(),
            fills = fills
        )

        assertEquals(25, resolved.position?.quantity)
    }

    @Test
    fun resolve_returnsNoneWhenMilestoneSetButNetFillQtyZero() {
        val deployment = deploymentWithPositionMilestone()
        val fills = listOf(
            fill(side = "SLD", quantity = 100, time = "2026-06-04T09:46:00"),
            fill(side = "BOT", quantity = 100, time = "2026-06-04T10:00:00", parentOrderId = 1, orderId = 2)
        )

        val resolved = OpenDeadlinePositionResolver.resolve(
            instance = deployment,
            brokerPositionAtDecision = null,
            livePositions = emptyList(),
            fills = fills
        )

        assertEquals(OpenDeadlinePositionResolver.PositionSource.NONE, resolved.source)
        assertNull(resolved.position)
    }

    @Test
    fun resolve_returnsNoneWithoutMilestoneWhenCachesEmpty() {
        val deployment = deploymentWithPositionMilestone().copy(
            touchTurnSession = deploymentWithPositionMilestone().touchTurnSession?.copy(
                milestones = TouchTurnMilestoneTimestamps()
            )
        )
        val fills = listOf(fill(side = "SLD", quantity = 100, time = "2026-06-04T09:46:00"))

        val resolved = OpenDeadlinePositionResolver.resolve(
            instance = deployment,
            brokerPositionAtDecision = null,
            livePositions = emptyList(),
            fills = fills
        )

        assertEquals(OpenDeadlinePositionResolver.PositionSource.NONE, resolved.source)
        assertNull(resolved.position)
    }

    private fun deploymentWithPositionMilestone(): StrategyDeployment =
        StrategyDeployment(
            id = "dep-1",
            strategyType = StrategyType.TOUCH_AND_TURN_SCALPER,
            status = DeploymentStatus.RUNNING,
            symbol = "AAPL",
            maxDollars = 500,
            sessionHistory = listOf(
                StrategySession(
                    id = "session-1",
                    date = "2026-06-04",
                    startedAt = "2026-06-04T09:31:00",
                    pnl = 0.0,
                    trades = 0,
                    maxAtRisk = 500,
                    status = SessionStatus.IN_PROGRESS
                )
            ),
            touchTurnSession = TouchTurnSessionContext(
                sessionDate = "2026-06-04",
                status = TouchTurnCandleStatus.READY,
                rules = TouchTurnRuleConfig.DEFAULT,
                milestones = TouchTurnMilestoneTimestamps(
                    startingSessionAt = "2026-06-04T09:31:00",
                    ordersPlacedAt = "2026-06-04T09:45:00",
                    positionOpenedAt = "2026-06-04T09:46:00"
                )
            )
        )

    private fun shortPosition(quantity: Int = -100) = AccountPosition(
        account = "DU123",
        symbol = "AAPL",
        companyName = "Apple",
        quantity = quantity,
        avgPrice = 150.0,
        marketPrice = 149.0,
        priorClose = 148.0,
        totalUnrealizedPnL = 100.0,
        currency = "USD"
    )

    private fun fill(
        side: String,
        quantity: Int,
        time: String,
        parentOrderId: Int = 0,
        orderId: Int = 1
    ) = BrokerFill(
        execId = "$orderId-$side",
        orderId = orderId,
        permId = orderId.toLong(),
        parentOrderId = parentOrderId,
        symbol = "AAPL",
        side = side,
        quantity = quantity,
        price = 150.0,
        time = time,
        currency = "USD"
    )
}
