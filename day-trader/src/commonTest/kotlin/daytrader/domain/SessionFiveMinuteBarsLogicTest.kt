package daytrader.domain

import daytrader.gateway.BrokerId
import daytrader.gateway.BrokerKind
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SessionFiveMinuteBarsLogicTest {
    @Test
    fun appendClosedBars_dedupesByBarTimeAndPreservesOrder() {
        val first = OhlcBar(open = 100.0, high = 101.0, low = 99.0, close = 100.5, time = "20260522  09:30:00")
        val second = OhlcBar(open = 100.5, high = 102.0, low = 100.0, close = 101.5, time = "20260522  09:35:00")
        val duplicate = second.copy(close = 101.6)
        val third = OhlcBar(open = 101.5, high = 103.0, low = 101.0, close = 102.0, time = "20260522  09:40:00")

        val afterFirst = SessionFiveMinuteBarsLogic.appendClosedBars(emptyList(), listOf(first, second))
        assertEquals(listOf(first, second), afterFirst)

        val afterDup = SessionFiveMinuteBarsLogic.appendClosedBars(afterFirst, listOf(duplicate, third))
        assertEquals(listOf(first, second, third), afterDup)
        assertEquals(101.5, afterDup[1].close, "duplicate time keeps the already-stored bar")
    }

    @Test
    fun appendClosedBars_skipsBarsWithoutTime() {
        val timed = OhlcBar(open = 100.0, high = 101.0, low = 99.0, close = 100.5, time = "20260522  09:30:00")
        val untimed = OhlcBar(open = 100.0, high = 101.0, low = 99.0, close = 100.5, time = null)
        assertEquals(
            listOf(timed),
            SessionFiveMinuteBarsLogic.appendClosedBars(emptyList(), listOf(untimed, timed))
        )
    }

    @Test
    fun buildTouchTurnRunRecord_freezesSessionFiveMinuteBars_whenConfirmationDisabled() {
        val bars = listOf(
            OhlcBar(open = 100.0, high = 101.0, low = 99.0, close = 100.5, time = "20260522  09:30:00"),
            OhlcBar(open = 100.5, high = 102.0, low = 100.0, close = 101.5, time = "20260522  09:35:00"),
        )
        val instance = defaultStrategyDeployment(
            strategyType = StrategyType.TOUCH_AND_TURN_SCALPER,
            symbol = "AAPL",
            maxDollars = 1_000
        ).onSessionStarted("2026-05-22", touchTurnStartedBy = TouchTurnSessionStartedBy.MANUAL)
            .copy(
                touchTurnSession = TouchTurnSessionContext(
                    sessionDate = "2026-05-22",
                    status = TouchTurnCandleStatus.READY,
                    candle = OhlcBar(open = 100.0, high = 110.0, low = 99.0, close = 108.0, time = "20260522  09:30:00"),
                    decisionOutcome = TouchTurnSessionOutcome.NO_TRADE_NOT_LIQUIDITY,
                    fiveMinuteConfirmation = null,
                    sessionFiveMinuteBars = bars
                )
            )
        val stopped = instance.onSessionStopped(
            stopParams = SessionStopParams(
                stopTrigger = TouchTurnSessionStopTrigger.MANUAL,
                brokerId = BrokerId.EMULATOR,
                brokerKind = BrokerKind.EMULATOR,
                brokerUnrealizedPnLAtStop = null
            )
        )
        val record = stopped.sessionHistory.single().touchTurnRunRecord
        assertEquals(bars, record?.sessionFiveMinuteBars)
        assertEquals(null, record?.fiveMinuteConfirmation)
        assertTrue(record?.sessionFiveMinuteBars?.isNotEmpty() == true)
    }
}
