package daytrader.presentation.strategies

import daytrader.gateway.WorkingOrder
import daytrader.domain.ActiveExecution
import daytrader.domain.ExecutionState
import daytrader.domain.InstanceStatus
import daytrader.domain.RunStatus
import daytrader.domain.StrategyInstance
import daytrader.domain.StrategyRun
import daytrader.domain.StrategyType
import daytrader.domain.TradeSide
import kotlin.test.Test
import kotlin.test.assertEquals

class InstanceCardStateMapperTest {
    private val sessionDate = "2026-05-22"

    @Test
    fun running_flat_whenNoOpenPosition() {
        val instance = instance(status = InstanceStatus.RUNNING, live = ActiveExecution.flat())
        val card = InstanceCardStateMapper.resolve(instance, sessionDate)
        assertEquals(InstanceCardAccent.RUNNING_FLAT, card.accent)
        assertEquals("Active", card.chipLabel)
    }

    @Test
    fun running_inTheMoney_whenFilledAndPositiveUnrealized() {
        val instance = instance(
            status = InstanceStatus.RUNNING,
            live = ActiveExecution(
                state = ExecutionState.FILLED,
                side = TradeSide.LONG,
                quantity = 10,
                entryPrice = 100.0,
                marketPrice = 105.0
            )
        )
        val card = InstanceCardStateMapper.resolve(instance, sessionDate, brokerUnrealizedPnL = 42.0)
        assertEquals(InstanceCardAccent.RUNNING_IN_THE_MONEY, card.accent)
        assertEquals("In the money", card.chipLabel)
    }

    @Test
    fun running_outOfTheMoney_whenFilledAndNegativeUnrealized() {
        val instance = instance(
            status = InstanceStatus.RUNNING,
            live = ActiveExecution(
                state = ExecutionState.FILLED,
                side = TradeSide.LONG,
                quantity = 10,
                entryPrice = 100.0,
                marketPrice = 95.0
            )
        )
        val card = InstanceCardStateMapper.resolve(instance, sessionDate, brokerUnrealizedPnL = -25.0)
        assertEquals(InstanceCardAccent.RUNNING_OUT_OF_THE_MONEY, card.accent)
        assertEquals("Out of the money", card.chipLabel)
    }

    @Test
    fun stopped_win_whenTodaysClosedRunIsPositive() {
        val instance = instance(
            status = InstanceStatus.STOPPED,
            performance = listOf(
                StrategyRun(
                    id = "r1",
                    date = sessionDate,
                    pnl = 120.0,
                    trades = 1,
                    maxAtRisk = 1000,
                    status = RunStatus.CLOSED,
                    stoppedAt = "2026-05-22T16:00:00"
                )
            )
        )
        val card = InstanceCardStateMapper.resolve(instance, sessionDate)
        assertEquals(InstanceCardAccent.STOPPED_WIN, card.accent)
        assertEquals("Win", card.chipLabel)
    }

    @Test
    fun stopped_loss_whenTodaysClosedRunIsNegative() {
        val instance = instance(
            status = InstanceStatus.STOPPED,
            performance = listOf(
                StrategyRun(
                    id = "r1",
                    date = sessionDate,
                    pnl = -50.0,
                    trades = 1,
                    maxAtRisk = 1000,
                    status = RunStatus.CLOSED
                )
            )
        )
        val card = InstanceCardStateMapper.resolve(instance, sessionDate)
        assertEquals(InstanceCardAccent.STOPPED_LOSS, card.accent)
        assertEquals("Loss", card.chipLabel)
    }

    @Test
    fun openOrders_brownPulse_whenBrokerHasMatchingOrders() {
        val instance = instance(status = InstanceStatus.RUNNING, live = ActiveExecution.flat())
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
        val card = InstanceCardStateMapper.resolve(instance, sessionDate, brokerOpenOrders = orders)
        assertEquals(InstanceCardAccent.OPEN_ORDERS, card.accent)
        assertEquals("Open order", card.chipLabel)
    }

    @Test
    fun openOrders_overridesInTheMoney_whenFilledAndOrdersWorking() {
        val instance = instance(
            status = InstanceStatus.RUNNING,
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
        val card = InstanceCardStateMapper.resolve(
            instance,
            sessionDate,
            brokerUnrealizedPnL = 42.0,
            brokerOpenOrders = orders
        )
        assertEquals(InstanceCardAccent.OPEN_ORDERS, card.accent)
        assertEquals("2 open orders", card.chipLabel)
    }

    @Test
    fun running_flat_whenOpenOrdersAreForAnotherSymbol() {
        val instance = instance(status = InstanceStatus.RUNNING, live = ActiveExecution.flat())
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
        val card = InstanceCardStateMapper.resolve(instance, sessionDate, brokerOpenOrders = orders)
        assertEquals(InstanceCardAccent.RUNNING_FLAT, card.accent)
    }

    @Test
    fun stopped_neutral_whenTodaysClosedRunIsBreakeven() {
        val instance = instance(
            status = InstanceStatus.STOPPED,
            performance = listOf(
                StrategyRun(
                    id = "r1",
                    date = sessionDate,
                    pnl = 0.0,
                    trades = 0,
                    maxAtRisk = 1000,
                    status = RunStatus.CLOSED
                )
            )
        )
        val card = InstanceCardStateMapper.resolve(instance, sessionDate)
        assertEquals(InstanceCardAccent.STOPPED_NEUTRAL, card.accent)
        assertEquals("Neutral", card.chipLabel)
    }

    private fun instance(
        status: InstanceStatus,
        live: ActiveExecution = ActiveExecution.flat(),
        performance: List<StrategyRun> = emptyList()
    ) = StrategyInstance(
        id = "inst-1",
        strategyType = StrategyType.TOUCH_AND_TURN_SCALPER,
        status = status,
        symbol = "TSLA",
        maxDollars = 1000,
        live = live,
        performance = performance
    )
}
