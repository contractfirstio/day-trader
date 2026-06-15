package daytrader.presentation.strategies

import daytrader.gateway.WorkingOrder
import daytrader.domain.ActiveExecution
import daytrader.domain.ExecutionState
import daytrader.domain.DeploymentStatus
import daytrader.domain.SessionStatus
import daytrader.domain.StrategyDeployment
import daytrader.domain.StrategySession
import daytrader.domain.StrategyType
import daytrader.domain.TradeSide
import kotlin.test.Test
import kotlin.test.assertEquals

class DeploymentCardStateMapperTest {
    private val sessionDate = "2026-05-22"

    @Test
    fun running_flat_whenNoOpenPosition() {
        val instance = deployment(status = DeploymentStatus.RUNNING, live = ActiveExecution.flat())
        val card = DeploymentCardStateMapper.resolve(instance, sessionDate)
        assertEquals(DeploymentCardAccent.RUNNING_FLAT, card.accent)
        assertEquals("Active", card.chipLabel)
    }

    @Test
    fun running_inTheMoney_whenFilledAndPositiveUnrealized() {
        val instance = deployment(
            status = DeploymentStatus.RUNNING,
            live = ActiveExecution(
                state = ExecutionState.FILLED,
                side = TradeSide.LONG,
                quantity = 10,
                entryPrice = 100.0,
                marketPrice = 105.0
            )
        )
        val card = DeploymentCardStateMapper.resolve(instance, sessionDate, brokerUnrealizedPnL = 42.0)
        assertEquals(DeploymentCardAccent.RUNNING_IN_THE_MONEY, card.accent)
        assertEquals("In the money", card.chipLabel)
    }

    @Test
    fun running_outOfTheMoney_whenFilledAndNegativeUnrealized() {
        val instance = deployment(
            status = DeploymentStatus.RUNNING,
            live = ActiveExecution(
                state = ExecutionState.FILLED,
                side = TradeSide.LONG,
                quantity = 10,
                entryPrice = 100.0,
                marketPrice = 95.0
            )
        )
        val card = DeploymentCardStateMapper.resolve(instance, sessionDate, brokerUnrealizedPnL = -25.0)
        assertEquals(DeploymentCardAccent.RUNNING_OUT_OF_THE_MONEY, card.accent)
        assertEquals("Out of the money", card.chipLabel)
    }

    @Test
    fun stopped_win_whenTodaysClosedRunIsPositive() {
        val instance = deployment(
            status = DeploymentStatus.STOPPED,
            sessionHistory = listOf(
                StrategySession(
                    id = "r1",
                    date = sessionDate,
                    pnl = 120.0,
                    trades = 1,
                    maxAtRisk = 1000,
                    status = SessionStatus.CLOSED,
                    stoppedAt = "2026-05-22T16:00:00"
                )
            )
        )
        val card = DeploymentCardStateMapper.resolve(instance, sessionDate)
        assertEquals(DeploymentCardAccent.STOPPED_WIN, card.accent)
        assertEquals("Win", card.chipLabel)
    }

    @Test
    fun stopped_loss_whenTodaysClosedRunIsNegative() {
        val instance = deployment(
            status = DeploymentStatus.STOPPED,
            sessionHistory = listOf(
                StrategySession(
                    id = "r1",
                    date = sessionDate,
                    pnl = -50.0,
                    trades = 1,
                    maxAtRisk = 1000,
                    status = SessionStatus.CLOSED
                )
            )
        )
        val card = DeploymentCardStateMapper.resolve(instance, sessionDate)
        assertEquals(DeploymentCardAccent.STOPPED_LOSS, card.accent)
        assertEquals("Loss", card.chipLabel)
    }

    @Test
    fun openOrders_brownPulse_whenBrokerHasMatchingOrders() {
        val instance = deployment(status = DeploymentStatus.RUNNING, live = ActiveExecution.flat())
        val orders = listOf(
            WorkingOrder(
                orderId = 1,
                symbol = "TSLA",
                action = "BUY",
                quantity = 10,
                filled = 0,
                remaining = 10,
                orderType = "LMT",
                limitPrice = 250.0,
                stopPrice = null,
                status = "Submitted",
                currency = "USD"
            )
        )
        val card = DeploymentCardStateMapper.resolve(instance, sessionDate, brokerOpenOrders = orders)
        assertEquals(DeploymentCardAccent.OPEN_ORDERS, card.accent)
        assertEquals("Open order", card.chipLabel)
    }

