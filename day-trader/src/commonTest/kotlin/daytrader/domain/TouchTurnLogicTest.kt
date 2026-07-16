package daytrader.domain

import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertContains
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
    fun liquidityThresholdFromDailyAtr_is25PercentOfDailyAtr() {
        assertEquals(2.5, TouchTurnLogic.liquidityRangeThresholdFromDailyAtr(10.0), 0.001)
    }

    @Test
    fun evaluatesLiquidityCandle_requiresDailyGateWhenEnabled() {
        val bar = OhlcBar(open = 100.0, high = 110.0, low = 99.0, close = 108.0)
        val thresholds = TouchTurnLiquidityThresholds(thresholdDailyAtr = 8.0)
        val dailyOn = TouchTurnRuleConfig.DEFAULT.copy(
            enables = TouchTurnRuleEnables.DEFAULT.copy(
                liquidityRangeDailyAtr = true
            )
        )
        assertTrue(TouchTurnLogic.evaluatesLiquidityCandle(bar, thresholds, dailyOn))
        assertFalse(
            TouchTurnLogic.evaluatesLiquidityCandle(
                bar,
                thresholds.copy(thresholdDailyAtr = 12.0),
                dailyOn
            )
        )
    }

    @Test
    fun evaluatesLiquidityCandle_passesWhenLiquidityGateDisabled() {
        val bar = OhlcBar(open = 100.0, high = 100.1, low = 99.9, close = 100.0)
        val thresholds = TouchTurnLiquidityThresholds(thresholdDailyAtr = 5.0)
        val neither = TouchTurnRuleConfig.DEFAULT.copy(
            enables = TouchTurnRuleEnables.DEFAULT.copy(
                liquidityRangeDailyAtr = false
            )
        )
        assertTrue(TouchTurnLogic.evaluatesLiquidityCandle(bar, thresholds, neither))
    }

    @Test
    fun computeDailyAtr14_usesWilderAtrOnDailyBars() {
        val bars = (1..16).map { day ->
            OhlcBar(
                open = 100.0 + day,
                high = 102.0 + day,
                low = 99.0 + day,
                close = 101.0 + day,
                time = "202505${day.toString().padStart(2, '0')}  16:00:00"
            )
        }
        val dailyAtr = TouchTurnLogic.computeDailyAtr14(bars, excludeSessionDayYyyyMmdd = "20250516")
            .getOrThrow()
        assertTrue(dailyAtr > 0.0)
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
    fun takeProfitToStopLossRatio_tightensStop_whenRatioIncreased() {
        val bar = OhlcBar(open = 410.0, high = 410.0, low = 400.0, close = 402.0)
        val defaultSetup = TouchTurnLogic.computeBracketSetup(bar, rangeThreshold = 5.0)
        val tighterStopRules = TouchTurnRuleConfig.DEFAULT.copy(takeProfitToStopLossRatio = 4.0)
        val tighterSetup = TouchTurnLogic.computeBracketSetup(
            bar,
            rangeThreshold = 5.0,
            rules = tighterStopRules
        )
        assertEquals(defaultSetup.entry, tighterSetup.entry, 0.001)
        assertEquals(defaultSetup.takeProfit, tighterSetup.takeProfit, 0.001)
        val defaultRisk = defaultSetup.entry - defaultSetup.stopLoss
        val tighterRisk = tighterSetup.entry - tighterSetup.stopLoss
        assertTrue(tighterRisk < defaultRisk)
        val tpDistance = defaultSetup.takeProfit - defaultSetup.entry
        assertEquals(defaultSetup.entry - tpDistance / 4.0, tighterSetup.stopLoss, 0.001)
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
    fun entryOutwardOffset_inverseGreen_longStopAboveHigh() {
        val bar = OhlcBar(open = 400.0, high = 410.0, low = 400.0, close = 408.0)
        val rules = TouchTurnRuleConfig.DEFAULT.copy(
            invertTradeSide = true,
            entryOutwardOffsetRatioOfRange = 0.02
        )
        val setup = TouchTurnLogic.computeBracketSetup(bar, rangeThreshold = 5.0, rules = rules)
        assertEquals(TouchTurnTradeSide.LONG, setup.side)
        assertEquals(410.2, setup.entry, 0.001)
    }

    @Test
    fun entryOutwardOffset_inverseRed_shortStopBelowLow() {
        val bar = OhlcBar(open = 410.0, high = 410.0, low = 400.0, close = 402.0)
        val rules = TouchTurnRuleConfig.DEFAULT.copy(
            invertTradeSide = true,
            entryOutwardOffsetRatioOfRange = 0.02
        )
        val setup = TouchTurnLogic.computeBracketSetup(bar, rangeThreshold = 5.0, rules = rules)
        assertEquals(TouchTurnTradeSide.SHORT, setup.side)
        assertEquals(399.8, setup.entry, 0.001)
    }

    @Test
    fun entryOutwardOffset_zero_matchesCurrentInvertBehavior() {
        val bar = OhlcBar(open = 400.0, high = 410.0, low = 400.0, close = 408.0)
        val inwardOnly = TouchTurnRuleConfig.DEFAULT.copy(
            invertTradeSide = true,
            entryInwardOffsetRatioOfRange = 0.15,
            entryOutwardOffsetRatioOfRange = 0.0
        )
        val setup = TouchTurnLogic.computeBracketSetup(bar, rangeThreshold = 5.0, rules = inwardOnly)
        assertEquals(408.5, setup.entry, 0.001)
    }

    @Test
    fun entryOutwardOffset_ignoredWhenReversal() {
        val bar = OhlcBar(open = 400.0, high = 410.0, low = 400.0, close = 408.0)
        val rules = TouchTurnRuleConfig.DEFAULT.copy(
            invertTradeSide = false,
            entryInwardOffsetRatioOfRange = 0.15,
            entryOutwardOffsetRatioOfRange = 0.05
        )
        val setup = TouchTurnLogic.computeBracketSetup(bar, rangeThreshold = 5.0, rules = rules)
        assertEquals(TouchTurnTradeSide.SHORT, setup.side)
        assertEquals(408.5, setup.entry, 0.001)
    }

    @Test
    fun entryOutwardOffset_outwardWinsOverInwardWhenBothSet() {
        val bar = OhlcBar(open = 400.0, high = 410.0, low = 400.0, close = 408.0)
        val rules = TouchTurnRuleConfig.DEFAULT.copy(
            invertTradeSide = true,
            entryInwardOffsetRatioOfRange = 0.15,
            entryOutwardOffsetRatioOfRange = 0.02
        )
        val setup = TouchTurnLogic.computeBracketSetup(bar, rangeThreshold = 5.0, rules = rules)
        assertEquals(410.2, setup.entry, 0.001)
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
    fun deferLiquidityEvaluationForLiveQuotes_alwaysFalse() {
        assertFalse(
            TouchTurnLogic.deferLiquidityEvaluationForLiveQuotes(
                requireLivePriceChecks = true,
                liveBid = null,
                liveAsk = null,
                entryWindowStatus = TouchTurnEntryWindowStatus.WITHIN_WINDOW
            )
        )
    }

    @Test
    fun closeConfirmation_passesForLiquidityCandleAfterBarClose() {
        val bar = OhlcBar(
            open = 400.0,
            high = 410.0,
            low = 400.0,
            close = 403.0,
            time = "20250522  09:30:00"
        )
        val setup = TouchTurnLogic.computeBracketSetup(bar, rangeThreshold = 5.0)
        val barEnd = TouchTurnLogic.barEndEpochMillis(bar.time!!, "Asia/Hong_Kong")!!
        assertEquals(
            TouchTurnCloseConfirmation.PASSED,
            TouchTurnLogic.closeConfirmation(bar, setup, "Asia/Hong_Kong", barEnd + 30_000)
        )
    }

    @Test
    fun closeConfirmation_failsWhenNotLiquidityCandle() {
        val bar = OhlcBar(
            open = 400.0,
            high = 400.5,
            low = 399.5,
            close = 400.2,
            time = "20250522  09:30:00"
        )
        val rules = TouchTurnRuleConfig.DEFAULT.copy(
            enables = TouchTurnRuleEnables.DEFAULT.copy(liquidityRangeDailyAtr = true)
        )
        val setup = TouchTurnLogic.computeBracketSetup(bar, rangeThreshold = 5.0, rules = rules)
        val barEnd = TouchTurnLogic.barEndEpochMillis(bar.time!!, "Asia/Hong_Kong")!!
        assertEquals(
            TouchTurnCloseConfirmation.FAILED,
            TouchTurnLogic.closeConfirmation(bar, setup, "Asia/Hong_Kong", barEnd + 30_000, rules = rules)
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
            val expected = TouchTurnOrderDefaults.timeInForceFor(leg.role)
            assertEquals(expected, leg.timeInForce, "TIF for ${leg.role}")
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
    fun plannedOrders_invertedLong_usesStopEntry_onGreenLiquidityBar() {
        val bar = OhlcBar(open = 400.0, high = 410.0, low = 400.0, close = 408.0)
        val rules = TouchTurnRuleConfig.DEFAULT.copy(invertTradeSide = true)
        val setup = TouchTurnLogic.computeBracketSetup(bar, rangeThreshold = 5.0, rules = rules)
        val plan = TouchTurnOrderPlanner.buildOrderPlan("700", setup!!, maxDollars = 4100, rules = rules)!!
        assertEquals(TouchTurnTradeSide.LONG, plan.side)
        assertEquals("BUY", plan.orders[0].action)
        assertEquals("STP", plan.orders[0].orderType)
    }

    @Test
    fun plannedOrders_invertedShort_usesStopEntry_onRedLiquidityBar() {
        val bar = OhlcBar(open = 410.0, high = 410.0, low = 400.0, close = 402.0)
        val rules = TouchTurnRuleConfig.DEFAULT.copy(invertTradeSide = true)
        val setup = TouchTurnLogic.computeBracketSetup(bar, rangeThreshold = 5.0, rules = rules)
        val plan = TouchTurnOrderPlanner.buildOrderPlan("700", setup!!, maxDollars = 4000, rules = rules)!!
        assertEquals(TouchTurnTradeSide.SHORT, plan.side)
        assertEquals("SELL", plan.orders[0].action)
        assertEquals("STP", plan.orders[0].orderType)
    }

    @Test
    fun invertPlacementBlockOutcome_nullWhenSyntheticBidAskProvided() {
        val bar = OhlcBar(open = 380.33, high = 383.77, low = 379.92, close = 380.75, volume = 344_160.0)
        val rules = TouchTurnRuleConfig.DEFAULT.copy(invertTradeSide = true)
        val setup = TouchTurnLogic.computeBracketSetup(bar, rangeThreshold = 0.5, rules = rules)!!
        val plan = TouchTurnOrderPlanner.buildOrderPlan("700", setup, maxDollars = 500, rules = rules)!!
        val (bid, ask) = TouchTurnLogic.syntheticBidAskForInvertPlacement(plan, setup, bar.close)
        assertEquals(
            null,
            TouchTurnLogic.invertPlacementBlockOutcome(plan, bid, ask, rules)
        )
    }

    @Test
    fun stopEntryTriggered_buyStop_whenAskCrossesEntry() {
        assertTrue(TouchTurnLogic.stopEntryTriggered("BUY", bid = 99.0, ask = 100.0, stopPrice = 100.0))
        assertFalse(TouchTurnLogic.stopEntryTriggered("BUY", bid = 99.0, ask = 99.5, stopPrice = 100.0))
    }

    @Test
    fun stopEntryTriggered_sellStop_whenBidCrossesEntry() {
        assertTrue(TouchTurnLogic.stopEntryTriggered("SELL", bid = 100.0, ask = 100.5, stopPrice = 100.0))
        assertFalse(TouchTurnLogic.stopEntryTriggered("SELL", bid = 100.5, ask = 101.0, stopPrice = 100.0))
    }

    @Test
    fun liveEntryTouchable_invertedShort_trueWhenPriceAboveEntry() {
        val bar = OhlcBar(open = 410.0, high = 410.0, low = 400.0, close = 402.0)
        val rules = TouchTurnRuleConfig.DEFAULT.copy(invertTradeSide = true)
        val setup = TouchTurnLogic.computeBracketSetup(bar, rangeThreshold = 5.0, rules = rules)!!
        assertTrue(TouchTurnLogic.liveEntryTouchable(setup, bid = 401.0, ask = 401.5, invertTradeSide = true))
        assertFalse(TouchTurnLogic.liveEntryTouchable(setup, bid = 399.5, ask = 400.0, invertTradeSide = true))
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
    fun liveCloseConfirmsTurn_long_falseWhenLiveMidBelowEntryBand() {
        val bar = OhlcBar(open = 85.7, high = 85.7, low = 84.8, close = 85.0, time = "20260603  09:30:00")
        val setup = TouchTurnLogic.computeBracketSetup(bar, rangeThreshold = 0.1)
        assertFalse(TouchTurnLogic.closeConfirmsTurn(setup, bar))
        assertFalse(TouchTurnLogic.liveCloseConfirmsTurn(setup, bar, livePrice = 83.4))
    }

    @Test
    fun withLiquidityEvaluatedIfClosed_ignoresRemovedLiveGates() {
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
            nowEpochMillis = barEnd + 10_000
        )
        assertEquals(true, evaluated.touchTurnSession?.entryOrdersPermitted)
        assertNull(evaluated.touchTurnSession?.decisionOutcome)
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
        assertEquals(true, lateEval.touchTurnSession?.entryOrdersPermitted)
        assertEquals(
            TouchTurnCloseConfirmation.PASSED,
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
        val rules = TouchTurnRuleConfig.DEFAULT.copy(
            enables = TouchTurnRuleEnables.DEFAULT.copy(liquidityRangeDailyAtr = true)
        )
        val setup = TouchTurnLogic.computeBracketSetup(bar, rangeThreshold = 2.0, rules)
        assertNull(TouchTurnOrderPlanner.buildOrderPlan("SPY", setup, maxDollars = 5000, rules = rules))
    }

    @Test
    fun sizeQuantity_belowMinimumBoardLot() {
        val rules = InstrumentOrderSizeRules(minOrderSize = 1_000, orderSizeIncrement = 1_000)
        val sizing = TouchTurnOrderPlanner.sizeQuantity(maxDollars = 50_000, entryPrice = 211.4, rules)
        assertEquals(
            TouchTurnOrderSizingResult.BelowMinimum(
                rawQuantity = 236,
                minimumLot = 1_000,
                minimumNotional = 211_400.0,
            ),
            sizing
        )
    }

    @Test
    fun insufficientFundsDetailMessage_explainsMinLotGap() {
        val sizing = TouchTurnOrderSizingResult.BelowMinimum(
            rawQuantity = 236,
            minimumLot = 1_000,
            minimumNotional = 211_400.0,
        )
        val message = TouchTurnOrderPlanner.insufficientFundsDetailMessage(
            maxDollars = 50_000,
            currencyCode = "HKD",
            entryPrice = 211.4,
            sizing = sizing,
        )
        assertContains(message, "50000 HKD")
        assertContains(message, "236 shares")
        assertContains(message, "1000 shares")
        assertContains(message, "211400")
    }

    @Test
    fun setupActionableForEntry_requiresLiquidityWhenLiquidityRangeEnabled() {
        val bar = OhlcBar(open = 100.0, high = 100.5, low = 99.0, close = 100.2)
        val rules = TouchTurnRuleConfig.DEFAULT.copy(
            enables = TouchTurnRuleEnables.DEFAULT.copy(liquidityRangeDailyAtr = true)
        )
        val setup = TouchTurnLogic.computeBracketSetup(bar, rangeThreshold = 2.0, rules)
        assertFalse(setup.isLiquidityCandle)
        assertFalse(TouchTurnLogic.setupActionableForEntry(setup, rules))
    }

    @Test
    fun setupActionableForEntry_allowsNonLiquidityWhenLiquidityRangeDisabled() {
        val bar = OhlcBar(open = 100.0, high = 100.5, low = 99.0, close = 100.2)
        val setup = TouchTurnLogic.computeBracketSetup(bar, rangeThreshold = 2.0)
        val rules = TouchTurnRuleConfig.DEFAULT.copy(
            enables = TouchTurnRuleEnables.DEFAULT.copy(liquidityRangeDailyAtr = false)
        )
        assertTrue(TouchTurnLogic.setupActionableForEntry(setup, rules))
    }

    @Test
    fun barSetupBlockOutcome_blocksNonLiquidityWhenGateEnabled() {
        val bar = OhlcBar(open = 100.0, high = 100.5, low = 99.0, close = 100.2)
        val setup = TouchTurnLogic.computeBracketSetup(bar, rangeThreshold = 2.0)
        val rules = TouchTurnRuleConfig.DEFAULT.copy(
            enables = TouchTurnRuleEnables.DEFAULT.copy(liquidityRangeDailyAtr = true)
        )
        assertEquals(
            TouchTurnSessionOutcome.NO_TRADE_NOT_LIQUIDITY,
            TouchTurnLogic.barSetupBlockOutcome(setup, rules)
        )
    }

    @Test
    fun barSetupBlockOutcome_skipsRedLiquidityBarWhenEnabled() {
        val bar = OhlcBar(open = 100.0, high = 110.0, low = 99.0, close = 99.5)
        val setup = TouchTurnLogic.computeBracketSetup(bar, rangeThreshold = 5.0)
        assertTrue(setup.isLiquidityCandle)
        assertEquals(FirstCandleColor.RED, setup.candleColor)
        val rules = TouchTurnRuleConfig.DEFAULT.copy(
            redLiquidityBarAction = TouchTurnClosePositionTriggerMode.SKIP
        )
        assertEquals(
            TouchTurnSessionOutcome.NO_TRADE_OPENING_BAR_COLOR_SKIPPED,
            TouchTurnLogic.barSetupBlockOutcome(setup, rules)
        )
    }

    @Test
    fun barSetupBlockOutcome_skipsGreenLiquidityBarWhenEnabled() {
        val bar = OhlcBar(open = 100.0, high = 110.0, low = 99.0, close = 109.0)
        val setup = TouchTurnLogic.computeBracketSetup(bar, rangeThreshold = 5.0)
        assertTrue(setup.isLiquidityCandle)
        assertEquals(FirstCandleColor.GREEN, setup.candleColor)
        val rules = TouchTurnRuleConfig.DEFAULT.copy(
            greenLiquidityBarAction = TouchTurnClosePositionTriggerMode.SKIP
        )
        assertEquals(
            TouchTurnSessionOutcome.NO_TRADE_OPENING_BAR_COLOR_SKIPPED,
            TouchTurnLogic.barSetupBlockOutcome(setup, rules)
        )
    }

    @Test
    fun barSetupBlockOutcome_doesNotSkipNonLiquidityBarForColorGate() {
        val bar = OhlcBar(open = 100.0, high = 100.5, low = 99.0, close = 100.2)
        val setup = TouchTurnLogic.computeBracketSetup(bar, rangeThreshold = 5.0)
        assertFalse(setup.isLiquidityCandle)
        val rules = TouchTurnRuleConfig.DEFAULT.copy(
            enables = TouchTurnRuleEnables.DEFAULT.copy(liquidityRangeDailyAtr = false),
            greenLiquidityBarAction = TouchTurnClosePositionTriggerMode.SKIP,
            redLiquidityBarAction = TouchTurnClosePositionTriggerMode.SKIP
        )
        assertNull(TouchTurnLogic.barSetupBlockOutcome(setup, rules))
    }

    @Test
    fun barSetupBlockOutcome_skipsGreenLiquidityBarWhenClosePositionBelowThreshold() {
        val bar = OhlcBar(open = 100.0, high = 110.0, low = 99.0, close = 105.0)
        val setup = TouchTurnLogic.computeBracketSetup(bar, rangeThreshold = 5.0)
        assertEquals(FirstCandleColor.GREEN, setup.candleColor)
        assertEquals(0.5454545454545454, setup.closePositionRatio!!, 0.0001)
        val rules = TouchTurnRuleConfig.DEFAULT.copy(
            enables = TouchTurnRuleEnables.DEFAULT.copy(closePositionGate = true),
            greenSkipClosePositionBelow = 0.60
        )
        assertEquals(
            TouchTurnSessionOutcome.NO_TRADE_OPENING_BAR_CLOSE_POSITION_SKIPPED,
            TouchTurnLogic.barSetupBlockOutcome(setup, rules)
        )
    }

    @Test
    fun barSetupBlockOutcome_skipsRedLiquidityBarWhenClosePositionAboveThreshold() {
        val bar = OhlcBar(open = 108.0, high = 110.0, low = 99.0, close = 104.5)
        val setup = TouchTurnLogic.computeBracketSetup(bar, rangeThreshold = 5.0)
        assertEquals(FirstCandleColor.RED, setup.candleColor)
        assertEquals(0.5, setup.closePositionRatio!!, 0.0001)
        val rules = TouchTurnRuleConfig.DEFAULT.copy(
            enables = TouchTurnRuleEnables.DEFAULT.copy(closePositionGate = true),
            redSkipClosePositionAbove = 0.50
        )
        assertEquals(
            TouchTurnSessionOutcome.NO_TRADE_OPENING_BAR_CLOSE_POSITION_SKIPPED,
            TouchTurnLogic.barSetupBlockOutcome(setup, rules)
        )
    }

    @Test
    fun barSetupBlockOutcome_closePositionGateInclusiveAtThreshold() {
        val bar = OhlcBar(open = 100.0, high = 110.0, low = 99.0, close = 105.6)
        val setup = TouchTurnLogic.computeBracketSetup(bar, rangeThreshold = 5.0)
        assertEquals(0.6, setup.closePositionRatio!!, 0.0001)
        val rules = TouchTurnRuleConfig.DEFAULT.copy(
            enables = TouchTurnRuleEnables.DEFAULT.copy(closePositionGate = true),
            greenSkipClosePositionBelow = 0.60
        )
        assertEquals(
            TouchTurnSessionOutcome.NO_TRADE_OPENING_BAR_CLOSE_POSITION_SKIPPED,
            TouchTurnLogic.barSetupBlockOutcome(setup, rules)
        )
    }

    @Test
    fun barSetupBlockOutcome_closePositionGateDisabledByDefault() {
        val bar = OhlcBar(open = 100.0, high = 110.0, low = 99.0, close = 105.0)
        val setup = TouchTurnLogic.computeBracketSetup(bar, rangeThreshold = 5.0)
        val rules = TouchTurnRuleConfig.DEFAULT.copy(greenSkipClosePositionBelow = 0.60)
        assertNull(TouchTurnLogic.barSetupBlockOutcome(setup, rules))
    }

    @Test
    fun barSetupBlockOutcome_skipsGreenLiquidityBarWhenBodyRatioBelowThreshold() {
        // Wide green doji-ish: body 0.5 / range 11 ≈ 0.045 — not A′
        val bar = OhlcBar(open = 100.0, high = 110.0, low = 99.0, close = 100.5)
        val setup = TouchTurnLogic.computeBracketSetup(bar, rangeThreshold = 5.0)
        assertEquals(FirstCandleColor.GREEN, setup.candleColor)
        assertEquals(0.045454545454545456, setup.bodyRatio!!, 0.0001)
        val rules = TouchTurnRuleConfig.DEFAULT.copy(
            enables = TouchTurnRuleEnables.DEFAULT.copy(closePositionGate = true),
            greenSkipBodyRatioBelow = 0.70
        )
        assertEquals(
            TouchTurnSessionOutcome.NO_TRADE_OPENING_BAR_SHAPE_TRIGGER_SKIPPED,
            TouchTurnLogic.barSetupBlockOutcome(setup, rules)
        )
    }

    @Test
    fun barSetupBlockOutcome_allowsGreenAPrimeWhenBodyAndCpPass() {
        // A′: cp≈0.95, body≈0.91
        val bar = OhlcBar(open = 100.0, high = 111.0, low = 100.0, close = 110.5)
        val setup = TouchTurnLogic.computeBracketSetup(bar, rangeThreshold = 5.0)
        assertTrue(setup.closePositionRatio!! >= 0.85)
        assertTrue(setup.bodyRatio!! >= 0.70)
        val rules = TouchTurnRuleConfig.DEFAULT.copy(
            enables = TouchTurnRuleEnables.DEFAULT.copy(closePositionGate = true),
            // Skip small-body greens (not A′); this bar's body clears 0.70
            greenSkipBodyRatioBelow = 0.70
        )
        assertNull(TouchTurnLogic.barSetupBlockOutcome(setup, rules))
    }

    @Test
    fun closePositionTriggers_bodySkipStacksIndependentlyOfCp() {
        // Red A body but cp already mid — only body below should fire
        val bar = OhlcBar(open = 110.0, high = 110.0, low = 100.0, close = 109.5)
        val setup = TouchTurnLogic.computeBracketSetup(bar, rangeThreshold = 5.0)
        assertEquals(FirstCandleColor.RED, setup.candleColor)
        assertTrue(setup.bodyRatio!! < 0.20)
        val rules = TouchTurnRuleConfig.DEFAULT.copy(
            enables = TouchTurnRuleEnables.DEFAULT.copy(closePositionGate = true),
            redSkipBodyRatioBelow = 0.70,
            redBodyRatioBelowAction = TouchTurnClosePositionTriggerMode.SKIP
        )
        assertEquals(
            TouchTurnClosePositionTriggerEvaluation.SKIP,
            TouchTurnClosePositionTriggers.evaluate(setup, rules)
        )
        assertEquals(
            TouchTurnSessionOutcome.NO_TRADE_OPENING_BAR_SHAPE_TRIGGER_SKIPPED,
            TouchTurnClosePositionTriggers.skipOutcome(setup, rules)
        )
    }

    @Test
    fun evaluateEntryGate_allowsLiquidityCandle() {
        val bar = OhlcBar(
            open = 400.0,
            high = 410.0,
            low = 399.0,
            close = 409.0,
            volume = 50_000.0,
            time = "20250522  09:30:00"
        )
        val setup = TouchTurnLogic.computeBracketSetup(bar, rangeThreshold = 5.0)
        val barEnd = TouchTurnLogic.barEndEpochMillis(bar.time!!, "America/New_York")!!
        val result = TouchTurnLogic.evaluateEntryGate(
            setup = setup,
            candle = bar,
            marketZoneId = "America/New_York",
            nowEpochMillis = barEnd + 1_000,
            sessionDateIso = "2025-05-22"
        )
        assertTrue(result.entryOrdersPermitted)
        assertNull(result.decisionOutcome)
    }

    @Test
    fun withLiquidityEvaluatedIfClosed_permitsLiquidityBarAfterClose() {
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
            enforceCloseConfirmation = false,
            nowEpochMillis = lateAfterClose
        )
        assertEquals(true, evaluated.touchTurnSession?.entryOrdersPermitted)
        assertNull(evaluated.touchTurnSession?.decisionOutcome)
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
    fun closeConfirmation_legacyTurnZoneHelpers_stillAvailable() {
        val bar = OhlcBar(
            open = 400.0,
            high = 410.0,
            low = 400.0,
            close = 410.0,
            time = "20250522  09:30:00"
        )
        val setup = TouchTurnLogic.computeBracketSetup(bar, rangeThreshold = 5.0)
        val barEnd = TouchTurnLogic.barEndEpochMillis(bar.time!!, "Asia/Hong_Kong")!!
        assertEquals(
            TouchTurnCloseConfirmation.PASSED,
            TouchTurnLogic.closeConfirmation(bar, setup, "Asia/Hong_Kong", barEnd + 30_000)
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
            TouchTurnCloseConfirmation.PASSED,
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
    fun ruleEnables_openDeadlineDisabledByDefault() {
        assertFalse(TouchTurnRuleEnables.DEFAULT.openDeadline)
        assertFalse(TouchTurnRuleConfig.DEFAULT.enables.openDeadline)
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
    fun deriveTouchTurnSignalContext_succeedsWithoutVolumeHistory() {
        val zone = "Europe/London"
        val session = "20260604"
        val opening = OhlcBar(
            open = 100.0,
            high = 105.0,
            low = 99.0,
            close = 104.0,
            time = "$session  08:00:00",
            volume = 12_000.0
        )
        val result = TouchTurnLogic.deriveTouchTurnSignalContext(
            bars = listOf(opening),
            marketZoneId = zone,
            sessionDayYyyyMmdd = session,
            explicitFirstCandle = opening
        )
        assertTrue(result.isSuccess, result.exceptionOrNull()?.message)
        assertEquals(0.0, result.getOrThrow().volumeSma20)
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
    fun describeSignalContextBootstrapPendingLegs_dailyStillPending() {
        val detail = TouchTurnLogic.describeSignalContextBootstrapPendingLegs(
            bars15mReady = true,
            bars15mCount = 42,
            dailyBarsRequired = true,
            dailyBarsReady = false
        )
        assertEquals("15m_bars_ready(count=42),daily_bars", detail)
    }

    @Test
    fun describeSignalContextBootstrapPendingLegs_openingBarAndDailyPending() {
        val detail = TouchTurnLogic.describeSignalContextBootstrapPendingLegs(
            bars15mReady = false,
            dailyBarsRequired = true,
            dailyBarsReady = false
        )
        assertEquals("15m_opening_bar,daily_bars", detail)
    }

    @Test
    fun deriveTouchTurnSignalContext_dailyAtrOnly_skips15mAtrAndVolumeSma() {
        val opening = OhlcBar(
            open = 100.0,
            high = 105.0,
            low = 99.0,
            close = 104.0,
            time = "20260604  09:30:00",
            volume = 12_000.0
        )
        val dailyBars = (1..16).map { day ->
            OhlcBar(
                open = 100.0 + day,
                high = 102.0 + day,
                low = 98.0 + day,
                close = 101.0 + day,
                time = "202605${day.toString().padStart(2, '0')}  16:00:00",
                volume = 1_000_000.0
            )
        }
        val rules = TouchTurnRuleConfig.DEFAULT.copy(
            enables = TouchTurnRuleEnables.DEFAULT.copy(liquidityRangeDailyAtr = true)
        )
        val result = TouchTurnLogic.deriveTouchTurnSignalContext(
            bars = listOf(opening),
            marketZoneId = "America/New_York",
            sessionDayYyyyMmdd = "20260604",
            explicitFirstCandle = opening,
            dailyBars = dailyBars,
            rules = rules
        )
        assertTrue(result.isSuccess, result.exceptionOrNull()?.message)
        val ctx = result.getOrThrow()
        assertEquals(0.0, ctx.atr14)
        assertEquals(0.0, ctx.volumeSma20)
        assertTrue(ctx.dailyAtr14 != null && ctx.dailyAtr14 > 0.0)
        assertTrue(ctx.hasBootstrapMetrics(rules))
    }

    @Test
    fun deriveTouchTurnSignalContext_failsWhenDailyAtrRuleEnabledButDailyBarsMissing() {
        val opening = OhlcBar(
            open = 100.0,
            high = 105.0,
            low = 99.0,
            close = 104.0,
            time = "20260604  09:30:00",
            volume = 12_000.0
        )
        val rules = TouchTurnRuleConfig.DEFAULT.copy(
            enables = TouchTurnRuleEnables.DEFAULT.copy(liquidityRangeDailyAtr = true)
        )
        val result = TouchTurnLogic.deriveTouchTurnSignalContext(
            bars = listOf(opening),
            marketZoneId = "America/New_York",
            sessionDayYyyyMmdd = "20260604",
            explicitFirstCandle = opening,
            dailyBars = null,
            rules = rules
        )
        assertTrue(result.isFailure)
        assertTrue(
            result.exceptionOrNull()?.message?.contains("Daily bars required") == true,
            result.exceptionOrNull()?.message
        )
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

    @Test
    fun invertBracketSetup_greenBar_flipsSideAndPreservesDistances() {
        val bar = OhlcBar(open = 100.0, high = 110.0, low = 90.0, close = 108.0)
        val base = TouchTurnLogic.computeBracketSetup(bar, rangeThreshold = 5.0)!!
        assertEquals(TouchTurnTradeSide.SHORT, base.side)
        val inverted = TouchTurnLogic.invertBracketSetup(base)
        assertEquals(TouchTurnTradeSide.LONG, inverted.side)
        assertEquals(base.entry, inverted.entry)
        assertEquals(
            kotlin.math.abs(base.entry - base.takeProfit),
            kotlin.math.abs(inverted.takeProfit - inverted.entry),
            1e-9
        )
        assertEquals(
            kotlin.math.abs(base.entry - base.stopLoss),
            kotlin.math.abs(inverted.stopLoss - inverted.entry),
            1e-9
        )
        assertTrue(inverted.takeProfit > inverted.entry)
        assertTrue(inverted.stopLoss < inverted.entry)
    }

    @Test
    fun invertBracketSetup_redBar_flipsSideAndPreservesDistances() {
        val bar = OhlcBar(open = 100.0, high = 110.0, low = 90.0, close = 92.0)
        val base = TouchTurnLogic.computeBracketSetup(bar, rangeThreshold = 5.0)!!
        assertEquals(TouchTurnTradeSide.LONG, base.side)
        val inverted = TouchTurnLogic.invertBracketSetup(base)
        assertEquals(TouchTurnTradeSide.SHORT, inverted.side)
        assertEquals(base.entry, inverted.entry)
        assertTrue(inverted.takeProfit < inverted.entry)
        assertTrue(inverted.stopLoss > inverted.entry)
    }

    @Test
    fun withLiquidityEvaluatedIfClosed_inverted_flipsSide() {
        val bar = OhlcBar(open = 100.0, high = 110.0, low = 90.0, close = 108.0, time = "20260522  09:30:00")
        val rules = TouchTurnRuleConfig.DEFAULT.copy(
            invertTradeSide = true,
            enables = TouchTurnRuleEnables.DEFAULT.copy(liquidityRangeDailyAtr = true)
        )
        var deployment = defaultStrategyDeployment(
            strategyType = StrategyType.TOUCH_AND_TURN_SCALPER,
            symbol = "AAPL",
            maxDollars = 500
        ).copy(
            touchTurnRules = rules,
            touchTurnSession = TouchTurnSessionContext(
                sessionDate = "2026-05-22",
                status = TouchTurnCandleStatus.READY,
                candle = bar,
                adr14 = 10.0,
                rangeThreshold = 5.0,
                rules = rules,
                marketZoneId = "America/New_York"
            )
        )
        val barEnd = TouchTurnLogic.barEndEpochMillis(bar.time!!, "America/New_York")!!
        deployment = deployment.withLiquidityEvaluatedIfClosed(
            nowEpochMillis = barEnd + 30_000
        )
        val session = deployment.touchTurnSession!!
        assertEquals(TouchTurnTradeSide.LONG, session.setup?.side)
        assertEquals(true, session.entryOrdersPermitted)
        assertNull(session.decisionOutcome)
    }

    @Test
    fun invertPlacementBlockOutcome_allowsStopEntryWhenPriceHasNotCrossed() {
        val bar = OhlcBar(open = 530.0, high = 530.0, low = 510.2, close = 519.3)
        val rules = TouchTurnRuleConfig.DEFAULT.copy(
            invertTradeSide = true,
            takeProfitToStopLossRatio = 0.5,
            enables = TouchTurnRuleEnables.DEFAULT.copy(liquidityRangeDailyAtr = true)
        )
        val setup = TouchTurnLogic.computeBracketSetup(
            bar,
            TouchTurnLiquidityThresholds(thresholdDailyAtr = 14.0),
            rules
        )
        val plan = TouchTurnOrderPlanner.buildOrderPlan("AMD", setup, maxDollars = 10_000, rules = rules)!!
        assertEquals("STP", plan.orders.first { it.role == TouchTurnOrderRole.ENTRY }.orderType)
        val outcome = TouchTurnLogic.invertPlacementBlockOutcome(
            plan = plan,
            bid = 518.77,
            ask = 519.36,
            rules = rules
        )
        assertNull(outcome, "buy stop should not trigger while ask is below entry")
    }

    @Test
    fun invertPlacementBlockOutcome_allowsTriggeredStopEntryWhenStopNotTriggered() {
        val bar = OhlcBar(open = 530.0, high = 530.0, low = 510.2, close = 519.3)
        val rules = TouchTurnRuleConfig.DEFAULT.copy(
            invertTradeSide = true,
            takeProfitToStopLossRatio = 0.5,
            enables = TouchTurnRuleEnables.DEFAULT.copy(liquidityRangeDailyAtr = true)
        )
        val setup = TouchTurnLogic.computeBracketSetup(
            bar,
            TouchTurnLiquidityThresholds(thresholdDailyAtr = 14.0),
            rules
        )
        val plan = TouchTurnOrderPlanner.buildOrderPlan("AMD", setup, maxDollars = 10_000, rules = rules)!!
        val entry = plan.orders.first { it.role == TouchTurnOrderRole.ENTRY }.price
        val outcome = TouchTurnLogic.invertPlacementBlockOutcome(
            plan = plan,
            bid = entry - 0.1,
            ask = entry + 5.0,
            rules = rules
        )
        assertNull(outcome, "triggered sell stop with ask below protective stop should place bracket")
    }

    @Test
    fun invertPlacementBlockOutcome_jpmGreenInvertedLong_allowsRestingStopBelowMarket() {
        val bar = OhlcBar(open = 332.18, high = 335.77, low = 331.5, close = 335.61)
        val rules = TouchTurnRuleConfig.DEFAULT.copy(
            invertTradeSide = true,
            takeProfitToStopLossRatio = 0.5,
            enables = TouchTurnRuleEnables.DEFAULT.copy(liquidityRangeDailyAtr = true)
        )
        val setup = TouchTurnLogic.computeBracketSetup(
            bar,
            TouchTurnLiquidityThresholds(thresholdDailyAtr = 2.0),
            rules
        )
        val plan = TouchTurnOrderPlanner.buildOrderPlan("JPM", setup, maxDollars = 10_000, rules = rules)!!
        val outcome = TouchTurnLogic.invertPlacementBlockOutcome(
            plan = plan,
            bid = 335.55,
            ask = 335.65,
            rules = rules
        )
        assertNull(outcome, "buy stop resting below market should place bracket")
    }

    @Test
    fun invertPlacementBlockOutcome_blocksTriggeredStopEntryAndProtectiveStopOnSameQuote() {
        val bar = OhlcBar(open = 530.0, high = 530.0, low = 510.2, close = 519.3)
        val rules = TouchTurnRuleConfig.DEFAULT.copy(
            invertTradeSide = true,
            enables = TouchTurnRuleEnables.DEFAULT.copy(liquidityRangeDailyAtr = true)
        )
        val setup = TouchTurnLogic.computeBracketSetup(bar, rangeThreshold = 14.0, rules = rules)
        val plan = TouchTurnOrderPlanner.buildOrderPlan("AMD", setup, maxDollars = 10_000, rules = rules)!!
        val entry = plan.orders.first { it.role == TouchTurnOrderRole.ENTRY }.price
        val stop = plan.orders.first { it.role == TouchTurnOrderRole.STOP_LOSS }.price
        val outcome = TouchTurnLogic.invertPlacementBlockOutcome(
            plan = plan,
            bid = entry - 0.1,
            ask = stop + 0.1,
            rules = rules
        )
        assertEquals(TouchTurnSessionOutcome.NO_TRADE_INVERT_STOP_WOULD_TRIGGER, outcome)
    }

    @Test
    fun invertPlacementBlockOutcome_blocksWhenStopEntryAndProtectiveStopTriggerTogether() {
        val bar = OhlcBar(open = 100.0, high = 110.0, low = 90.0, close = 108.0)
        val rules = TouchTurnRuleConfig.DEFAULT.copy(invertTradeSide = true)
        val setup = TouchTurnLogic.computeBracketSetup(bar, rangeThreshold = 5.0, rules = rules)
        val plan = TouchTurnOrderPlanner.buildOrderPlan("AAPL", setup, maxDollars = 500, rules = rules)!!
        val stopPrice = plan.orders.first { it.role == TouchTurnOrderRole.STOP_LOSS }.price
        val outcome = TouchTurnLogic.invertPlacementBlockOutcome(
            plan = plan,
            bid = stopPrice - 0.5,
            ask = setup.entry + 0.5,
            rules = rules
        )
        assertEquals(TouchTurnSessionOutcome.NO_TRADE_INVERT_STOP_WOULD_TRIGGER, outcome)
    }

    @Test
    fun invertPlacementBlockOutcome_allowsRestingStopAboveMarket() {
        val bar = OhlcBar(open = 100.0, high = 110.0, low = 90.0, close = 92.0)
        val rules = TouchTurnRuleConfig.DEFAULT.copy(invertTradeSide = true)
        val setup = TouchTurnLogic.computeBracketSetup(bar, rangeThreshold = 5.0, rules = rules)
        val plan = TouchTurnOrderPlanner.buildOrderPlan("AAPL", setup, maxDollars = 500, rules = rules)!!
        val outcome = TouchTurnLogic.invertPlacementBlockOutcome(
            plan = plan,
            bid = setup.entry + 0.5,
            ask = setup.entry + 1.0,
            rules = rules
        )
        assertNull(outcome, "sell stop resting above market should place bracket")
    }

    @Test
    fun computeBracketSetup_greenInverted_higherFib_widensTakeProfitFromEntry() {
        val bar = OhlcBar(open = 100.0, high = 110.0, low = 90.0, close = 108.0)
        val narrow = TouchTurnLogic.computeBracketSetup(
            bar,
            rangeThreshold = 5.0,
            rules = TouchTurnRuleConfig.DEFAULT.copy(
                invertTradeSide = true,
                takeProfitFibRatioGreen = 0.3
            )
        )
        val wide = TouchTurnLogic.computeBracketSetup(
            bar,
            rangeThreshold = 5.0,
            rules = TouchTurnRuleConfig.DEFAULT.copy(
                invertTradeSide = true,
                takeProfitFibRatioGreen = 0.5
            )
        )
        assertEquals(TouchTurnTradeSide.LONG, narrow.side)
        assertEquals(TouchTurnTradeSide.LONG, wide.side)
        val narrowTpDistance = narrow.takeProfit - narrow.entry
        val wideTpDistance = wide.takeProfit - wide.entry
        assertTrue(wideTpDistance > narrowTpDistance)
        assertEquals(20.0 * 0.3, narrowTpDistance, 1e-9)
        assertEquals(20.0 * 0.5, wideTpDistance, 1e-9)
    }

    @Test
    fun computeBracketSetup_greenInverted_higherTpSlRatio_tightensStop() {
        val bar = OhlcBar(open = 100.0, high = 110.0, low = 90.0, close = 108.0)
        val defaultStop = TouchTurnLogic.computeBracketSetup(
            bar,
            rangeThreshold = 5.0,
            rules = TouchTurnRuleConfig.DEFAULT.copy(invertTradeSide = true)
        )
        val tighterStop = TouchTurnLogic.computeBracketSetup(
            bar,
            rangeThreshold = 5.0,
            rules = TouchTurnRuleConfig.DEFAULT.copy(
                invertTradeSide = true,
                takeProfitToStopLossRatio = 4.0
            )
        )
        assertEquals(defaultStop.entry, tighterStop.entry, 1e-9)
        assertEquals(defaultStop.takeProfit, tighterStop.takeProfit, 1e-9)
        val defaultRisk = defaultStop.entry - defaultStop.stopLoss
        val tighterRisk = tighterStop.entry - tighterStop.stopLoss
        assertTrue(tighterRisk < defaultRisk)
    }

    @Test
    fun invertPlacementBlockOutcome_skippedWhenInvertDisabled() {
        val bar = OhlcBar(open = 530.0, high = 530.0, low = 510.2, close = 519.3)
        val setup = TouchTurnLogic.computeBracketSetup(bar, rangeThreshold = 14.0)
        val plan = TouchTurnOrderPlanner.buildOrderPlan("AMD", setup, maxDollars = 10_000)!!
        val outcome = TouchTurnLogic.invertPlacementBlockOutcome(
            plan = plan,
            bid = 518.77,
            ask = 519.36,
            rules = TouchTurnRuleConfig.DEFAULT.copy(invertTradeSide = false)
        )
        assertNull(outcome)
    }

    @Test
    fun closePositionTriggers_evaluate_skipWinsOverSwitch() {
        val bar = OhlcBar(open = 110.0, high = 110.0, low = 100.0, close = 101.0)
        val rules = TouchTurnRuleConfig.DEFAULT.copy(
            invertTradeSide = true,
            enables = TouchTurnRuleEnables.DEFAULT.copy(closePositionGate = true),
            redSkipClosePositionBelow = 0.15,
            redClosePositionBelowAction = TouchTurnClosePositionTriggerMode.SKIP,
            greenSkipClosePositionAbove = 0.85,
            greenClosePositionAboveAction = TouchTurnClosePositionTriggerMode.SWITCH_TO_TOUCH_TURN
        )
        val setup = TouchTurnLogic.computeBracketSetup(
            bar,
            TouchTurnLiquidityThresholds(thresholdDailyAtr = 5.0),
            rules
        )
        assertEquals(
            TouchTurnClosePositionTriggerEvaluation.SKIP,
            TouchTurnClosePositionTriggers.evaluate(setup, rules)
        )
    }

    @Test
    fun closePositionTriggers_evaluate_switchToTouchTurnWhenInvertOn() {
        val bar = OhlcBar(open = 110.0, high = 110.0, low = 100.0, close = 101.0)
        val rules = TouchTurnRuleConfig.DEFAULT.copy(
            invertTradeSide = true,
            redSkipClosePositionBelow = 0.15,
            redClosePositionBelowAction = TouchTurnClosePositionTriggerMode.SWITCH_TO_TOUCH_TURN,
            enables = TouchTurnRuleEnables.DEFAULT.copy(liquidityRangeDailyAtr = true)
        )
        val setup = TouchTurnLogic.computeBracketSetup(
            bar,
            TouchTurnLiquidityThresholds(thresholdDailyAtr = 5.0),
            rules
        )
        assertEquals(
            TouchTurnClosePositionTriggerEvaluation.SWITCH_TO_TOUCH_TURN,
            TouchTurnClosePositionTriggers.evaluate(setup, rules)
        )
    }

    @Test
    fun withLiquidityEvaluatedIfClosed_closePositionSkipBlocksTrade() {
        val bar = OhlcBar(open = 110.0, high = 110.0, low = 100.0, close = 101.0, time = "20260708  09:30:00")
        val rules = TouchTurnRuleConfig.DEFAULT.copy(
            invertTradeSide = true,
            enables = TouchTurnRuleEnables.DEFAULT.copy(
                closePositionGate = true,
                liquidityRangeDailyAtr = true
            ),
            redSkipClosePositionBelow = 0.15,
            redClosePositionBelowAction = TouchTurnClosePositionTriggerMode.SKIP
        )
        var deployment = defaultStrategyDeployment(
            strategyType = StrategyType.TOUCH_AND_TURN_SCALPER,
            symbol = "00700",
            maxDollars = 500
        ).copy(
            touchTurnRules = rules,
            touchTurnSession = TouchTurnSessionContext(
                sessionDate = "2026-07-08",
                status = TouchTurnCandleStatus.READY,
                candle = bar,
                dailyAtr14 = 10.0,
                rangeThreshold = 5.0,
                rules = rules,
                marketZoneId = "Asia/Hong_Kong"
            )
        )
        val barEnd = TouchTurnLogic.barEndEpochMillis(bar.time!!, "Asia/Hong_Kong")!!
        deployment = deployment.withLiquidityEvaluatedIfClosed(nowEpochMillis = barEnd + 30_000)
        val session = deployment.touchTurnSession!!
        assertEquals(
            TouchTurnSessionOutcome.NO_TRADE_OPENING_BAR_CLOSE_POSITION_SKIPPED,
            session.decisionOutcome
        )
        assertEquals(false, session.entryOrdersPermitted)
    }

    @Test
    fun withLiquidityEvaluatedIfClosed_closePositionSwitchUsesTouchTurnLong() {
        val bar = OhlcBar(open = 110.0, high = 110.0, low = 100.0, close = 101.0, time = "20260708  09:30:00")
        val rules = TouchTurnRuleConfig.DEFAULT.copy(
            invertTradeSide = true,
            redSkipClosePositionBelow = 0.15,
            redClosePositionBelowAction = TouchTurnClosePositionTriggerMode.SWITCH_TO_TOUCH_TURN,
            enables = TouchTurnRuleEnables.DEFAULT.copy(liquidityRangeDailyAtr = true)
        )
        var deployment = defaultStrategyDeployment(
            strategyType = StrategyType.TOUCH_AND_TURN_SCALPER,
            symbol = "00700",
            maxDollars = 500
        ).copy(
            touchTurnRules = rules,
            touchTurnSession = TouchTurnSessionContext(
                sessionDate = "2026-07-08",
                status = TouchTurnCandleStatus.READY,
                candle = bar,
                dailyAtr14 = 10.0,
                rangeThreshold = 5.0,
                rules = rules,
                marketZoneId = "Asia/Hong_Kong"
            )
        )
        val barEnd = TouchTurnLogic.barEndEpochMillis(bar.time!!, "Asia/Hong_Kong")!!
        deployment = deployment.withLiquidityEvaluatedIfClosed(nowEpochMillis = barEnd + 30_000)
        val session = deployment.touchTurnSession!!
        assertEquals(false, session.rules.invertTradeSide)
        assertEquals(TouchTurnTradeSide.LONG, session.setup?.side)
        assertEquals(true, session.entryOrdersPermitted)
    }

    @Test
    fun withLiquidityEvaluatedIfClosed_greenBarSwitchUsesTouchTurnShort() {
        val bar = OhlcBar(open = 100.0, high = 110.0, low = 100.0, close = 108.0, time = "20260708  09:30:00")
        val rules = TouchTurnRuleConfig.DEFAULT.copy(
            invertTradeSide = true,
            greenLiquidityBarAction = TouchTurnClosePositionTriggerMode.SWITCH_TO_TOUCH_TURN,
            enables = TouchTurnRuleEnables.DEFAULT.copy(liquidityRangeDailyAtr = true)
        )
        var deployment = defaultStrategyDeployment(
            strategyType = StrategyType.TOUCH_AND_TURN_SCALPER,
            symbol = "00700",
            maxDollars = 500
        ).copy(
            touchTurnRules = rules,
            touchTurnSession = TouchTurnSessionContext(
                sessionDate = "2026-07-08",
                status = TouchTurnCandleStatus.READY,
                candle = bar,
                dailyAtr14 = 10.0,
                rangeThreshold = 5.0,
                rules = rules,
                marketZoneId = "Asia/Hong_Kong"
            )
        )
        val barEnd = TouchTurnLogic.barEndEpochMillis(bar.time!!, "Asia/Hong_Kong")!!
        deployment = deployment.withLiquidityEvaluatedIfClosed(nowEpochMillis = barEnd + 30_000)
        val session = deployment.touchTurnSession!!
        assertEquals(false, session.rules.invertTradeSide)
        assertEquals(TouchTurnTradeSide.SHORT, session.setup?.side)
    }
}
