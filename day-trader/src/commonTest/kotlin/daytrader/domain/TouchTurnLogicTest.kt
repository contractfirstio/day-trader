package daytrader.domain

import java.time.LocalDate
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
    fun normalizeIbBarTime_londonNoSuffixTreatsNineAmAsMetWallClock() {
        val normalized = TouchTurnLogic.normalizeIbBarTimeToMarketZone(
            "20260126 09:00:00",
            "Europe/London"
        )
        assertEquals("20260126  08:00:00", normalized)
    }

    @Test
    fun normalizeIbBarTime_londonUnknownSuffixTreatsAsMet() {
        val normalized = TouchTurnLogic.normalizeIbBarTimeToMarketZone(
            "20260526 09:15:00 MECST",
            "Europe/London"
        )
        assertEquals("20260526  08:15:00", normalized)
    }

    @Test
    fun normalizeIbBarTime_usMarketUnchangedWithoutSuffix() {
        val normalized = TouchTurnLogic.normalizeIbBarTimeToMarketZone(
            "20260526 09:30:00",
            "America/New_York"
        )
        assertEquals("20260526  09:30:00", normalized)
    }

    @Test
    fun selectFirstFifteenMinuteBar_prefersNearestOpenNotLatestWhenExactMatchMissing() {
        val bars = listOf(
            OhlcBar(open = 1.0, high = 2.0, low = 0.5, close = 1.5, time = "20260526 09:15:00 MET"),
            OhlcBar(open = 1.0, high = 2.0, low = 0.5, close = 1.5, time = "20260526 10:00:00 MET")
        )
        val selected = TouchTurnLogic.selectFirstFifteenMinuteBar(
            bars,
            "Europe/London",
            "20260526"
        )
        assertEquals("20260526  08:15:00", selected?.time)
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
    fun shortStopIsHalfTakeProfitDistance_onSmallRangeBar() {
        val bar = OhlcBar(open = 5.36, high = 5.41, low = 5.34, close = 5.37)
        val setup = TouchTurnLogic.computeBracketSetup(
            bar,
            rangeThreshold = 0.01,
            rules = TouchTurnRuleConfig.DEFAULT.copy(entryInwardOffsetRatioOfRange = 0.0)
        )
        val fib38 = 5.34 + 0.07 * TouchTurnDefaults.TAKE_PROFIT_FIB_RATIO_GREEN
        val tpDistance = 5.41 - fib38
        assertEquals(TouchTurnTradeSide.SHORT, setup.side)
        assertEquals(5.41, setup.entry, 0.001)
        assertEquals(fib38, setup.takeProfit, 0.001)
        assertEquals(5.41 + tpDistance / 2.0, setup.stopLoss, 0.001)
    }

    @Test
    fun greenLiquidityBar_shortTakeProfit_atAbsoluteFib38RetracementLevel() {
        val bar = OhlcBar(open = 400.0, high = 410.0, low = 400.0, close = 408.0)
        val setup = TouchTurnLogic.computeBracketSetup(
            bar,
            rangeThreshold = 5.0,
            rules = TouchTurnRuleConfig.DEFAULT.copy(entryInwardOffsetRatioOfRange = 0.0)
        )
        val fib38 = 400.0 + 10.0 * TouchTurnDefaults.TAKE_PROFIT_FIB_RATIO_GREEN
        assertEquals(fib38, setup.takeProfit, 0.001)
        assertEquals(410.0 - fib38, 410.0 - setup.takeProfit, 0.001)
        assertEquals(410.0 + (410.0 - fib38) / 2.0, setup.stopLoss, 0.001)
    }

    @Test
    fun greenLiquidityBar_shortAtHigh_fib382TakeProfit_stopAboveHigh() {
        val bar = OhlcBar(open = 400.0, high = 410.0, low = 400.0, close = 408.0)
        val setup = TouchTurnLogic.computeBracketSetup(bar, rangeThreshold = 5.0)
        assertTrue(setup.isLiquidityCandle)
        assertEquals(FirstCandleColor.GREEN, setup.candleColor)
        assertEquals(TouchTurnTradeSide.SHORT, setup.side)
        val entryOffset = 10.0 * TouchTurnDefaults.ENTRY_INWARD_OFFSET_RATIO_OF_RANGE
        val entry = 410.0 - entryOffset
        val fib38 = 400.0 + 10.0 * TouchTurnDefaults.TAKE_PROFIT_FIB_RATIO_GREEN
        val tpDistance = entry - fib38
        assertEquals(entry, setup.entry, 0.001)
        assertEquals(fib38, setup.takeProfit, 0.001)
        assertEquals(entry + tpDistance / 2.0, setup.stopLoss, 0.001)
    }

    @Test
    fun redLiquidityBar_longAtLow_fib382TakeProfit_stopBelowLow() {
        val bar = OhlcBar(open = 410.0, high = 410.0, low = 400.0, close = 402.0)
        val setup = TouchTurnLogic.computeBracketSetup(bar, rangeThreshold = 5.0)
        assertTrue(setup.isLiquidityCandle)
        assertEquals(FirstCandleColor.RED, setup.candleColor)
        assertEquals(TouchTurnTradeSide.LONG, setup.side)
        val entryOffset = 10.0 * TouchTurnDefaults.ENTRY_INWARD_OFFSET_RATIO_OF_RANGE
        val entry = 400.0 + entryOffset
        assertEquals(entry, setup.entry, 0.001)
        val tpDistance = 10.0 * TouchTurnDefaults.TAKE_PROFIT_FIB_RATIO_RED
        assertEquals(entry + tpDistance, setup.takeProfit, 0.001)
        assertEquals(entry - tpDistance / 2.0, setup.stopLoss, 0.001)
    }

    @Test
    fun entryInwardOffset_movesLongUpAndShortDown() {
        val bar = OhlcBar(open = 400.0, high = 410.0, low = 400.0, close = 408.0)
        val atExtreme = TouchTurnLogic.computeBracketSetup(
            bar,
            rangeThreshold = 5.0,
            rules = TouchTurnRuleConfig.DEFAULT.copy(entryInwardOffsetRatioOfRange = 0.0)
        )
        assertEquals(410.0, atExtreme.entry, 0.001)

        val inward = TouchTurnLogic.computeBracketSetup(
            bar,
            rangeThreshold = 5.0,
            rules = TouchTurnRuleConfig.DEFAULT.copy(entryInwardOffsetRatioOfRange = 0.15)
        )
        assertEquals(408.5, inward.entry, 0.001)
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
    fun validateClosedFirstCandleRefetch_notYetFinalBeforeSettleWindow() {
        val barTime = "20250522  09:30:00"
        val zone = "Asia/Hong_Kong"
        val barEnd = TouchTurnLogic.barEndEpochMillis(barTime, zone)!!
        val candle = OhlcBar(open = 400.0, high = 401.0, low = 399.0, close = 400.5, time = barTime)
        val (status, _) = TouchTurnLogic.validateClosedFirstCandleRefetch(
            candle = candle,
            openingBarTime = barTime,
            marketZoneId = zone,
            sessionDateIso = "2025-05-22",
            nowEpochMillis = barEnd + 1_000
        )
        assertEquals(ClosedFirstCandleRefetchValidation.NOT_YET_FINAL, status)
    }

    @Test
    fun validateClosedFirstCandleRefetch_readyAfterSettleAndBarEnd() {
        val barTime = "20250522  09:30:00"
        val zone = "Asia/Hong_Kong"
        val barEnd = TouchTurnLogic.barEndEpochMillis(barTime, zone)!!
        val candle = OhlcBar(open = 400.0, high = 401.0, low = 399.0, close = 400.5, time = barTime)
        val now = barEnd + TouchTurnDefaults.CLOSED_BAR_REFETCH_SETTLE_MS + 1
        val (status, reason) = TouchTurnLogic.validateClosedFirstCandleRefetch(
            candle = candle,
            openingBarTime = barTime,
            marketZoneId = zone,
            sessionDateIso = "2025-05-22",
            nowEpochMillis = now
        )
        assertEquals(ClosedFirstCandleRefetchValidation.READY, status)
        assertNull(reason)
    }

    @Test
    fun validateClosedFirstCandleRefetch_notYetFinalWhenRefetchedBarTimeDiffersFromAnchor() {
        val anchor = "20250522  09:30:00"
        val refetched = "20250522  09:45:00"
        val zone = "Asia/Hong_Kong"
        val anchorEnd = TouchTurnLogic.barEndEpochMillis(anchor, zone)!!
        val candle = OhlcBar(open = 400.0, high = 401.0, low = 399.0, close = 400.5, time = refetched)
        val (status, reason) = TouchTurnLogic.validateClosedFirstCandleRefetch(
            candle = candle,
            openingBarTime = anchor,
            marketZoneId = zone,
            sessionDateIso = "2025-05-22",
            nowEpochMillis = anchorEnd + TouchTurnDefaults.CLOSED_BAR_REFETCH_SETTLE_MS + 1
        )
        assertEquals(ClosedFirstCandleRefetchValidation.NOT_YET_FINAL, status)
        assertTrue(reason?.contains("!=") == true)
    }

    @Test
    fun validateClosedFirstCandleRefetch_readyWhenRefetchedTimeDriftsWithinSameBarPeriod() {
        val anchor = "20250522  09:30:00"
        val refetched = "20250522  09:30:28"
        val zone = "Asia/Hong_Kong"
        val anchorEnd = TouchTurnLogic.barEndEpochMillis(anchor, zone)!!
        val candle = OhlcBar(open = 400.0, high = 401.0, low = 399.0, close = 400.5, time = refetched)
        val (status, reason) = TouchTurnLogic.validateClosedFirstCandleRefetch(
            candle = candle,
            openingBarTime = anchor,
            marketZoneId = zone,
            sessionDateIso = "2025-05-22",
            nowEpochMillis = anchorEnd + TouchTurnDefaults.CLOSED_BAR_REFETCH_SETTLE_MS + 1
        )
        assertEquals(ClosedFirstCandleRefetchValidation.READY, status)
        assertNull(reason)
        assertTrue(TouchTurnLogic.openingBarPeriodEndsEqual(anchor, refetched, zone))
    }

    @Test
    fun millisUntilClosedBarRefetchReady_zeroAfterSettleElapsed() {
        val barTime = "20250522  09:30:00"
        val zone = "Asia/Hong_Kong"
        val barEnd = TouchTurnLogic.barEndEpochMillis(barTime, zone)!!
        val wait = TouchTurnLogic.millisUntilClosedBarRefetchReady(
            openingBarTime = barTime,
            marketZoneId = zone,
            nowEpochMillis = barEnd + TouchTurnDefaults.CLOSED_BAR_REFETCH_SETTLE_MS
        )
        assertEquals(0L, wait)
    }

    @Test
    fun firstCandleCloseStatus_acceleratedBarFormingEvenWhenPastScheduledRthEnd() {
        val sessionDate = "2026-06-03"
        val zone = "Asia/Hong_Kong"
        val barTime = "20260603  13:03:21"
        val barEnd = TouchTurnLogic.barEndEpochMillis(barTime, zone)!!
        val now = barEnd - 10_000
        assertTrue(now >= TouchTurnLogic.marketOpenEpochMillis(sessionDate, zone)!! + TouchTurnLogic.FIRST_CANDLE_BAR_DURATION_MS)
        assertEquals(
            FirstCandleCloseStatus.FORMING,
            TouchTurnLogic.firstCandleCloseStatus(barTime, zone, now, sessionDateIso = sessionDate)
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
    fun deferLiquidityEvaluationForLiveQuotes_trueWhenBidAskMissingWithinEntryWindow() {
        val barTime = "20250522  09:30:00"
        val barEnd = TouchTurnLogic.barEndEpochMillis(barTime, "Asia/Hong_Kong")!!
        val withinWindow = TouchTurnLogic.entryWindowStatus(
            barTime = barTime,
            marketZoneId = "Asia/Hong_Kong",
            nowEpochMillis = barEnd + 5_000
        )
        assertEquals(TouchTurnEntryWindowStatus.WITHIN_WINDOW, withinWindow)
        assertTrue(
            TouchTurnLogic.deferLiquidityEvaluationForLiveQuotes(
                requireLivePriceChecks = true,
                liveBid = null,
                liveAsk = 400.0,
                entryWindowStatus = withinWindow
            )
        )
    }

    @Test
    fun deferLiquidityEvaluationForLiveQuotes_falseWhenQuotesPresentOrWindowExpired() {
        val barTime = "20250522  09:30:00"
        val barEnd = TouchTurnLogic.barEndEpochMillis(barTime, "Asia/Hong_Kong")!!
        val withinWindow = TouchTurnEntryWindowStatus.WITHIN_WINDOW
        assertFalse(
            TouchTurnLogic.deferLiquidityEvaluationForLiveQuotes(
                requireLivePriceChecks = true,
                liveBid = 399.5,
                liveAsk = 400.5,
                entryWindowStatus = withinWindow
            )
        )
        assertFalse(
            TouchTurnLogic.deferLiquidityEvaluationForLiveQuotes(
                requireLivePriceChecks = false,
                liveBid = null,
                liveAsk = null,
                entryWindowStatus = withinWindow
            )
        )
        val expired = TouchTurnLogic.entryWindowStatus(
            barTime = barTime,
            marketZoneId = "Asia/Hong_Kong",
            nowEpochMillis = barEnd + TouchTurnDefaults.CLOSE_CONFIRMATION_AFTER_CLOSE_MS + 1
        )
        assertEquals(TouchTurnEntryWindowStatus.EXPIRED, expired)
        assertFalse(
            TouchTurnLogic.deferLiquidityEvaluationForLiveQuotes(
                requireLivePriceChecks = true,
                liveBid = null,
                liveAsk = null,
                entryWindowStatus = expired
            )
        )
    }

    @Test
    fun deferLiquidityEvaluationForLiveQuotes_falseWhenLiveQuoteRequiredDisabled() {
        val rules = TouchTurnRuleConfig.DEFAULT.copy(
            enables = TouchTurnRuleEnables.DEFAULT.copy(liveQuoteRequired = false)
        )
        assertFalse(
            TouchTurnLogic.deferLiquidityEvaluationForLiveQuotes(
                requireLivePriceChecks = true,
                liveBid = null,
                liveAsk = null,
                entryWindowStatus = TouchTurnEntryWindowStatus.WITHIN_WINDOW,
                rules = rules
            )
        )
    }

    @Test
    fun closeConfirmation_notExpiredWhenEntryWindowDisabledPastDeadline() {
        val bar = OhlcBar(
            open = 400.0,
            high = 410.0,
            low = 400.0,
            close = 403.0,
            time = "20250522  09:30:00"
        )
        val setup = TouchTurnLogic.computeBracketSetup(bar, rangeThreshold = 5.0)
        val barEnd = TouchTurnLogic.barEndEpochMillis(bar.time!!, "Asia/Hong_Kong")!!
        val pastDeadline = barEnd + TouchTurnDefaults.CLOSE_CONFIRMATION_AFTER_CLOSE_MS + 1
        val rules = TouchTurnRuleConfig.DEFAULT.copy(
            enables = TouchTurnRuleEnables.DEFAULT.copy(entryWindow = false)
        )
        assertEquals(
            TouchTurnCloseConfirmation.PASSED,
            TouchTurnLogic.closeConfirmation(bar, setup, "Asia/Hong_Kong", pastDeadline, rules = rules)
        )
    }

    @Test
    fun enforcesCloseConfirmation_followsEnabledRules() {
        val allOff = TouchTurnRuleEnables.DEFAULT.copy(
            barCloseTurn = false,
            entryWindow = false,
            liveQuoteRequired = false,
            liveBarAgreement = false,
            liveTurnConfirmation = false,
            liveEntryTouchable = false
        )
        assertFalse(TouchTurnRuleConfig.DEFAULT.copy(enables = allOff).enforcesCloseConfirmation(true))
        assertTrue(
            TouchTurnRuleConfig.DEFAULT.copy(
                enables = allOff.copy(barCloseTurn = true)
            ).enforcesCloseConfirmation(false)
        )
        assertTrue(
            TouchTurnRuleConfig.DEFAULT.copy(
                enables = allOff.copy(liveTurnConfirmation = true)
            ).enforcesCloseConfirmation(true)
        )
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
        assertEquals(409.8, plan.orders[0].price, 0.001)
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
        assertEquals(9, plan.quantity)
        assertEquals("BUY", plan.orders[0].action)
        assertEquals(400.2, plan.orders[0].price, 0.001)
        assertEquals("SELL", plan.orders[1].action)
        assertEquals("STP", plan.orders[2].orderType)
    }

    @Test
    fun liveEntryTouchable_long_falseWhenAskBelowEntryBuffer() {
        val bar = OhlcBar(open = 85.7, high = 85.7, low = 84.8, close = 83.4, time = "20260603  09:30:00")
        val setup = TouchTurnLogic.computeBracketSetup(bar, rangeThreshold = 0.1)
        assertFalse(TouchTurnLogic.liveEntryTouchable(setup, bid = 83.35, ask = 83.4))
    }

    @Test
    fun resolveLiveMid_prefersBidAskMidOverStaleLast() {
        assertEquals(83.375, TouchTurnLogic.resolveLiveMid(83.35, 83.4, 85.55)!!, 0.001)
        assertEquals(85.1, TouchTurnLogic.resolveLiveMid(null, null, 85.1)!!, 0.001)
    }

    @Test
    fun barCloseAgreesWithLiveMid_rejects3690StyleGap() {
        val bar = OhlcBar(open = 85.7, high = 85.7, low = 84.8, close = 85.0, time = "20250522  09:30:00")
        val liveMid = TouchTurnLogic.resolveLiveMid(83.35, 83.4, 85.55)!!
        assertFalse(TouchTurnLogic.barCloseAgreesWithLiveMid(bar, liveMid))
    }

    @Test
    fun barCloseAgreesWithLiveMid_passesWhenCloseNearLiveMid() {
        val bar = OhlcBar(open = 85.7, high = 85.7, low = 84.8, close = 85.0, time = "20250522  09:30:00")
        assertTrue(TouchTurnLogic.barCloseAgreesWithLiveMid(bar, 85.05))
    }

    @Test
    fun withLiquidityEvaluatedIfClosed_barLiveDivergence_blocks3690StyleSession() {
        val bar = OhlcBar(open = 85.7, high = 85.7, low = 84.8, close = 85.55, time = "20250522  09:30:00")
        val barEnd = TouchTurnLogic.barEndEpochMillis(bar.time!!, "Asia/Hong_Kong")!!
        val instance = defaultStrategyDeployment(
            strategyType = StrategyType.TOUCH_AND_TURN_SCALPER,
            symbol = "3690",
            maxDollars = 10_000
        ).copy(
            touchTurnSession = TouchTurnSessionContext(
                sessionDate = "2025-05-22",
                status = TouchTurnCandleStatus.READY,
                openingBarTime = bar.time,
                candle = bar,
                adr14 = 0.56,
                rangeThreshold = 0.1,
                marketZoneId = "Asia/Hong_Kong"
            )
        )
        val evaluated = instance.withLiquidityEvaluatedIfClosed(
            enforceCloseConfirmation = true,
            nowEpochMillis = barEnd + 10_000,
            liveBid = 83.35,
            liveAsk = 83.4,
            liveLast = 85.55,
            requireLivePriceChecks = true
        )
        assertEquals(
            TouchTurnSessionOutcome.NO_TRADE_BAR_LIVE_DIVERGENCE,
            evaluated.touchTurnSession?.decisionOutcome
        )
    }

    @Test
    fun liveCloseConfirmsTurn_long_falseWhenLiveMidBelowEntryBand() {
        val bar = OhlcBar(open = 85.7, high = 85.7, low = 84.8, close = 85.0, time = "20260603  09:30:00")
        val setup = TouchTurnLogic.computeBracketSetup(bar, rangeThreshold = 0.1)
        assertFalse(TouchTurnLogic.closeConfirmsTurn(setup, bar))
        assertFalse(TouchTurnLogic.liveCloseConfirmsTurn(setup, bar, livePrice = 83.4))
    }

    @Test
    fun withLiquidityEvaluatedIfClosed_liveGatesBlock3690StyleGap() {
        val bar = OhlcBar(open = 85.7, high = 85.7, low = 84.8, close = 85.0, time = "20250522  09:30:00")
        val barEnd = TouchTurnLogic.barEndEpochMillis(bar.time!!, "Asia/Hong_Kong")!!
        val instance = defaultStrategyDeployment(
            strategyType = StrategyType.TOUCH_AND_TURN_SCALPER,
            symbol = "3690",
            maxDollars = 10_000
        ).copy(
            touchTurnSession = TouchTurnSessionContext(
                sessionDate = "2025-05-22",
                status = TouchTurnCandleStatus.READY,
                openingBarTime = bar.time,
                candle = bar,
                adr14 = 0.56,
                rangeThreshold = 0.1,
                marketZoneId = "Asia/Hong_Kong"
            )
        )
        val evaluated = instance.withLiquidityEvaluatedIfClosed(
            enforceCloseConfirmation = true,
            nowEpochMillis = barEnd + 10_000,
            liveBid = 83.35,
            liveAsk = 83.4,
            liveLast = 83.4,
            requireLivePriceChecks = true
        )
        assertEquals(false, evaluated.touchTurnSession?.entryOrdersPermitted)
        assertEquals(
            TouchTurnSessionOutcome.NO_TRADE_CLOSE_CONFIRMATION_FAILED,
            evaluated.touchTurnSession?.decisionOutcome
        )
    }

    @Test
    fun withLiquidityEvaluatedIfClosed_liveCloseUsesMidNotStaleLastMatchingBar() {
        val bar = OhlcBar(open = 85.7, high = 85.7, low = 84.8, close = 85.55, time = "20250522  09:30:00")
        val barEnd = TouchTurnLogic.barEndEpochMillis(bar.time!!, "Asia/Hong_Kong")!!
        val instance = defaultStrategyDeployment(
            strategyType = StrategyType.TOUCH_AND_TURN_SCALPER,
            symbol = "3690",
            maxDollars = 10_000
        ).copy(
            touchTurnSession = TouchTurnSessionContext(
                sessionDate = "2025-05-22",
                status = TouchTurnCandleStatus.READY,
                openingBarTime = bar.time,
                candle = bar,
                adr14 = 0.56,
                rangeThreshold = 0.1,
                marketZoneId = "Asia/Hong_Kong"
            )
        )
        val evaluated = instance.withLiquidityEvaluatedIfClosed(
            enforceCloseConfirmation = false,
            nowEpochMillis = barEnd + 10_000,
            liveBid = 83.35,
            liveAsk = 83.4,
            liveLast = 85.55,
            requireLivePriceChecks = true
        )
        assertEquals(false, evaluated.touchTurnSession?.entryOrdersPermitted)
        assertEquals(
            TouchTurnSessionOutcome.NO_TRADE_BAR_LIVE_DIVERGENCE,
            evaluated.touchTurnSession?.decisionOutcome
        )
    }

    @Test
    fun withLiquidityEvaluatedIfClosed_liveGatesBlockWhenBarCloseInUpperRangeButLiveMidDiverges() {
        val bar = OhlcBar(open = 85.7, high = 85.7, low = 84.8, close = 85.55, time = "20250522  09:30:00")
        val barEnd = TouchTurnLogic.barEndEpochMillis(bar.time!!, "Asia/Hong_Kong")!!
        val instance = defaultStrategyDeployment(
            strategyType = StrategyType.TOUCH_AND_TURN_SCALPER,
            symbol = "3690",
            maxDollars = 10_000
        ).copy(
            touchTurnSession = TouchTurnSessionContext(
                sessionDate = "2025-05-22",
                status = TouchTurnCandleStatus.READY,
                openingBarTime = bar.time,
                candle = bar,
                adr14 = 0.56,
                rangeThreshold = 0.1,
                marketZoneId = "Asia/Hong_Kong"
            )
        )
        val evaluated = instance.withLiquidityEvaluatedIfClosed(
            enforceCloseConfirmation = true,
            nowEpochMillis = barEnd + 10_000,
            liveBid = 83.35,
            liveAsk = 83.4,
            liveLast = 83.4,
            requireLivePriceChecks = true
        )
        assertEquals(false, evaluated.touchTurnSession?.entryOrdersPermitted)
        assertEquals(
            TouchTurnSessionOutcome.NO_TRADE_BAR_LIVE_DIVERGENCE,
            evaluated.touchTurnSession?.decisionOutcome
        )
    }

    @Test
    fun withLiquidityEvaluatedIfClosed_liveCloseFailsBeforeEntryWhenAskDivergesFromStaleLast() {
        val bar = OhlcBar(open = 85.7, high = 85.7, low = 84.8, close = 85.55, time = "20250522  09:30:00")
        val barEnd = TouchTurnLogic.barEndEpochMillis(bar.time!!, "Asia/Hong_Kong")!!
        val setup = TouchTurnLogic.computeBracketSetup(bar, rangeThreshold = 0.1)
        val instance = defaultStrategyDeployment(
            strategyType = StrategyType.TOUCH_AND_TURN_SCALPER,
            symbol = "3690",
            maxDollars = 10_000
        ).copy(
            touchTurnSession = TouchTurnSessionContext(
                sessionDate = "2025-05-22",
                status = TouchTurnCandleStatus.READY,
                candle = bar,
                adr14 = 0.56,
                rangeThreshold = 0.1,
                marketZoneId = "Asia/Hong_Kong"
            )
        )
        assertTrue(TouchTurnLogic.closeConfirmsTurn(setup, bar))
        val liveAsk = 84.74
        val liveBid = 85.55 * 2.0 - liveAsk
        val liveMid = TouchTurnLogic.resolveLiveMid(liveBid, liveAsk, 85.55)!!
        assertTrue(TouchTurnLogic.barCloseAgreesWithLiveMid(bar, liveMid))
        assertTrue(TouchTurnLogic.liveCloseConfirmsTurn(setup, bar, liveMid))
        assertFalse(TouchTurnLogic.liveEntryTouchable(setup, liveBid, liveAsk))
        val evaluated = instance.withLiquidityEvaluatedIfClosed(
            enforceCloseConfirmation = true,
            nowEpochMillis = barEnd + 10_000,
            liveBid = liveBid,
            liveAsk = liveAsk,
            liveLast = 85.55,
            requireLivePriceChecks = true
        )
        assertEquals(
            TouchTurnSessionOutcome.NO_TRADE_ENTRY_NOT_TOUCHABLE,
            evaluated.touchTurnSession?.decisionOutcome
        )
    }

    @Test
    fun withLiquidityEvaluatedIfClosed_entryNotTouchableWhenLiveMidConfirmsButAskBelowEntry() {
        val bar = OhlcBar(open = 85.7, high = 85.7, low = 84.8, close = 85.55, time = "20250522  09:30:00")
        val barEnd = TouchTurnLogic.barEndEpochMillis(bar.time!!, "Asia/Hong_Kong")!!
        val setup = TouchTurnLogic.computeBracketSetup(bar, rangeThreshold = 0.1)
        val instance = defaultStrategyDeployment(
            strategyType = StrategyType.TOUCH_AND_TURN_SCALPER,
            symbol = "3690",
            maxDollars = 10_000
        ).copy(
            touchTurnSession = TouchTurnSessionContext(
                sessionDate = "2025-05-22",
                status = TouchTurnCandleStatus.READY,
                openingBarTime = bar.time,
                candle = bar,
                adr14 = 0.56,
                rangeThreshold = 0.1,
                marketZoneId = "Asia/Hong_Kong"
            )
        )
        val liveAsk = 84.74
        val liveMid = 85.55
        val liveBid = liveMid * 2.0 - liveAsk
        assertTrue(TouchTurnLogic.closeConfirmsTurn(setup, bar))
        assertTrue(TouchTurnLogic.liveCloseConfirmsTurn(setup, bar, liveMid))
        assertFalse(TouchTurnLogic.liveEntryTouchable(setup, liveBid, liveAsk))
        val evaluated = instance.withLiquidityEvaluatedIfClosed(
            enforceCloseConfirmation = true,
            nowEpochMillis = barEnd + 10_000,
            liveBid = liveBid,
            liveAsk = liveAsk,
            requireLivePriceChecks = true
        )
        assertEquals(
            TouchTurnSessionOutcome.NO_TRADE_ENTRY_NOT_TOUCHABLE,
            evaluated.touchTurnSession?.decisionOutcome
        )
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
    fun setupActionableForEntry_requiresLiquidityWhenLiquidityRangeEnabled() {
        val bar = OhlcBar(open = 100.0, high = 100.5, low = 99.0, close = 100.2)
        val setup = TouchTurnLogic.computeBracketSetup(bar, rangeThreshold = 2.0)
        assertFalse(setup.isLiquidityCandle)
        assertFalse(TouchTurnLogic.setupActionableForEntry(setup, TouchTurnRuleConfig.DEFAULT))
    }

    @Test
    fun setupActionableForEntry_allowsNonLiquidityWhenLiquidityRangeDisabled() {
        val bar = OhlcBar(open = 100.0, high = 100.5, low = 99.0, close = 100.2)
        val setup = TouchTurnLogic.computeBracketSetup(bar, rangeThreshold = 2.0)
        val rules = TouchTurnRuleConfig.DEFAULT.copy(
            enables = TouchTurnRuleEnables.DEFAULT.copy(liquidityRange = false)
        )
        assertTrue(TouchTurnLogic.setupActionableForEntry(setup, rules))
    }

    @Test
    fun barSetupBlockOutcome_skipsDisabledRules() {
        val bar = OhlcBar(open = 100.0, high = 100.5, low = 99.0, close = 100.2)
        val setup = TouchTurnLogic.computeBracketSetup(bar, rangeThreshold = 2.0)
        val rules = TouchTurnRuleConfig.DEFAULT.copy(
            enables = TouchTurnRuleEnables.DEFAULT.copy(
                liquidityRange = false,
                volumeExhaustion = false,
                notDoji = false
            )
        )
        assertNull(TouchTurnLogic.barSetupBlockOutcome(setup, volumeExhausted = true, rules))
    }

    @Test
    fun evaluateEntryGate_allowsWhenVolumeExhaustionDisabled() {
        val bar = OhlcBar(
            open = 400.0,
            high = 410.0,
            low = 399.0,
            close = 409.0,
            volume = 50_000.0,
            time = "20250522  09:30:00"
        )
        val setup = TouchTurnLogic.computeBracketSetup(bar, rangeThreshold = 5.0)
        val rules = TouchTurnRuleConfig.DEFAULT.copy(
            enables = TouchTurnRuleEnables.DEFAULT.copy(
                volumeExhaustion = false,
                barCloseTurn = false
            )
        )
        assertTrue(TouchTurnLogic.isVolumeExhaustion(bar.volume, 100.0, rules))
        val barEnd = TouchTurnLogic.barEndEpochMillis(bar.time!!, "America/New_York")!!
        val result = TouchTurnLogic.evaluateEntryGate(
            setup = setup,
            candle = bar,
            volumeSma20 = 100.0,
            marketZoneId = "America/New_York",
            nowEpochMillis = barEnd + 1_000,
            sessionDateIso = "2025-05-22",
            enforceCloseConfirmation = true,
            liveBid = null,
            liveAsk = null,
            liveLast = null,
            requireLivePriceChecks = false,
            rules = rules
        )
        assertTrue(result.entryOrdersPermitted)
        assertNull(result.decisionOutcome)
    }

    @Test
    fun evaluateEntryGate_blocksWhenEntryWindowExpiredAndEnforced() {
        val bar = OhlcBar(
            open = 591.6,
            high = 593.2,
            low = 587.2,
            close = 592.8,
            volume = 489_555.0,
            time = "20260608  08:00:00"
        )
        val setup = TouchTurnLogic.computeBracketSetup(bar, rangeThreshold = 0.5)
        val barEnd = TouchTurnLogic.barEndEpochMillis(bar.time!!, "Europe/London")!!
        val lateAfterClose = barEnd + TouchTurnDefaults.CLOSE_CONFIRMATION_AFTER_CLOSE_MS + 120_000L
        val result = TouchTurnLogic.evaluateEntryGate(
            setup = setup,
            candle = bar,
            volumeSma20 = 819_255.1,
            marketZoneId = "Europe/London",
            nowEpochMillis = lateAfterClose,
            sessionDateIso = "2026-06-08",
            enforceCloseConfirmation = true,
            liveBid = 592.5,
            liveAsk = 592.7,
            liveLast = 592.6,
            requireLivePriceChecks = true
        )
        assertEquals(false, result.entryOrdersPermitted)
        assertEquals(TouchTurnSessionOutcome.NO_TRADE_ENTRY_WINDOW_EXPIRED, result.decisionOutcome)
        assertEquals(TouchTurnCloseConfirmation.EXPIRED, result.closeConfirmation)
    }

    @Test
    fun withLiquidityEvaluatedIfClosed_blocksLateManualIbSessionStart() {
        val bar = OhlcBar(
            open = 591.6,
            high = 593.2,
            low = 587.2,
            close = 592.8,
            volume = 489_555.0,
            time = "20260608  08:00:00"
        )
        val barEnd = TouchTurnLogic.barEndEpochMillis(bar.time!!, "Europe/London")!!
        val lateAfterClose = barEnd + TouchTurnDefaults.CLOSE_CONFIRMATION_AFTER_CLOSE_MS + 120_000L
        val instance = defaultStrategyDeployment(
            strategyType = StrategyType.TOUCH_AND_TURN_SCALPER,
            symbol = "NWG",
            maxDollars = 10_000
        ).copy(
            touchTurnSession = TouchTurnSessionContext(
                sessionDate = "2026-06-08",
                status = TouchTurnCandleStatus.READY,
                openingBarTime = bar.time,
                candle = bar,
                adr14 = 2.27,
                atr14 = 2.27,
                volumeSma20 = 819_255.1,
                rangeThreshold = 0.5,
                marketZoneId = "Europe/London"
            )
        )
        val evaluated = instance.withLiquidityEvaluatedIfClosed(
            enforceCloseConfirmation = true,
            nowEpochMillis = lateAfterClose,
            liveBid = 592.5,
            liveAsk = 592.7,
            liveLast = 592.6,
            requireLivePriceChecks = true
        )
        assertEquals(false, evaluated.touchTurnSession?.entryOrdersPermitted)
        assertEquals(
            TouchTurnSessionOutcome.NO_TRADE_ENTRY_WINDOW_EXPIRED,
            evaluated.touchTurnSession?.decisionOutcome
        )
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
            close = 403.0,
            time = "20250522  09:30:00"
        )
        val setup = TouchTurnLogic.computeBracketSetup(bar, rangeThreshold = 5.0)
        val barEnd = TouchTurnLogic.barEndEpochMillis(bar.time!!, "Asia/Hong_Kong")!!
        val now = barEnd + 30_000
        assertEquals(
            TouchTurnCloseConfirmation.PASSED,
            TouchTurnLogic.closeConfirmation(bar, setup, "Asia/Hong_Kong", now)
        )
        assertTrue(TouchTurnLogic.closeConfirmsTurn(setup, bar))
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
        assertFalse(TouchTurnLogic.closeConfirmsTurn(setup, bar))
    }

    @Test
    fun closeConfirmation_passesWhenRedCloseAboveEntryWithinOneMinuteOfBarClose() {
        val bar = OhlcBar(
            open = 410.0,
            high = 410.0,
            low = 400.0,
            close = 407.0,
            time = "20250522  09:30:00"
        )
        val setup = TouchTurnLogic.computeBracketSetup(bar, rangeThreshold = 5.0)
        val barEnd = TouchTurnLogic.barEndEpochMillis(bar.time!!, "Asia/Hong_Kong")!!
        val now = barEnd + 30_000
        assertEquals(
            TouchTurnCloseConfirmation.PASSED,
            TouchTurnLogic.closeConfirmation(bar, setup, "Asia/Hong_Kong", now)
        )
        assertTrue(TouchTurnLogic.closeConfirmsTurn(setup, bar))
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
        assertFalse(TouchTurnLogic.closeConfirmsTurn(setup, bar))
    }

    @Test
    fun closeConfirmsTurn_failsWhenGreenCloseNotInLowerRange() {
        val bar = OhlcBar(open = 400.0, high = 410.0, low = 400.0, close = 408.0)
        val setup = TouchTurnLogic.computeBracketSetup(bar, rangeThreshold = 5.0)
        assertFalse(TouchTurnLogic.closePositionInTurnZone(setup, bar, bar.close))
        assertFalse(TouchTurnLogic.closeConfirmsTurn(setup, bar))
    }

    @Test
    fun pipelineCloseConfirmation_passesWhenEntryPermittedDespiteStrictBarRules() {
        val bar = OhlcBar(open = 410.0, high = 410.0, low = 400.0, close = 405.0, time = "20250522  09:30:00")
        val setup = TouchTurnLogic.computeBracketSetup(bar, rangeThreshold = 5.0)
        val session = TouchTurnSessionContext(
            sessionDate = "2025-05-22",
            status = TouchTurnCandleStatus.READY,
            candle = bar,
            setup = setup,
            entryOrdersPermitted = true,
            rangeThreshold = 5.0,
            marketZoneId = "Asia/Hong_Kong"
        )
        val barEnd = TouchTurnLogic.barEndEpochMillis(bar.time!!, "Asia/Hong_Kong")!!
        assertFalse(TouchTurnLogic.closeConfirmsTurn(setup, bar))
        assertEquals(
            TouchTurnCloseConfirmation.FAILED,
            session.closeConfirmation(barEnd + 10_000)
        )
        assertEquals(
            TouchTurnCloseConfirmation.PASSED,
            session.pipelineCloseConfirmation(barEnd + 10_000)
        )
    }

    @Test
    fun closeConfirmsTurn_failsWhenRedCloseNotInUpperRange() {
        val bar = OhlcBar(open = 410.0, high = 410.0, low = 400.0, close = 405.0)
        val setup = TouchTurnLogic.computeBracketSetup(bar, rangeThreshold = 5.0)
        assertFalse(TouchTurnLogic.closePositionInTurnZone(setup, bar, bar.close))
        assertFalse(TouchTurnLogic.closeConfirmsTurn(setup, bar))
    }

    @Test
    fun closeConfirmation_failsWhenGreenCloseOutsideTurnZone() {
        val bar = OhlcBar(
            open = 400.0,
            high = 410.0,
            low = 400.0,
            close = 409.5,
            time = "20250522  09:30:00"
        )
        val setup = TouchTurnLogic.computeBracketSetup(bar, rangeThreshold = 5.0)
        assertFalse(TouchTurnLogic.closeConfirmsTurn(setup, bar))
        val barEnd = TouchTurnLogic.barEndEpochMillis(bar.time!!, "Asia/Hong_Kong")!!
        assertEquals(
            TouchTurnCloseConfirmation.FAILED,
            TouchTurnLogic.closeConfirmation(bar, setup, "Asia/Hong_Kong", barEnd + 30_000)
        )
    }

    @Test
    fun closeConfirmation_failsWhenRedCloseOutsideTurnZone() {
        val bar = OhlcBar(
            open = 410.0,
            high = 410.0,
            low = 400.0,
            close = 400.5,
            time = "20250522  09:30:00"
        )
        val setup = TouchTurnLogic.computeBracketSetup(bar, rangeThreshold = 5.0)
        assertFalse(TouchTurnLogic.closeConfirmsTurn(setup, bar))
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

    @Test
    fun closeConfirmation_allowsDojiWhenNotDojiRuleDisabled() {
        val bar = OhlcBar(
            open = 592.6,
            high = 593.2,
            low = 587.2,
            close = 592.6,
            time = "20260608  08:00:00"
        )
        val setup = TouchTurnLogic.computeBracketSetup(bar, rangeThreshold = 0.5)
        assertFalse(setup.isActionable)
        val rules = TouchTurnRuleConfig.DEFAULT.copy(
            enables = TouchTurnRuleEnables.DEFAULT.copy(
                notDoji = false,
                barCloseTurn = false
            )
        )
        val barEnd = TouchTurnLogic.barEndEpochMillis(bar.time!!, "Europe/London")!!
        val now = barEnd + 30_000
        assertEquals(
            TouchTurnCloseConfirmation.PASSED,
            TouchTurnLogic.closeConfirmation(bar, setup, "Europe/London", now, rules = rules)
        )
    }

    @Test
    fun closeConfirmation_failsDojiWhenNotDojiRuleEnabled() {
        val bar = OhlcBar(
            open = 592.6,
            high = 593.2,
            low = 587.2,
            close = 592.6,
            time = "20260608  08:00:00"
        )
        val setup = TouchTurnLogic.computeBracketSetup(bar, rangeThreshold = 0.5)
        val rules = TouchTurnRuleConfig.DEFAULT.copy(
            enables = TouchTurnRuleEnables.DEFAULT.copy(notDoji = true)
        )
        val barEnd = TouchTurnLogic.barEndEpochMillis(bar.time!!, "Europe/London")!!
        val now = barEnd + 30_000
        assertEquals(
            TouchTurnCloseConfirmation.FAILED,
            TouchTurnLogic.closeConfirmation(bar, setup, "Europe/London", now, rules = rules)
        )
    }

    @Test
    fun ruleEnables_notDojiDisabledByDefault() {
        assertFalse(TouchTurnRuleEnables.DEFAULT.notDoji)
        assertFalse(TouchTurnRuleConfig.DEFAULT.enables.notDoji)
        assertFalse(TouchTurnRuleEnables.DEFAULT.openDeadline)
        assertFalse(TouchTurnRuleConfig.DEFAULT.enables.openDeadline)
    }

    @Test
    fun priorSessionOpeningFifteenMinuteBars_oneBarPerPriorRthDay() {
        val zone = "America/New_York"
        val session = "20260522"
        var day = LocalDate.of(2026, 5, 21)
        val bars = buildList {
            repeat(3) {
                val ymd = "%04d%02d%02d".format(day.year, day.monthValue, day.dayOfMonth)
                add(
                    OhlcBar(
                        open = 1.0,
                        high = 2.0,
                        low = 0.5,
                        close = 1.5,
                        time = "$ymd  09:30:00",
                        volume = 1_000.0
                    )
                )
                add(
                    OhlcBar(
                        open = 1.0,
                        high = 2.0,
                        low = 0.5,
                        close = 1.5,
                        time = "$ymd  10:00:00",
                        volume = 100.0
                    )
                )
                day = TouchTurnLogic.previousRthTradingDay(day.minusDays(1))
            }
        }
        val openings = TouchTurnLogic.priorSessionOpeningFifteenMinuteBars(bars, zone, session)
        assertEquals(3, openings.size)
        assertTrue(openings.all { it.time?.contains("09:30:00") == true })
        assertEquals(listOf(1_000.0, 1_000.0, 1_000.0), openings.map { it.volume })
    }

    @Test
    fun resolveSessionOpeningFifteenMinuteBar_fallsBackWhenScheduledOpenHasZeroVolume() {
        val zone = "Europe/London"
        val day = "20260603"
        val bars = listOf(
            OhlcBar(
                open = 100.0,
                high = 101.0,
                low = 99.0,
                close = 100.5,
                time = "$day  08:00:00",
                volume = 0.0
            ),
            OhlcBar(
                open = 100.0,
                high = 102.0,
                low = 99.5,
                close = 101.0,
                time = "$day  08:15:00",
                volume = 50_000.0
            )
        )
        val resolved = TouchTurnLogic.resolveSessionOpeningFifteenMinuteBar(bars, zone, day)
        assertEquals(50_000.0, resolved?.volume)
        assertEquals("$day  08:15:00", resolved?.time)
    }

    @Test
    fun deriveTouchTurnSignalContext_failsWhenFewerThanTwentyPriorSessionOpens() {
        val zone = "Europe/London"
        val session = "20260604"
        var day = LocalDate.of(2026, 6, 3)
        val priorOpenings = buildList {
            repeat(19) {
                val ymd = "%04d%02d%02d".format(day.year, day.monthValue, day.dayOfMonth)
                add(
                    OhlcBar(
                        open = 100.0,
                        high = 101.0,
                        low = 99.0,
                        close = 100.5,
                        time = "$ymd  08:00:00",
                        volume = 10_000.0
                    )
                )
                day = TouchTurnLogic.previousRthTradingDay(day.minusDays(1))
            }
        }
        val atrBars = (1..TouchTurnDefaults.ATR_LOOKBACK_PERIODS).map { slot ->
            OhlcBar(
                open = 100.0,
                high = 100.2,
                low = 99.8,
                close = 100.1,
                time = "20260603  %02d:%02d:00".format(8 + (slot * 15) / 60, (slot * 15) % 60),
                volume = 500.0
            )
        }
        val opening = OhlcBar(
            open = 100.0,
            high = 105.0,
            low = 99.0,
            close = 104.0,
            time = "$session  08:00:00",
            volume = 12_000.0
        )
        val result = TouchTurnLogic.deriveTouchTurnSignalContext(
            bars = priorOpenings + atrBars + opening,
            marketZoneId = zone,
            sessionDayYyyyMmdd = session
        )
        assertTrue(result.isFailure)
        assertTrue(
            result.exceptionOrNull()?.message?.contains("Need 20 session-opening") == true,
            result.exceptionOrNull()?.message
        )
    }

    @Test
    fun deriveTouchTurnSignalContext_preOpen_succeedsWithoutTodayOpeningBar() {
        val zone = "Europe/London"
        val session = "20260604"
        var day = LocalDate.of(2026, 6, 3)
        val priorOpenings = buildList {
            repeat(20) {
                val ymd = "%04d%02d%02d".format(day.year, day.monthValue, day.dayOfMonth)
                add(
                    OhlcBar(
                        open = 100.0,
                        high = 101.0,
                        low = 99.0,
                        close = 100.5,
                        time = "$ymd  08:00:00",
                        volume = 10_000.0
                    )
                )
                day = TouchTurnLogic.previousRthTradingDay(day.minusDays(1))
            }
        }
        val atrBars = (1..TouchTurnDefaults.ATR_LOOKBACK_PERIODS).map { slot ->
            OhlcBar(
                open = 100.0,
                high = 100.2,
                low = 99.8,
                close = 100.1,
                time = "20260603  %02d:%02d:00".format(8 + (slot * 15) / 60, (slot * 15) % 60),
                volume = 500.0
            )
        }
        val result = TouchTurnLogic.deriveTouchTurnSignalContext(
            bars = priorOpenings + atrBars,
            marketZoneId = zone,
            sessionDayYyyyMmdd = session,
            allowMissingTodayOpeningBar = true
        )
        assertTrue(result.isSuccess, result.exceptionOrNull()?.message)
        val ctx = result.getOrThrow()
        assertTrue(ctx.todayOpeningBarPending)
        assertTrue(ctx.hasBootstrapMetrics())
        assertTrue(ctx.firstCandle.time?.contains("08:00:00") == true)
    }

    @Test
    fun deriveTouchTurnSignalContext_withoutTodayBar_failsWithMarketLabel() {
        val zone = "Europe/London"
        val session = "20260604"
        val result = TouchTurnLogic.deriveTouchTurnSignalContext(
            bars = emptyList(),
            marketZoneId = zone,
            sessionDayYyyyMmdd = session,
            allowMissingTodayOpeningBar = false
        )
        assertTrue(result.isFailure)
        assertTrue(
            result.exceptionOrNull()?.message?.contains("UK (LSE)") == true,
            result.exceptionOrNull()?.message
        )
    }
}
