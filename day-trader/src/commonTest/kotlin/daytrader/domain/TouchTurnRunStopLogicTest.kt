package daytrader.domain

import daytrader.data.StrategyCatalog
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TouchTurnRunStopLogicTest {
    @Test
    fun stopAfterMinOpen_isTouchTurnOnly() {
        assertEquals(90, StrategyCatalog.stopAfterMinOpen(StrategyType.TOUCH_AND_TURN_SCALPER))
        assertNull(StrategyCatalog.stopAfterMinOpen(StrategyType.QUICK_FLIP_SCALPER))
    }

    @Test
    fun evaluateOpenDeadline_continuesBeforeDeadline() {
        val instance = touchTurnRunningInstance()
        val open = TouchTurnRunStopLogic.sessionOpenEpochMillis(instance, "2025-05-22")!!
        assertEquals(
            InstanceRunStopAction.CONTINUE,
            TouchTurnRunStopLogic.evaluateOpenDeadline(
                instance = instance,
                nowEpochMillis = open + 30 * 60_000
            )
        )
    }

    @Test
    fun evaluateOpenDeadline_stopsAfterDeadline() {
        val instance = touchTurnRunningInstance()
        val open = TouchTurnRunStopLogic.sessionOpenEpochMillis(instance, "2025-05-22")!!
        assertEquals(
            InstanceRunStopAction.STOP_AFTER_OPEN_DEADLINE,
            TouchTurnRunStopLogic.evaluateOpenDeadline(
                instance = instance,
                nowEpochMillis = open + 90 * 60_000
            )
        )
    }

    @Test
    fun sessionOpenEpochMillis_usesFirstCandleAnchor() {
        val barTime = "20250522  09:30:00"
        val instance = touchTurnRunningInstance().copy(
            touchTurnSession = TouchTurnSessionContext(
                sessionDate = "2025-05-22",
                status = TouchTurnCandleStatus.READY,
                candle = OhlcBar(
                    open = 100.0,
                    high = 101.0,
                    low = 99.0,
                    close = 100.5,
                    time = barTime
                )
            )
        )
        val open = TouchTurnRunStopLogic.sessionOpenEpochMillis(instance, "2025-05-22")!!
        val expected = TouchTurnLogic.marketOpenEpochMillis("2025-05-22", "America/New_York", barTime)!!
        assertEquals(expected, open)
    }

    @Test
    fun evaluateDeadlineForInstance_quickFlipReturnsNull() {
        val instance = defaultStrategyInstance(
            strategyType = StrategyType.QUICK_FLIP_SCALPER,
            symbol = "TSLA",
            maxDollars = 500,
            status = InstanceStatus.RUNNING
        ).onRunStarted("2025-05-22")
        assertNull(InstanceRunStopLogic.evaluateDeadlineForInstance(instance))
    }

    private fun touchTurnRunningInstance(): StrategyInstance =
        defaultStrategyInstance(
            strategyType = StrategyType.TOUCH_AND_TURN_SCALPER,
            symbol = "AAPL",
            maxDollars = 500,
            status = InstanceStatus.RUNNING
        ).onRunStarted("2025-05-22").copy(
            touchTurnSession = TouchTurnSessionContext(
                sessionDate = "2025-05-22",
                status = TouchTurnCandleStatus.READY
            )
        )
}