    @Test
    fun openPosition_overridesOpenOrders_whenFilledAndBracketWorking() {
        val instance = deployment(
            status = DeploymentStatus.RUNNING,
            live = ActiveExecution(
                state = ExecutionState.FILLED,
                side = TradeSide.LONG,
                quantity = 10,
                entryPrice = 100.0,
                marketPrice = 105.0
            )
        )
        val orders = listOf(
            WorkingOrder(
                orderId = 1,
                symbol = "TSLA",
                action = "SELL",
                quantity = 10,
                filled = 0,
                remaining = 10,
                orderType = "LMT",
                limitPrice = 110.0,
                stopPrice = null,
                status = "Submitted",
                currency = "USD"
            ),
            WorkingOrder(
                orderId = 2,
                symbol = "TSLA",
                action = "SELL",
                quantity = 10,
                filled = 0,
                remaining = 10,
                orderType = "STP",
                limitPrice = null,
                stopPrice = 95.0,
                status = "Submitted",
                currency = "USD"
            )
        )
        val card = DeploymentCardStateMapper.resolve(
            instance,
            sessionDate,
            brokerUnrealizedPnL = 42.0,
            brokerOpenOrders = orders,
            hasOpenPosition = true
        )
        assertEquals(DeploymentCardAccent.RUNNING_IN_THE_MONEY, card.accent)
        assertEquals("In the money", card.chipLabel)
    }

    @Test
    fun stopped_inTheMoney_whenBrokerHasOpenPosition() {
        val instance = deployment(status = DeploymentStatus.STOPPED, live = ActiveExecution.flat())
        val card = DeploymentCardStateMapper.resolve(
            instance,
            sessionDate,
            brokerUnrealizedPnL = 15.0,
            hasOpenPosition = true
        )
        assertEquals(DeploymentCardAccent.RUNNING_IN_THE_MONEY, card.accent)
        assertEquals("In the money", card.chipLabel)
    }

    @Test
    fun running_inTheMoney_whenBrokerHasPositionButLiveIsFlat() {
        val instance = deployment(status = DeploymentStatus.RUNNING, live = ActiveExecution.flat())
        val card = DeploymentCardStateMapper.resolve(
            instance,
            sessionDate,
            brokerUnrealizedPnL = 10.0,
            hasOpenPosition = true
        )
        assertEquals(DeploymentCardAccent.RUNNING_IN_THE_MONEY, card.accent)
        assertEquals("In the money", card.chipLabel)
    }

    @Test
    fun running_flat_whenOpenOrdersAreForAnotherSymbol() {
        val instance = deployment(status = DeploymentStatus.RUNNING, live = ActiveExecution.flat())
        val orders = listOf(
            WorkingOrder(
                orderId = 1,
                symbol = "AAPL",
                action = "BUY",
                quantity = 5,
                filled = 0,
                remaining = 5,
                orderType = "LMT",
                limitPrice = 180.0,
                stopPrice = null,
                status = "Submitted",
                currency = "USD"
            )
        )
        val card = DeploymentCardStateMapper.resolve(instance, sessionDate, brokerOpenOrders = orders)
        assertEquals(DeploymentCardAccent.RUNNING_FLAT, card.accent)
    }

    @Test
    fun stopped_neutral_whenTodaysClosedRunIsBreakeven() {
        val instance = deployment(
            status = DeploymentStatus.STOPPED,
            sessionHistory = listOf(
                StrategySession(
                    id = "r1",
                    date = sessionDate,
                    pnl = 0.0,
                    trades = 0,
                    maxAtRisk = 1000,
                    status = SessionStatus.CLOSED
                )
            )
        )
        val card = DeploymentCardStateMapper.resolve(instance, sessionDate)
        assertEquals(DeploymentCardAccent.STOPPED_NEUTRAL, card.accent)
        assertEquals("Neutral", card.chipLabel)
    }

    private fun deployment(
        status: DeploymentStatus,
        live: ActiveExecution = ActiveExecution.flat(),
        sessionHistory: List<StrategySession> = emptyList()
    ) = StrategyDeployment(
        id = "deploy-1",
        strategyType = StrategyType.TOUCH_AND_TURN_SCALPER,
        status = status,
        symbol = "TSLA",
        maxDollars = 1000,
        live = live,
        sessionHistory = sessionHistory
    )
}
