package daytrader.engine.touchturn

import daytrader.domain.DeploymentStatus
import daytrader.domain.StrategyType
import daytrader.domain.TouchTurnCandleStatus
import daytrader.domain.TouchTurnSessionContext
import daytrader.domain.TouchTurnSessionStopTrigger
import daytrader.domain.defaultStrategyDeployment
import daytrader.domain.onSessionStarted
import daytrader.engine.BrokerSnapshotSource
import daytrader.engine.TouchTurnCommand
import daytrader.engine.TouchTurnEvent
import daytrader.engine.support.FakeBrokerGateway
import daytrader.engine.support.InMemoryStrategyDeploymentRepository
import daytrader.engine.support.testTouchTurnEngine
import daytrader.gateway.AccountPosition
import daytrader.gateway.BrokerFill
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.runBlocking

class TouchTurnBrokerAutoStopTest {
    @Test
    fun outOfOrderBrokerSnapshots_stopAfterTradeOutcomeWhenMergedStateIsFlat() = runBlocking {
        val repo = InMemoryStrategyDeploymentRepository()
        val gateway = FakeBrokerGateway()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val deployment = defaultStrategyDeployment(
            strategyType = StrategyType.TOUCH_AND_TURN_SCALPER,
            symbol = "0700",
            maxDollars = 500,
            status = DeploymentStatus.RUNNING
        ).onSessionStarted("2026-06-19", startedAt = "2026-06-19T10:00:00")
            .copy(
                touchTurnSession = TouchTurnSessionContext(
                    sessionDate = "2026-06-19",
                    status = TouchTurnCandleStatus.READY,
                    ordersPlacedForSession = true
                )
            )
        repo.add(deployment)

        val engine = testTouchTurnEngine(gateway, repo, scope)
        val events = mutableListOf<TouchTurnEvent>()
        engine.events.onEach { events += it }.launchIn(scope)
        engine.start()

        val entryFill = sampleFill(execId = "emu-1003-0", parentOrderId = 0)
        val exitFill = sampleFill(execId = "emu-1004-1", parentOrderId = 1003)
        val shortPosition = samplePosition(quantity = -10)
        val flatPosition = samplePosition(quantity = 0)

        engine.dispatch(
            TouchTurnCommand.BrokerSnapshot(
                source = BrokerSnapshotSource.OPEN_ORDERS,
                positions = listOf(shortPosition),
                openOrders = emptyList(),
                fills = listOf(entryFill)
            )
        )
        engine.dispatch(
            TouchTurnCommand.BrokerSnapshot(
                source = BrokerSnapshotSource.FILLS,
                positions = listOf(shortPosition),
                openOrders = emptyList(),
                fills = listOf(entryFill, exitFill)
            )
        )
        engine.dispatch(
            TouchTurnCommand.BrokerSnapshot(
                source = BrokerSnapshotSource.POSITIONS,
                positions = listOf(flatPosition),
                openOrders = emptyList(),
                fills = listOf(entryFill)
            )
        )
        delay(100)

        val stopped = events.filterIsInstance<TouchTurnEvent.SessionStopped>().singleOrNull()
        assertTrue(stopped != null, "expected SessionStopped after merged broker state is flat")
        assertEquals(deployment.id, stopped.instanceId)
        assertEquals(TouchTurnSessionStopTrigger.TRADE_OUTCOME_KNOWN, stopped.trigger)
        assertEquals(DeploymentStatus.STOPPED, repo.deployments.value.single().status)
    }

    private fun samplePosition(quantity: Int) = AccountPosition(
        account = "EMU",
        symbol = "0700",
        companyName = "Tencent",
        quantity = quantity,
        avgPrice = 100.0,
        marketPrice = 100.0,
        priorClose = 99.0,
        totalUnrealizedPnL = 0.0,
        currency = "HKD"
    )

    private fun sampleFill(execId: String, parentOrderId: Int) = BrokerFill(
        execId = execId,
        orderId = if (parentOrderId == 0) 1003 else 1004,
        permId = 1L,
        parentOrderId = parentOrderId,
        symbol = "0700",
        side = if (parentOrderId == 0) "SLD" else "BOT",
        quantity = 10,
        price = 100.0,
        time = "2026-06-19T10:05:00Z",
        currency = "HKD"
    )
}
