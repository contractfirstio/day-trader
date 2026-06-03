package daytrader.domain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TouchTurnLogicTest {
    @Test
    fun normalizeIbBarTime_metConvertsToLondonSummer() {
        val normalized = TouchTurnLogic.normalizeIbBarTimeToMarketZone(
            "20260526 09:00:00 MET",
            "Europe/London"
        )
        assertEquals("20260526  08:00:00", normalized)
    }

    @Test
    fun selectFirstFifteenMinuteBar_prefersEightAmLondonAfterMetNormalization() {
        val bars = listOf(
            OhlcBar(open = 1.0, high = 2.0, low = 0.5, close = 1.5, time = "20260526 09:00:00 MET"),
            OhlcBar(open = 1.0, high = 2.0, low = 0.5, close = 1.5, time = "20260526 09:15:00 MET")
        )
        val selected = TouchTurnLogic.selectFirstFifteenMinuteBar(
            bars,
            "Europe/London",
            "20260526"
        )
        assertEquals("20260526  08:00:00", selected?.time)
    }

    @Test
    fun firstCandleCloseStatus_closedAfterMetBarNormalizedToLondon() {
        val bar = OhlcBar(
            open = 400.0,
            high = 401.0,
            low = 399.0,
            close = 400.5,
            time = "20260526 09:00:00 MET"
        )
        val barEnd = TouchTurnLogic.barEndEpochMillis(bar.time!!, "Europe/London")!!
        assertEquals(FirstCandleCloseStatus.CLOSED, TouchTurnLogic.firstCandleCloseStatus(bar, "Europe/London", barEnd))
    }

    @Test
    fun liquidityCandle_whenRangeExceedsThreshold() {
        val bar = OhlcBar(open = 100.0, high = 110.0, low = 99.0, close = 108.0)
        val threshold = TouchTurnLogic.liquidityRangeThreshold(adr14 = 20.0)
        val setup = TouchTurnLogic.computeBracketSetup(bar, rangeThreshold = threshold)
        assertTrue(setup.isLiquidityCandle)
        assertEquals(11.0, setup.range, 0.001)
    }

    @Test
    fun notLiquidity_whenRangeAtOrBelowThreshold() {
        val bar = OhlcBar(open = 100.0, high = 100.5, low = 99.0, close = 100.2)
        val setup = TouchTurnLogic.computeBracketSetup(bar, rangeThreshold = 2.0)
        assertFalse(setup.isLiquidityCandle)
        assertEquals(1.5, setup.range, 0.001)
    }

    @Test
    fun computeAdr14_averagesLastFourteenCompletedDays() {
        val bars = (1..16).map { day ->
            OhlcBar(
                open = 100.0,
                high = 100.0 + day,
                low = 100.0,
                close = 100.0 + day / 2.0,
                time = "202505${day.toString().padStart(2, '0')}  16:00:00"
            )
        }
        val adr = TouchTurnLogic.computeAdr14(bars, excludeSessionDayYyyyMmdd = "20250516").getOrThrow()
        // Days 2..15 = 14 days with ranges 2..15, mean = (2+15)*14/2 / 14 = 8.5
        assertEquals(8.5, adr, 0.001)
    }

    @Test
    fun liquidityThreshold_is25PercentOfAdr() {
        assertEquals(2.5, TouchTurnLogic.liquidityRangeThreshold(10.0), 0.001)
    }

    @Test
    fun longStopRespectsMinimumDistance_onRedCandle() {
        val bar = OhlcBar(open = 10.02, high = 10.02, low = 10.0, close = 10.0)
        val setup = TouchTurnLogic.computeBracketSetup(bar, rangeThreshold = 1.0, minStopDistance = 0.05)
        assertEquals(TouchTurnTradeSide.LONG, setup.side)
        assertEquals(10.0, setup.entry, 0.001)
        assertEquals(10.0 - 0.05, setup.stopLoss, 0.001)
    }

    @Test
    fun greenLiquidityBar_shortAtHigh_fib618TakeProfit_stopAboveHigh() {
        val bar = OhlcBar(open = 400.0, high = 410.0, low = 400.0, close = 408.0)
        val setup = TouchTurnLogic.computeBracketSetup(bar, rangeThreshold = 5.0)
        assertTrue(setup.isLiquidityCandle)
        assertEquals(FirstCandleColor.GREEN, setup.candleColor)
        assertEquals(TouchTurnTradeSide.SHORT, setup.side)
        assertEquals(410.0, setup.entry, 0.001)
        val tpDistance = 10.0 * TouchTurnDefaults.TAKE_PROFIT_FIB_RATIO_GREEN
        assertEquals(410.0 - tpDistance, setup.takeProfit, 0.001)
        assertEquals(410.0 + tpDistance / 2.0, setup.stopLoss, 0.001)
    }

    @Test
    fun redLiquidityBar_longAtLow_fib382TakeProfit_stopBelowLow() {
        val bar = OhlcBar(open = 410.0, high = 410.0, low = 400.0, close = 402.0)
        val setup = TouchTurnLogic.computeBracketSetup(bar, rangeThreshold = 5.0)
        assertTrue(setup.isLiquidityCandle)
        assertEquals(FirstCandleColor.RED, setup.candleColor)
        assertEquals(TouchTurnTradeSide.LONG, setup.side)
        assertEquals(400.0, setup.entry, 0.001)
        val tpDistance = 10.0 * TouchTurnDefaults.TAKE_PROFIT_FIB_RATIO_RED
        assertEquals(400.0 + tpDistance, setup.takeProfit, 0.001)
        assertEquals(400.0 - tpDistance / 2.0, setup.stopLoss, 0.001)
    }

    @Test
    fun candleStillForming_beforeBarEnd() {
        val bar = OhlcBar(
            open = 400.0,
            high = 401.0,
            low = 399.0,
            close = 400.5,
            time = "20250522  09:30:00"
        )
        val barEnd = TouchTurnLogic.barEndEpochMillis(bar.time!!, "Asia/Hong_Kong")!!
        val status = TouchTurnLogic.firstCandleCloseStatus(bar, "Asia/Hong_Kong", barEnd - 60_000)
        assertEquals(FirstCandleCloseStatus.FORMING, status)
    }

    @Test
    fun candleClosed_atOrAfterBarEnd() {
        val bar = OhlcBar(
            open = 400.0,
            high = 401.0,
            low = 399.0,
            close = 400.5,
            time = "20250522  09:30:00"
        )
        val barEnd = TouchTurnLogic.barEndEpochMillis(bar.time!!, "Asia/Hong_Kong")!!
        val status = TouchTurnLogic.firstCandleCloseStatus(bar, "Asia/Hong_Kong", barEnd)
        assertEquals(FirstCandleCloseStatus.CLOSED, status)
    }

    @Test
    fun candleClosed_usesScheduledRthEndWhenPastSessionOpenPlusFifteenMinutes() {
        val bar = OhlcBar(
            open = 400.0,
            high = 401.0,
            low = 399.0,
            close = 400.5,
            time = "20260526  14:30:00"
        )
        val sessionDate = "2026-05-26"
        val zoneId = "America/New_York"
        val scheduledEnd = TouchTurnLogic.marketOpenEpochMillis(sessionDate, zoneId)!! +
            TouchTurnLogic.FIRST_CANDLE_BAR_DURATION_MS
        val status = TouchTurnLogic.firstCandleCloseStatus(
            bar,
            zoneId,
            nowEpochMillis = scheduledEnd,
            sessionDateIso = sessionDate
        )
        assertEquals(FirstCandleCloseStatus.CLOSED, status)
    }

    @Test
    fun liquidityResolved_whenSessionDateMarksClosedBeforeBarWallClockEnd() {
        val sessionDate = "2026-06-01"
        val zone = "Asia/Hong_Kong"
        val barTime = "20260601  16:27:06"
        val bar = OhlcBar(open = 384.0, high = 389.0, low = 383.0, close = 388.0, time = barTime)
        val scheduledEnd = TouchTurnLogic.marketOpenEpochMillis(sessionDate, zone)!! +
            TouchTurnLogic.FIRST_CANDLE_BAR_DURATION_MS
        val barEnd = TouchTurnLogic.barEndEpochMillis(barTime, zone)!!
        assertTrue(scheduledEnd < barEnd - 60_000)
        val threshold = 1.0
        assertEquals(
            LiquidityCandleEvaluation.AWAITING_CLOSE,
            TouchTurnLogic.liquidityCandleEvaluation(bar, zone, threshold, nowEpochMillis = scheduledEnd)
        )
        assertEquals(
            LiquidityCandleEvaluation.LIQUIDITY,
            TouchTurnLogic.liquidityCandleEvaluation(
                bar,
                zone,
                threshold,
                nowEpochMillis = scheduledEnd,
                sessionDateIso = sessionDate
            )
        )
    }

    @Test
    fun liquidityAwaitingClose_whenBarClosedButOhlcNotLoadedYet() {
        val barTime = "20250522  09:30:00"
        val barEnd = TouchTurnLogic.barEndEpochMillis(barTime, "Asia/Hong_Kong")!!
        val eval = TouchTurnLogic.liquidityCandleEvaluation(
            candle = null,
            barTime = barTime,
            marketZoneId = "Asia/Hong_Kong",
            rangeThreshold = 2.0,
            nowEpochMillis = barEnd
        )
        assertEquals(LiquidityCandleEvaluation.AWAITING_CLOSE, eval)
    }

    @Test
    fun liquidityAwaitingClose_whileBarForming() {
        val bar = OhlcBar(
            open = 400.0,
            high = 401.0,
            low = 399.5,
            close = 400.5,
            time = "20250522  09:30:00"
        )
        val barEnd = TouchTurnLogic.barEndEpochMillis(bar.time!!, "Asia/Hong_Kong")!!
        val eval = TouchTurnLogic.liquidityCandleEvaluation(
            bar,
            "Asia/Hong_Kong",
            rangeThreshold = 2.0,
            nowEpochMillis = barEnd - 60_000
        )
        assertEquals(LiquidityCandleEvaluation.AWAITING_CLOSE, eval)
    }

    @Test
    fun notLiquidity_whenClosedAndRangeBelowThreshold() {
        val bar = OhlcBar(
            open = 400.0,
            high = 400.5,
            low = 399.5,
            close = 400.2,
            time = "20250522  09:30:00"
        )
        val barEnd = TouchTurnLogic.barEndEpochMillis(bar.time!!, "Asia/Hong_Kong")!!
        val eval = TouchTurnLogic.liquidityCandleEvaluation(
            bar,
            "Asia/Hong_Kong",
            rangeThreshold = 2.0,
            nowEpochMillis = barEnd
        )
        assertEquals(LiquidityCandleEvaluation.NOT_LIQUIDITY, eval)
        assertFalse(TouchTurnLogic.isLiquidityCandle(bar, 2.0))
    }

    @Test
    fun liquidityCandle_whenClosedAndRangeExceedsThreshold() {
        val bar = OhlcBar(
            open = 400.0,
            high = 405.0,
            low = 399.0,
            close = 404.0,
            time = "20250522  09:30:00"
        )
        val barEnd = TouchTurnLogic.barEndEpochMillis(bar.time!!, "Asia/Hong_Kong")!!
        val eval = TouchTurnLogic.liquidityCandleEvaluation(
            bar,
            "Asia/Hong_Kong",
            rangeThreshold = 1.87,
            nowEpochMillis = barEnd
        )
        assertEquals(LiquidityCandleEvaluation.LIQUIDITY, eval)
        assertTrue(TouchTurnLogic.isLiquidityCandle(bar, 1.87))
    }

    @Test
    fun greenCandle_whenCloseAboveOpen() {
        val bar = OhlcBar(open = 400.0, high = 410.0, low = 399.0, close = 405.0)
        assertEquals(FirstCandleColor.GREEN, TouchTurnLogic.firstCandleColor(bar))
    }

    @Test
    fun redCandle_whenCloseBelowOpen() {
        val bar = OhlcBar(open = 400.0, high = 410.0, low = 395.0, close = 398.0)
        assertEquals(FirstCandleColor.RED, TouchTurnLogic.firstCandleColor(bar))
    }

    @Test
    fun plannedOrders_shortBracket_onGreenLiquidityBar() {
        val bar = OhlcBar(open = 400.0, high = 410.0, low = 400.0, close = 408.0)
        val setup = TouchTurnLogic.computeBracketSetup(bar, rangeThreshold = 5.0)
        val plan = TouchTurnOrderPlanner.buildOrderPlan("700", setup, maxDollars = 4100, currencyCode = "HKD")!!
        assertEquals(10, plan.quantity)
        assertEquals(TouchTurnTradeSide.SHORT, plan.side)
        assertEquals(3, plan.orders.size)
        assertEquals("SELL", plan.orders[0].action)
        assertEquals("LMT", plan.orders[0].orderType)
        assertEquals(410.0, plan.orders[0].price, 0.001)
        assertEquals(TouchTurnOrderRole.TAKE_PROFIT, plan.orders[1].role)
        assertEquals("BUY", plan.orders[1].action)
        assertEquals(TouchTurnOrderRole.STOP_LOSS, plan.orders[2].role)
        assertEquals("STP", plan.orders[2].orderType)
        plan.orders.forEach { leg ->
            assertEquals(TouchTurnOrderDefaults.TIME_IN_FORCE, leg.timeInForce)
        }
    }

    @Test
    fun plannedOrders_longBracket_onRedLiquidityBar() {
        val bar = OhlcBar(open = 410.0, high = 410.0, low = 400.0, close = 402.0)
        val setup = TouchTurnLogic.computeBracketSetup(bar, rangeThreshold = 5.0)
        val plan = TouchTurnOrderPlanner.buildOrderPlan("700", setup, maxDollars = 4000)!!
        assertEquals(10, plan.quantity)
        assertEquals("BUY", plan.orders[0].action)
        assertEquals(400.0, plan.orders[0].price, 0.001)
        assertEquals("SELL", plan.orders[1].action)
        assertEquals("STP", plan.orders[2].orderType)
    }

    @Test
    fun withFirstFifteenMinuteCandle_doesNotStoreOpeningBarOhlc() {
        val bar = OhlcBar(open = 400.0, high = 410.0, low = 400.0, close = 401.0, time = "20250522  09:30:00")
        val deployment = defaultStrategyDeployment(
            strategyType = StrategyType.TOUCH_AND_TURN_SCALPER,
            symbol = "700",
            maxDollars = 5000
        ).beginTouchTurnSession("2025-05-22")
            .withFirstFifteenMinuteCandle(
                sessionDate = "2025-05-22",
                candle = bar,
                atr14 = 10.0,
                volumeSma20 = 1_000_000.0,
                marketZoneId = "Asia/Hong_Kong"
            )
        val session = deployment.touchTurnSession!!
        assertEquals(bar.time, session.openingBarTime)
        assertEquals(null, session.candle)
        assertEquals(null, session.setup)
    }

    @Test
    fun entryOrdersPermitted_whenLiquidityActionableAfterBarClose() {
        val bar = OhlcBar(open = 400.0, high = 410.0, low = 400.0, close = 401.0, time = "20250522  09:30:00")
        val barEnd = TouchTurnLogic.barEndEpochMillis(bar.time!!, "Asia/Hong_Kong")!!
        val instance = defaultStrategyDeployment(
            strategyType = StrategyType.TOUCH_AND_TURN_SCALPER,
            symbol = "700",
            maxDollars = 5000
        ).copy(
            touchTurnSession = TouchTurnSessionContext(
                sessionDate = "2025-05-22",
                status = TouchTurnCandleStatus.READY,
                candle = bar,
                adr14 = 10.0,
                rangeThreshold = 5.0,
                marketZoneId = "Asia/Hong_Kong"
            )
        )
        val evaluated = instance.withLiquidityEvaluatedIfClosed(nowEpochMillis = barEnd + 10_000)
        assertEquals(true, evaluated.touchTurnSession?.entryOrdersPermitted)
        val pastDeadline = barEnd + TouchTurnDefaults.CLOSE_CONFIRMATION_AFTER_CLOSE_MS + 1
        val lateEval = instance.withLiquidityEvaluatedIfClosed(nowEpochMillis = pastDeadline)
        assertEquals(false, lateEval.touchTurnSession?.entryOrdersPermitted)
        assertEquals(
            TouchTurnCloseConfirmation.EXPIRED,
            lateEval.touchTurnSession?.closeConfirmation(pastDeadline)
        )
    }

    @Test
    fun millisUntilNextMarketOpen_beforeTodayOpen() {
        val zone = "Asia/Hong_Kong"
        val todayOpen = TouchTurnLogic.marketOpenEpochMillis("2025-05-22", zone, "20250522  09:30:00")!!
        val remaining = TouchTurnLogic.millisUntilNextMarketOpen(zone, todayOpen - 90_000)
        assertEquals(90_000L, remaining)
        assertEquals("1m 30s", TouchTurnLogic.formatCountdownToNextMarketOpen(remaining))
    }

    @Test
    fun millisUntilNextMarketOpen_afterTodayOpenIsTomorrow() {
        val zone = "Asia/Hong_Kong"
        val todayOpen = TouchTurnLogic.marketOpenEpochMillis("2025-05-22", zone, "20250522  09:30:00")!!
        val next = TouchTurnLogic.nextMarketOpenEpochMillis(zone, todayOpen + 60_000)
        val tomorrowOpen = todayOpen + 24 * 60 * 60 * 1000
        assertEquals(tomorrowOpen, next)
        assertEquals(tomorrowOpen - (todayOpen + 60_000), TouchTurnLogic.millisUntilNextMarketOpen(zone, todayOpen + 60_000))
    }

    @Test
    fun millisSinceLastMarketOpenWallClock_matchesSessionBasedAfterOpen() {
        val barTime = "20250522  09:30:00"
        val open = TouchTurnLogic.marketOpenEpochMillis("2025-05-22", "Asia/Hong_Kong", barTime)!!
        val now = open + 125_000
        assertEquals(
            TouchTurnLogic.millisSinceLastMarketOpen("2025-05-22", "Asia/Hong_Kong", barTime, now),
            TouchTurnLogic.millisSinceLastMarketOpenWallClock("Asia/Hong_Kong", now)
        )
    }

    @Test
    fun millisSinceLastMarketOpen_afterTodayOpen() {
        val barTime = "20250522  09:30:00"
        val open = TouchTurnLogic.marketOpenEpochMillis("2025-05-22", "Asia/Hong_Kong", barTime)!!
        val elapsed = TouchTurnLogic.millisSinceLastMarketOpen(
            "2025-05-22",
            "Asia/Hong_Kong",
            barTime,
            open + 3_661_000
        )!!
        assertEquals(3_661_000L, elapsed)
        assertEquals("1h 01m 01s", TouchTurnLogic.formatElapsedSinceMarketOpen(elapsed))
    }

    @Test
    fun millisSinceLastMarketOpen_beforeTodayOpenUsesYesterday() {
        val barTime = "20250522  09:30:00"
        val todayOpen = TouchTurnLogic.marketOpenEpochMillis("2025-05-22", "Asia/Hong_Kong", barTime)!!
        val elapsed = TouchTurnLogic.millisSinceLastMarketOpen(
            "2025-05-22",
            "Asia/Hong_Kong",
            barTime,
            todayOpen - 60_000
        )!!
        assertTrue(elapsed >= 23 * 60 * 60 * 1000L)
    }

    @Test
    fun marketOpen_fromFirstCandleBarTime() {
        val barTime = "20250522  09:30:00"
        val open = TouchTurnLogic.marketOpenEpochMillis("2025-05-22", "Asia/Hong_Kong", barTime)!!
        val barStart = TouchTurnLogic.barStartEpochMillis(barTime, "Asia/Hong_Kong")!!
        assertEquals(barStart, open)
    }

    @Test
    fun noPlannedOrders_whenNotLiquidity() {
        val bar = OhlcBar(open = 100.0, high = 100.5, low = 99.0, close = 100.2)
        val setup = TouchTurnLogic.computeBracketSetup(bar, rangeThreshold = 2.0)
        assertNull(TouchTurnOrderPlanner.buildOrderPlan("SPY", setup, maxDollars = 5000))
    }

    @Test
    fun liquidityCandle_whenRangeExceedsThreshold_andGreen() {
        val bar = OhlcBar(open = 400.0, high = 410.0, low = 399.0, close = 409.0)
        val setup = TouchTurnLogic.computeBracketSetup(bar, rangeThreshold = 5.0)
        assertTrue(setup.isLiquidityCandle)
        assertEquals(FirstCandleColor.GREEN, TouchTurnLogic.firstCandleColor(bar))
        assertTrue(setup.isActionable)
    }

    @Test
    fun closeConfirmation_passesWhenGreenCloseBelowEntryWithinOneMinuteOfBarClose() {
        val bar = OhlcBar(
            open = 400.0,
            high = 410.0,
            low = 400.0,
            close = 405.0,
            time = "20250522  09:30:00"
        )
        val setup = TouchTurnLogic.computeBracketSetup(bar, rangeThreshold = 5.0)
        val barEnd = TouchTurnLogic.barEndEpochMillis(bar.time!!, "Asia/Hong_Kong")!!
        val now = barEnd + 30_000
        assertEquals(
            TouchTurnCloseConfirmation.PASSED,
            TouchTurnLogic.closeConfirmation(bar, setup, "Asia/Hong_Kong", now)
        )
        assertTrue(TouchTurnLogic.closeConfirmsTurn(setup, bar.close))
        assertTrue(TouchTurnLogic.closeConfirmationWithinDeadline(bar, "Asia/Hong_Kong", now))
    }

    @Test
    fun closeConfirmation_failsWhenGreenCloseAtOrAboveEntry() {
        val bar = OhlcBar(
            open = 400.0,
            high = 410.0,
            low = 400.0,
            close = 410.0,
            time = "20250522  09:30:00"
        )
        val setup = TouchTurnLogic.computeBracketSetup(bar, rangeThreshold = 5.0)
        val barEnd = TouchTurnLogic.barEndEpochMillis(bar.time!!, "Asia/Hong_Kong")!!
        val now = barEnd + 30_000
        assertEquals(
            TouchTurnCloseConfirmation.FAILED,
            TouchTurnLogic.closeConfirmation(bar, setup, "Asia/Hong_Kong", now)
        )
        assertFalse(TouchTurnLogic.closeConfirmsTurn(setup, bar.close))
    }

    @Test
    fun closeConfirmation_passesWhenRedCloseAboveEntryWithinOneMinuteOfBarClose() {
        val bar = OhlcBar(
            open = 410.0,
            high = 410.0,
            low = 400.0,
            close = 405.0,
            time = "20250522  09:30:00"
        )
        val setup = TouchTurnLogic.computeBracketSetup(bar, rangeThreshold = 5.0)
        val barEnd = TouchTurnLogic.barEndEpochMillis(bar.time!!, "Asia/Hong_Kong")!!
        val now = barEnd + 30_000
        assertEquals(
            TouchTurnCloseConfirmation.PASSED,
            TouchTurnLogic.closeConfirmation(bar, setup, "Asia/Hong_Kong", now)
        )
        assertTrue(TouchTurnLogic.closeConfirmsTurn(setup, bar.close))
    }

    @Test
    fun closeConfirmation_failsWhenRedCloseAtOrBelowEntry() {
        val bar = OhlcBar(
            open = 410.0,
            high = 410.0,
            low = 400.0,
            close = 400.0,
            time = "20250522  09:30:00"
        )
        val setup = TouchTurnLogic.computeBracketSetup(bar, rangeThreshold = 5.0)
        val barEnd = TouchTurnLogic.barEndEpochMillis(bar.time!!, "Asia/Hong_Kong")!!
        val now = barEnd + 30_000
        assertEquals(
            TouchTurnCloseConfirmation.FAILED,
            TouchTurnLogic.closeConfirmation(bar, setup, "Asia/Hong_Kong", now)
        )
        assertFalse(TouchTurnLogic.closeConfirmsTurn(setup, bar.close))
    }

    @Test
    fun closeConfirmationBufferPrice_isBelowEntryForShortAndAboveForLong() {
        val greenBar = OhlcBar(open = 400.0, high = 410.0, low = 400.0, close = 405.0)
        val greenSetup = TouchTurnLogic.computeBracketSetup(greenBar, rangeThreshold = 5.0)
        assertEquals(408.5, TouchTurnLogic.closeConfirmationBufferPrice(greenSetup))

        val redBar = OhlcBar(open = 410.0, high = 410.0, low = 400.0, close = 405.0)
        val redSetup = TouchTurnLogic.computeBracketSetup(redBar, rangeThreshold = 5.0)
        assertEquals(401.5, TouchTurnLogic.closeConfirmationBufferPrice(redSetup))
    }

    @Test
    fun closeConfirmation_failsWhenGreenCloseTooCloseToEntry() {
        val bar = OhlcBar(
            open = 400.0,
            high = 410.0,
            low = 400.0,
            close = 409.5,
            time = "20250522  09:30:00"
        )
        val setup = TouchTurnLogic.computeBracketSetup(bar, rangeThreshold = 5.0)
        val minDistance = TouchTurnLogic.closeConfirmationMinDistanceFromEntry(setup)
        assertEquals(1.5, minDistance, absoluteTolerance = 1e-9)
        assertFalse(TouchTurnLogic.closeConfirmsTurn(setup, bar.close))
        val barEnd = TouchTurnLogic.barEndEpochMillis(bar.time!!, "Asia/Hong_Kong")!!
        assertEquals(
            TouchTurnCloseConfirmation.FAILED,
            TouchTurnLogic.closeConfirmation(bar, setup, "Asia/Hong_Kong", barEnd + 30_000)
        )
    }

    @Test
    fun closeConfirmation_failsWhenRedCloseTooCloseToEntry() {
        val bar = OhlcBar(
            open = 410.0,
            high = 410.0,
            low = 400.0,
            close = 400.5,
            time = "20250522  09:30:00"
        )
        val setup = TouchTurnLogic.computeBracketSetup(bar, rangeThreshold = 5.0)
        assertFalse(TouchTurnLogic.closeConfirmsTurn(setup, bar.close))
        val barEnd = TouchTurnLogic.barEndEpochMillis(bar.time!!, "Asia/Hong_Kong")!!
        assertEquals(
            TouchTurnCloseConfirmation.FAILED,
            TouchTurnLogic.closeConfirmation(bar, setup, "Asia/Hong_Kong", barEnd + 30_000)
        )
    }

    @Test
    fun closeConfirmation_expiredWhenMoreThanOneMinuteAfterBarClose() {
        val bar = OhlcBar(
            open = 400.0,
            high = 410.0,
            low = 400.0,
            close = 405.0,
            time = "20250522  09:30:00"
        )
        val setup = TouchTurnLogic.computeBracketSetup(bar, rangeThreshold = 5.0)
        val barEnd = TouchTurnLogic.barEndEpochMillis(bar.time!!, "Asia/Hong_Kong")!!
        val now = barEnd + TouchTurnDefaults.CLOSE_CONFIRMATION_AFTER_CLOSE_MS + 1
        assertEquals(
            TouchTurnCloseConfirmation.EXPIRED,
            TouchTurnLogic.closeConfirmation(bar, setup, "Asia/Hong_Kong", now)
        )
        assertFalse(TouchTurnLogic.closeConfirmationWithinDeadline(bar, "Asia/Hong_Kong", now))
    }
}
