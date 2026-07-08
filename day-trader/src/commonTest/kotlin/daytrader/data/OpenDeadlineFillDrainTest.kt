package daytrader.data

import daytrader.domain.DeploymentStatus
import daytrader.domain.StrategyDeployment
import daytrader.domain.StrategySession
import daytrader.domain.StrategyType
import daytrader.domain.SessionStatus
import daytrader.domain.TouchTurnCandleStatus
import daytrader.domain.TouchTurnMilestoneTimestamps
import daytrader.domain.TouchTurnRuleConfig
import daytrader.domain.TouchTurnSessionContext
import daytrader.engine.support.FakeBrokerGateway
import daytrader.gateway.BrokerFill
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking

class OpenDeadlineFillDrainTest {
    @Test
    fun awaitClosingFill_waitsForExitFillAfterMarketClose() = runBlocking {
        val gateway = FakeBrokerGateway()
        gateway.synthesizeExitFillOnClose = true
        val deployment = deploymentWithSession()
        val entryFill = BrokerFill(
            execId = "entry-1",
            orderId = 521,
            permId = 521L,
            parentOrderId = 0,
            symbol = "AAPL",
            side = "SLD",
            quantity = 100,
            price = 150.0,
            time = "2026-06-04T09:46:00",
            currency = "USD"
        )
        gateway.setFills(listOf(entryFill))

        val seenBefore = OpenDeadlineFillDrain.sessionFillExecIds(deployment, gateway.fills.value)
        gateway.closeOpenPositionForSymbol(
            symbol = "AAPL",
            position = daytrader.gateway.AccountPosition(
                account = "DU123",
                symbol = "AAPL",
                companyName = "Apple",
                quantity = -100,
                avgPrice = 150.0,
                marketPrice = 149.0,
                priorClose = 148.0,
                totalUnrealizedPnL = 100.0,
                currency = "USD"
            )
        )

        val drained = OpenDeadlineFillDrain.awaitClosingFill(
            gateway = gateway,
            instance = deployment,
            fillsSeenBefore = seenBefore,
            timeoutMs = 1_000,
            pollIntervalMs = 25
        )

        assertEquals(2, drained.size)
        assertTrue(drained.any { it.execId.startsWith("exit-") })
    }

    private fun deploymentWithSession(): StrategyDeployment =
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
                milestones = TouchTurnMilestoneTimestamps(positionOpenedAt = "2026-06-04T09:46:00")
            )
        )
}
